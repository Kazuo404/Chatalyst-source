package dev.chatalyst.itemwatch;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.PlayerVaultsHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vigilancia de objetos: avisa al staff de cantidades absurdas, ganancias demasiado rapidas y
 * stacks imposibles, en el inventario, el ender chest, las mochilas de PlayerVaults y los
 * contenedores que un jugador abre.
 *
 * <p>Solo AVISA. No quita nada ni sanciona: un falso positivo (alguien que de verdad minó
 * mucho, un evento con premios) no le cuesta nada a nadie, y el staff decide con el inventario
 * delante. El aviso trae un clic para abrirlo.
 */
public final class ItemWatchManager {

    /**
     * Cuanto se reusa lo leido de las mochilas en el escaneo PERIODICO. Corto, y ademas se
     * tira en cuanto alguien cierra una mochila (ver {@link #vaultClosed}): con 10 minutos fijos,
     * meter diamantes en una mochila y revisar al jugador enseñaba la foto de cuando entro.
     */
    private static final long VAULT_REFRESH_MILLIS = 2 * 60_000L;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final ChatalystPlugin plugin;
    private final GainTracker gains = new GainTracker();
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<UUID, VaultSnapshot> vaults = new ConcurrentHashMap<>();
    /** Staff que ha silenciado los avisos con /itemwatch alerts (solo esta sesion). */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    private volatile ItemWatchSettings settings = ItemWatchSettings.disabled();
    private ScheduledTask task;

    private record VaultSnapshot(long time, List<WatchSource> sources) {
    }

    /**
     * Resultado de revisar a un jugador: el total de cada regla y el desglose por sitio
     * (inventario, ender chest, cada mochila), para que el staff sepa DONDE mirar.
     */
    public record Report(Map<String, Long> totals, List<WatchedStack> overstacked,
                         boolean vaultsIncluded, List<WatchSource> sources) {
    }

    public ItemWatchManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        settings = ItemWatchSettings.load(
                plugin.getConfig().getConfigurationSection(ItemWatchSettings.ROOT),
                problem -> plugin.getLogger().warning("Item_Watch: " + problem));
    }

    public synchronized void start() {
        stop();
        ItemWatchSettings current = settings;
        if (!current.enabled()) return;
        long period = current.scanIntervalSeconds() * 20L;
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                ignored -> FoliaTasks.onlinePlayers(plugin, this::scanQuietly), period, period);
        plugin.getLogger().info("Item watch: " + current.rules().size() + " rule(s), scanning"
                + " every " + current.scanIntervalSeconds() + "s.");
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        gains.clear();
        lastAlert.clear();
        vaults.clear();
    }

    public ItemWatchSettings settings() {
        return settings;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean toggleMuted(UUID staff) {
        if (muted.remove(staff)) return false;
        muted.add(staff);
        return true;
    }

    public void forget(UUID player) {
        gains.forget(player);
        lastTriggered.remove(player);
        vaults.remove(player);
        String prefix = player + ":";
        lastAlert.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ---- escaneo de jugadores ------------------------------------------------------------

    /** Escaneo periodico/al entrar: corre en la region del jugador. */
    void scanQuietly(Player player) {
        ItemWatchSettings current = settings;
        if (!current.enabled() || exempt(player, current)) return;
        inspect(player, current, false).thenAccept(report -> evaluate(player.getUniqueId(),
                player.getName(), report, current));
    }

    /** Alguien cerro una mochila de PlayerVaults: lo leido antes ya no vale. */
    void vaultClosed() {
        vaults.clear();
    }

    /**
     * Revisa a un jugador: inventario y ender chest en su region, mochilas en segundo plano.
     * Debe llamarse en la region del jugador.
     */
    CompletableFuture<Report> inspect(Player player, ItemWatchSettings current,
                                      boolean freshVaults) {
        List<WatchSource> sources = new ArrayList<>();
        List<WatchedStack> inventory = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) add(inventory, item);
        add(inventory, player.getItemOnCursor());
        sources.add(WatchSource.of(WatchSource.Kind.INVENTORY, 0, false, inventory,
                current.rules(), current.detectOverstack()));
        if (current.scanEnderChest()) {
            List<WatchedStack> ender = new ArrayList<>();
            for (ItemStack item : player.getEnderChest().getContents()) add(ender, item);
            sources.add(WatchSource.of(WatchSource.Kind.ENDER_CHEST, 0, false, ender,
                    current.rules(), current.detectOverstack()));
        }
        UUID id = player.getUniqueId();
        boolean withVaults = current.scanVaults() && PlayerVaultsHook.isAvailable();
        if (!withVaults) {
            return CompletableFuture.completedFuture(report(current, sources, false));
        }
        CompletableFuture<Report> result = new CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                try {
                    List<WatchSource> all = new ArrayList<>(sources);
                    all.addAll(vaultSources(id, freshVaults, current));
                    result.complete(report(current, all, true));
                } catch (Throwable failure) {
                    result.complete(report(current, sources, false));
                }
            });
        } catch (RuntimeException shuttingDown) {
            result.complete(report(current, sources, false));
        }
        return result;
    }

    private static Report report(ItemWatchSettings current, List<WatchSource> sources,
                                 boolean vaultsIncluded) {
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        List<WatchedStack> overstacked = new ArrayList<>();
        for (WatchRule rule : current.rules()) totals.put(rule.id(), 0L);
        for (WatchSource source : sources) {
            source.totals().forEach((rule, amount) -> totals.merge(rule, amount, Long::sum));
            overstacked.addAll(source.overstacked());
        }
        return new Report(Map.copyOf(totals), List.copyOf(overstacked), vaultsIncluded,
                List.copyOf(sources));
    }

    /**
     * Cada mochila por separado, cacheado {@link #VAULT_REFRESH_MILLIS} en el escaneo periodico
     * (el de /itemwatch scan lee siempre al momento). Corre async.
     */
    private List<WatchSource> vaultSources(UUID owner, boolean fresh, ItemWatchSettings current) {
        long now = System.currentTimeMillis();
        VaultSnapshot cached = fresh ? null : vaults.get(owner);
        if (cached != null && now - cached.time() < VAULT_REFRESH_MILLIS) return cached.sources();
        List<WatchSource> out = new ArrayList<>();
        for (int number : PlayerVaultsHook.vaultNumbers(owner)) {
            boolean live = PlayerVaultsHook.isOpenNow(owner, number);
            ItemStack[] contents = PlayerVaultsHook.contents(owner, number);
            if (contents == null) continue;
            List<WatchedStack> stacks = new ArrayList<>();
            for (ItemStack item : contents) add(stacks, item);
            out.add(WatchSource.of(WatchSource.Kind.VAULT, number, live, stacks,
                    current.rules(), current.detectOverstack()));
        }
        List<WatchSource> frozen = List.copyOf(out);
        vaults.put(owner, new VaultSnapshot(now, frozen));
        return frozen;
    }

    private void evaluate(UUID id, String name, Report report, ItemWatchSettings current) {
        long now = System.currentTimeMillis();
        for (WatchRule rule : current.rules()) {
            long amount = report.totals().getOrDefault(rule.id(), 0L);
            String ruleName = MM.escape(rule.displayName());
            if (rule.max() > 0 && amount >= rule.max()
                    && cooledDown(id + ":total:" + rule.id(), now, current)) {
                alertPlayer(name, "itemwatch.alert-held", current, report, rule.id(),
                        "rule", ruleName, "amount", amount, "max", rule.max());
            }
            if (rule.gain() > 0) {
                long gained = gains.record(id, rule.id(), amount, now, rule.gainWindowMillis());
                if (gained >= rule.gain() && cooledDown(id + ":gain:" + rule.id(), now, current)) {
                    alertPlayer(name, "itemwatch.alert-gained", current, report, rule.id(),
                            "rule", ruleName, "amount", gained,
                            "minutes", rule.gainWindowMillis() / 60_000L);
                }
            }
        }
        if (!report.overstacked().isEmpty() && cooledDown(id + ":overstack", now, current)) {
            WatchedStack worst = report.overstacked().getFirst();
            WatchSource at = sourceOf(report, worst);
            broadcast(current, "itemwatch.alert-illegal-stack",
                    viewer -> withPlayer(name, new Object[]{
                            // El nombre lo puede poner el jugador en un yunque: escapado.
                            "amount", worst.amount(), "item", MM.escape(itemName(worst)),
                            "max", worst.maxStack(), "count", report.overstacked().size(),
                            "where", at == null ? "?" : sourceName(viewer, at)}),
                    viewer -> Lang.get(viewer, "itemwatch.alert-hover-open",
                            "source", at == null ? "?" : sourceName(viewer, at)),
                    openCommand(name, at));
        }
    }

    /**
     * Aviso sobre un jugador con el desglose de DONDE lo tiene ({where}) y un clic que abre
     * el sitio en el que mas hay. Cada staff lo lee en su idioma.
     */
    private void alertPlayer(String name, String key, ItemWatchSettings current, Report report,
                             String ruleId, Object... vars) {
        WatchSource biggest = biggest(report, ruleId);
        broadcast(current, key,
                viewer -> {
                    Object[] base = withPlayer(name, vars);
                    Object[] all = java.util.Arrays.copyOf(base, base.length + 2);
                    all[base.length] = "where";
                    all[base.length + 1] = where(viewer, report, ruleId);
                    return all;
                },
                viewer -> Lang.get(viewer, "itemwatch.alert-hover-open", "source",
                        biggest == null ? Lang.raw(viewer, "itemwatch.src-inventory")
                                : sourceName(viewer, biggest)),
                openCommand(name, biggest));
    }

    /** "mochila #2: 1236 (576 en shulkers), inventario: 64", de mayor a menor, hasta 3. */
    static String where(org.bukkit.command.CommandSender viewer, Report report, String ruleId) {
        List<WatchSource> ranked = new ArrayList<>(report.sources());
        ranked.removeIf(source -> source.total(ruleId) <= 0);
        ranked.sort((a, b) -> Long.compare(b.total(ruleId), a.total(ruleId)));
        List<String> parts = new ArrayList<>();
        for (WatchSource source : ranked.subList(0, Math.min(3, ranked.size()))) {
            String part = Lang.raw(viewer, "itemwatch.where-entry",
                    "source", sourceName(viewer, source), "amount", source.total(ruleId));
            if (source.nested(ruleId) > 0) {
                part += Lang.raw(viewer, "itemwatch.where-nested",
                        "amount", source.nested(ruleId));
            }
            parts.add(part);
        }
        if (ranked.size() > 3) parts.add("…");
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    static String sourceName(org.bukkit.command.CommandSender viewer, WatchSource source) {
        return switch (source.kind()) {
            case INVENTORY -> Lang.raw(viewer, "itemwatch.src-inventory");
            case ENDER_CHEST -> Lang.raw(viewer, "itemwatch.src-ender");
            case VAULT -> Lang.raw(viewer, "itemwatch.src-vault", "number", source.number());
        };
    }

    private static WatchSource biggest(Report report, String ruleId) {
        WatchSource best = null;
        for (WatchSource source : report.sources()) {
            if (source.total(ruleId) > 0 && (best == null
                    || source.total(ruleId) > best.total(ruleId))) {
                best = source;
            }
        }
        return best;
    }

    private static WatchSource sourceOf(Report report, WatchedStack stack) {
        for (WatchSource source : report.sources()) {
            if (source.overstacked().contains(stack)) return source;
        }
        return null;
    }

    /** El comando que abre ese sitio: la mochila concreta, el ender chest o el inventario. */
    static String openCommand(String name, WatchSource source) {
        if (source == null) return "/invsee " + name;
        return switch (source.kind()) {
            case INVENTORY -> "/invsee " + name;
            case ENDER_CHEST -> "/echest " + name;
            case VAULT -> "/vaultsee " + name + " " + source.number();
        };
    }

    // ---- contenedores del mundo ------------------------------------------------------------

    /** Al abrir un cofre/barril/shulker colocado: avisa si guarda una cantidad absurda. */
    void inspectContainer(Player opener, Location location, ItemStack[] contents) {
        ItemWatchSettings current = settings;
        if (!current.enabled() || !current.scanContainers() || location == null
                || location.getWorld() == null || exempt(opener, current)) {
            return;
        }
        List<WatchedStack> stacks = new ArrayList<>();
        for (ItemStack item : contents) add(stacks, item);
        Map<String, Long> totals = ItemCounter.totals(current.rules(), stacks);
        long now = System.currentTimeMillis();
        String where = location.getWorld().getName() + "," + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
        for (WatchRule rule : current.rules()) {
            long amount = totals.getOrDefault(rule.id(), 0L);
            if (rule.max() <= 0 || amount < rule.max()) continue;
            if (!cooledDown("container:" + where + ":" + rule.id(), now, current)) continue;
            alertContainer(opener.getName(), location, current,
                    "rule", MM.escape(rule.displayName()), "amount", amount, "max", rule.max());
        }
        if (current.detectOverstack()) {
            List<WatchedStack> illegal = ItemCounter.overstacked(stacks);
            if (!illegal.isEmpty() && cooledDown("container:" + where + ":overstack", now, current)) {
                WatchedStack worst = illegal.getFirst();
                alertContainer(opener.getName(), location, current,
                        "rule", MM.escape(itemName(worst)) + " ×" + worst.amount(),
                        "amount", worst.amount(), "max", worst.maxStack());
            }
        }
    }

    // ---- avisos -------------------------------------------------------------------------

    private boolean cooledDown(String key, long now, ItemWatchSettings current) {
        Long previous = lastAlert.get(key);
        if (previous != null && now - previous < current.alertCooldownMillis()) return false;
        lastAlert.put(key, now);
        return true;
    }

    private void alertContainer(String opener, Location location, ItemWatchSettings current,
                                Object... vars) {
        List<Object> list = new ArrayList<>(List.of(withPlayer(opener, vars)));
        list.addAll(List.of("world", MM.escape(location.getWorld().getName()),
                "x", location.getBlockX(), "y", location.getBlockY(), "z", location.getBlockZ()));
        Object[] all = list.toArray();
        // Con el MUNDO. "/minecraft:tp @s x y z" solo mueve dentro de la dimension en la que ya
        // esta el staff: un cofre del Nether lo mandaba a esas coordenadas del overworld (o al
        // vacio) y habia que adivinar la dimension. tppos teletransporta entre mundos y deja al
        // staff encima del bloque.
        String tp = "/chatalyst tppos " + location.getWorld().getName() + " " + location.getBlockX()
                + " " + location.getBlockY() + " " + location.getBlockZ();
        broadcast(current, "itemwatch.alert-container", viewer -> all,
                viewer -> Lang.get(viewer, "itemwatch.alert-hover-container"), tp);
    }

    private static Object[] withPlayer(String name, Object[] vars) {
        Object[] all = new Object[vars.length + 2];
        all[0] = "player";
        all[1] = MM.escape(name);
        System.arraycopy(vars, 0, all, 2, vars.length);
        return all;
    }

    /**
     * Al staff con permiso (cada uno en su idioma, con hover y clic), a la consola y a Discord.
     * {@code varsFor} se evalua por espectador porque {where} se escribe en su idioma.
     */
    private void broadcast(ItemWatchSettings current, String key,
                           java.util.function.Function<org.bukkit.command.CommandSender, Object[]> varsFor,
                           java.util.function.Function<org.bukkit.command.CommandSender, Component> hover,
                           String clickCommand) {
        FoliaTasks.onlinePlayers(plugin,
                viewer -> viewer.hasPermission(current.alertPermission())
                        && !muted.contains(viewer.getUniqueId()),
                viewer -> viewer.sendMessage(Lang.get(viewer, key, varsFor.apply(viewer))
                        .hoverEvent(HoverEvent.showText(hover.apply(viewer)))
                        .clickEvent(ClickEvent.runCommand(clickCommand))));
        var console = plugin.getServer().getConsoleSender();
        Object[] consoleVars = varsFor.apply(console);
        FoliaTasks.global(plugin, () -> console.sendMessage(Lang.get(console, key, consoleVars)));
        if (current.notifyDiscord() && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Item watch: "
                    + MM.strip(Lang.raw(console, key, consoleVars)));
        }
    }

    // ---- utilidades -----------------------------------------------------------------------

    private static boolean exempt(Player player, ItemWatchSettings current) {
        if (current.ignoreCreative() && (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR)) {
            return true;
        }
        return player.hasPermission(current.bypassPermission());
    }

    private static String itemName(WatchedStack stack) {
        return stack.name().isEmpty()
                ? stack.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                : stack.name();
    }

    static void add(List<WatchedStack> out, ItemStack item) {
        WatchedStack converted = convert(item, 0);
        if (converted != null) out.add(converted);
    }

    private static WatchedStack convert(ItemStack item, int depth) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return null;
        String name = "";
        List<String> lore = List.of();
        List<WatchedStack> contents = new ArrayList<>();
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                name = PLAIN.serialize(meta.displayName());
            }
            List<Component> rawLore = meta.lore();
            if (rawLore != null) lore = rawLore.stream().map(PLAIN::serialize).toList();
            if (depth < ItemCounter.MAX_DEPTH) {
                if (meta instanceof BlockStateMeta states && states.hasBlockState()
                        && states.getBlockState() instanceof Container container) {
                    for (ItemStack inner : container.getSnapshotInventory().getContents()) {
                        WatchedStack child = convert(inner, depth + 1);
                        if (child != null) contents.add(child);
                    }
                } else if (meta instanceof BundleMeta bundle) {
                    for (ItemStack inner : bundle.getItems()) {
                        WatchedStack child = convert(inner, depth + 1);
                        if (child != null) contents.add(child);
                    }
                }
            }
        }
        return new WatchedStack(item.getType(), item.getAmount(), item.getMaxStackSize(), name,
                lore, contents);
    }

    /** Para /itemwatch scan: corre en la region del objetivo. */
    CompletableFuture<Report> scanNow(Player target) {
        // A mano se lee todo en el momento: es lo que el staff espera ver.
        return inspect(target, settings, true);
    }

    /** Ultima revision disparada por cerrar algo, por jugador (para no repetir en rafaga). */
    private final Map<UUID, Long> lastTriggered = new ConcurrentHashMap<>();
    private static final long TRIGGER_GAP_MILLIS = 5_000L;

    /**
     * Revision inmediata al cerrar una mochila, el ender chest o un contenedor: es justo cuando
     * cambia lo que tiene guardado, y esperar al siguiente ciclo de 60 s dejaba el aviso tarde.
     * Como mucho una cada {@link #TRIGGER_GAP_MILLIS} por jugador.
     */
    void triggerScan(Player player, boolean freshVaults) {
        ItemWatchSettings current = settings;
        if (!current.enabled() || exempt(player, current)) return;
        long now = System.currentTimeMillis();
        Long previous = lastTriggered.get(player.getUniqueId());
        if (previous != null && now - previous < TRIGGER_GAP_MILLIS) return;
        lastTriggered.put(player.getUniqueId(), now);
        player.getScheduler().run(plugin, ignored -> inspect(player, current, freshVaults)
                .thenAccept(report -> evaluate(player.getUniqueId(), player.getName(), report,
                        current)), null);
    }

    void scheduleScan(Player player, long delayTicks) {
        ItemWatchSettings current = settings;
        if (!current.enabled()) return;
        player.getScheduler().runDelayed(plugin, ignored -> scanQuietly(player), null,
                Math.max(1L, delayTicks));
    }

}
