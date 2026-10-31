package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Corazon del registro de objetos del staff: guarda la configuracion, escribe cada fila en
 * el {@link AuditStore}, reparte las marcas de origen y decide de que se avisa.
 *
 * <p>Todo lo que entra aqui puede llegar desde cualquier hilo de region (Folia): el estado
 * compartido son mapas concurrentes y colas, nunca inventarios ni entidades.
 */
final class ItemAuditManager {

    static final String ALERT_PERMISSION = "chatalyst.itemaudit.alerts";
    static final String BYPASS_PERMISSION = "chatalyst.itemaudit.bypass";
    static final String COMMAND_PERMISSION = "chatalyst.command.itemaudit";
    static final String CONSOLE = "CONSOLE";

    private final ChatalystPlugin plugin;
    private final AuditStore store;
    private final ItemMarks marks;
    private final AuditAlerts alerts;
    /** Ultimo aviso de traspaso por marca+receptor: mover el stack dentro del cofre no es otro. */
    private final Map<String, Long> transfers = new ConcurrentHashMap<>();

    private volatile AuditSettings settings = AuditSettings.disabled();
    private ScheduledTask flushTask;
    private ScheduledTask cleanupTask;
    private ScheduledTask alertTask;
    private ScheduledTask recheckTask;
    private Runnable recheck = () -> { };

    ItemAuditManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
        Path dir = plugin.getDataFolder().toPath().resolve("logs");
        this.store = new AuditStore(dir, plugin.getLogger(), ZoneId.systemDefault());
        this.marks = new ItemMarks(plugin);
        this.alerts = new AuditAlerts(plugin, this);
    }

    void load() {
        settings = AuditSettings.load(plugin.getConfig().getConfigurationSection(AuditSettings.ROOT),
                problem -> plugin.getLogger().warning(AuditSettings.ROOT + ": " + problem),
                ItemAuditManager::tagMaterials);
    }

    /** Quien reaplica las reglas de gamemode a los conectados (lo pone {@link GamemodeGuard}). */
    void onRecheck(Runnable action) {
        this.recheck = action == null ? () -> { } : action;
    }

    synchronized void start() {
        stopTasks();
        AuditSettings current = settings;
        if (!current.enabled()) return;
        var async = plugin.getServer().getAsyncScheduler();
        flushTask = async.runAtFixedRate(plugin, ignored -> store.flush(), 2, 2, TimeUnit.SECONDS);
        // La limpieza al arrancar y luego cada 12 h: un servidor que pasa semanas encendido no
        // debe acumular dias mas alla de la retencion.
        cleanupTask = async.runAtFixedRate(plugin, ignored -> cleanup(), 5, 12 * 3600,
                TimeUnit.SECONDS);
        alertTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                ignored -> {
                    alerts.tick(System.currentTimeMillis());
                    long now = System.currentTimeMillis();
                    marks.expire(now, settings.markReuseMillis());
                    transfers.values().removeIf(time -> now - time > settings.transferRepeatMillis());
                }, 20L, 20L);
        if (current.gamemode().restrict() && current.recheckSeconds() > 0) {
            long period = current.recheckSeconds() * 20L;
            recheckTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> recheck.run(), period, period);
        }
        // Tras un /reload con gente dentro: quien ya estaba en un modo prohibido se corrige.
        recheck.run();
        plugin.getLogger().info("Item audit: on (" + current.patterns().size()
                + " give command pattern(s), retention " + current.retentionDays() + " days"
                + (current.gamemode().restrict() ? ", gamemodes restricted" : "") + ").");
    }

    /** Apaga: lo pendiente se escribe AHORA, en este hilo, para no perder ninguna fila. */
    synchronized void stop() {
        stopTasks();
        alerts.discard();
        store.flush();
        marks.clear();
        transfers.clear();
    }

    private void stopTasks() {
        for (ScheduledTask task : new ScheduledTask[]{flushTask, cleanupTask, alertTask, recheckTask}) {
            if (task != null) task.cancel();
        }
        flushTask = null;
        cleanupTask = null;
        alertTask = null;
        recheckTask = null;
    }

    private void cleanup() {
        int deleted = store.cleanup(LocalDate.now(), settings.retentionDays());
        if (deleted > 0) {
            plugin.getLogger().info("Item audit: deleted " + deleted + " day(s) older than "
                    + settings.retentionDays() + " days.");
        }
    }

    AuditSettings settings() {
        return settings;
    }

    boolean enabled() {
        return settings.enabled();
    }

    ItemMarks marks() {
        return marks;
    }

    AuditAlerts alerts() {
        return alerts;
    }

    ChatalystPlugin plugin() {
        return plugin;
    }

    // ---- registrar ---------------------------------------------------------------------

    /** Escribe la fila y, si toca, la manda a los avisos (agrupada en rafagas). */
    void record(AuditEntry entry, boolean alert) {
        store.append(entry);
        if (alert) alerts.queue(entry);
    }

    /**
     * Que se avisa en vivo. Siempre: un objeto marcado que llega a otro jugador y lo que un
     * comando da a OTRO jugador, que es justo la sospecha. Lo que el staff se saca para si
     * mismo, solo si es valioso (o todo, con Alerts.Everything): construir no es noticia.
     */
    boolean alertsFor(AuditEntry entry) {
        if (entry.type() == AuditType.TRANSFER) return true;
        if ((entry.type() == AuditType.GIVE || entry.type() == AuditType.COMMAND)
                && entry.hasTarget()) {
            return true;
        }
        return settings.alertEverything() || entry.valuable();
    }

    boolean valuable(ValuableRules.ItemFacts facts) {
        return settings.valuable().valuable(facts);
    }

    /**
     * True la primera vez en la ventana que ESTA marca llega a ESTE jugador: sacar y meter el
     * mismo stack en un cofre diez veces es un traspaso, no diez.
     */
    boolean firstTransfer(String id, UUID receiver, long now) {
        String key = id + "|" + receiver;
        Long previous = transfers.get(key);
        if (previous != null && now - previous < settings.transferRepeatMillis()) return false;
        transfers.put(key, now);
        return true;
    }

    CompletableFuture<List<AuditEntry>> query(AuditQuery query, int limit) {
        CompletableFuture<List<AuditEntry>> result = new CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                try {
                    result.complete(store.query(query, limit));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException shuttingDown) {
            result.completeExceptionally(shuttingDown);
        }
        return result;
    }

    // ---- quien es quien ---------------------------------------------------------------------

    /**
     * Quien no deja rastro: el dueño con el bypass. La consola y los OP, si.
     *
     * <p>Un OP se audita SIEMPRE. Con LuckPerms (y con Bukkit a secas si el nodo no esta
     * declarado) ser OP responde "si" a cualquier permiso, bypass incluido: justo las cuentas
     * que mas objetos pueden sacar quedarian fuera del registro sin que nadie lo decidiera.
     * Quien deba estar exento recibe el nodo y no juega con OP.
     */
    static boolean exempt(CommandSender sender) {
        return sender instanceof Player player && !player.isOp()
                && player.hasPermission(BYPASS_PERMISSION);
    }

    /**
     * El rango para el aviso ("Admin Fulano"): el nombre visible del grupo principal de
     * LuckPerms. Vacio sin LuckPerms o para el grupo por defecto.
     */
    static String rank(UUID id) {
        if (id == null || !LuckPermsHook.isAvailable()) return "";
        String group = LuckPermsHook.group(id);
        if (group == null || group.isBlank() || group.equalsIgnoreCase("default")) return "";
        String display = dev.chatalyst.util.MM.strip(LuckPermsHook.groupDisplayName(group));
        if (display != null && !display.isBlank()) return display.strip();
        return Character.toUpperCase(group.charAt(0)) + group.substring(1).toLowerCase(Locale.ROOT);
    }

    /** La fila base con el autor y el sitio; el que llama completa objeto, destino y detalle. */
    static AuditEntry entry(String id, AuditType type, UUID actorId, String actorName,
                            UUID targetId, String targetName, String item, int amount,
                            String detail, String source, Location where, boolean valuable) {
        String world = where == null || where.getWorld() == null ? "" : where.getWorld().getName();
        return new AuditEntry(System.currentTimeMillis(), id, type, actorId, actorName,
                rank(actorId), targetId, targetName, item, amount, detail, source, world,
                where == null ? 0 : where.getBlockX(), where == null ? 0 : where.getBlockY(),
                where == null ? 0 : where.getBlockZ(), valuable);
    }

    /** Fila para un stack concreto (creativo, entrega vista, traspaso). */
    AuditEntry itemEntry(String id, AuditType type, UUID actorId, String actorName,
                         UUID targetId, String targetName, ItemStack item, int amount,
                         String source, Location where) {
        ValuableRules.ItemFacts facts = ItemMarks.facts(item);
        return entry(id, type, actorId, actorName, targetId, targetName, facts.key(), amount,
                ItemMarks.detail(facts), source, where, valuable(facts));
    }

    /** {@code #minecraft:logs} -&gt; sus materiales, para la lista de objetos valiosos. */
    private static List<String> tagMaterials(String raw) {
        NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        if (key == null) return List.of();
        Tag<Material> tag = org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        if (tag == null) tag = org.bukkit.Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
        if (tag == null) return List.of();
        Set<Material> values = tag.getValues();
        return values.stream().map(material -> material.getKey().toString()).toList();
    }
}
