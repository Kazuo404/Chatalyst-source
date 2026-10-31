package dev.chatalyst.afk;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado AFK por jugador. Detecta la inactividad (sin movimiento, chat ni comandos durante
 * un tiempo) y marca al jugador como AFK, avisandoselo SOLO a el (no publico). El estado se
 * expone como placeholder {afk_tag} para la tab list. Al volver a actuar deja de estar AFK.
 */
public final class AfkManager {

    private final ChatalystPlugin plugin;
    private final java.util.Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile long timeoutMs;
    private volatile boolean notify;
    private volatile boolean ignoreVanished;
    private volatile String tabSuffix;
    private ScheduledTask task;

    public AfkManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Afk.Enabled", true);
        timeoutMs = Math.max(10, plugin.getConfig().getInt("Afk.Timeout_Seconds", 300)) * 1000L;
        notify = plugin.getConfig().getBoolean("Afk.Notify", true);
        ignoreVanished = plugin.getConfig().getBoolean("Afk.Ignore_Vanished", true);
        tabSuffix = plugin.getConfig().getString("Afk.Tab_Suffix", " <#C0B3D9>[AFK]");
    }

    public void start() {
        stop();
        if (!enabled) {
            // Al apagar AFK con gente ya marcada, activity() deja de desmarcarla (sale temprano
            // con la feature apagada), asi que el [AFK] se quedaba pegado en su tab hasta que
            // reconectaran. Se limpia el estado y se refresca la tab de los afectados.
            clearAllAndRefresh();
            return;
        }
        long startedAt = System.currentTimeMillis();
        // Al activar AFK por reload, los ya conectados no reciben PlayerJoinEvent. Sin esta
        // semilla getOrDefault(id, now) usaria un "ahora" nuevo en cada tick y jamas quedarian
        // AFK hasta realizar alguna accion.
        plugin.getServer().getOnlinePlayers().forEach(
                player -> lastActivity.putIfAbsent(player.getUniqueId(), startedAt));
        // Cada 2s marca AFK a quien lleve el timeout sin actividad (y no se marco a mano).
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            long now = System.currentTimeMillis();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                FoliaTasks.entity(plugin, p, player -> {
                    UUID id = player.getUniqueId();
                    if (afk.contains(id)) return;
                    if (exempt(player)) {
                        // El reloj se reinicia mientras dure la guardia: al salir del vanish
                        // se empieza a contar de cero, no con el rato que llevaba mirando.
                        lastActivity.put(id, now);
                        return;
                    }
                    long last = lastActivity.getOrDefault(id, now);
                    if (now - last >= timeoutMs) setAfk(player);
                });
            }
        }, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Limpia el estado AFK y repinta la tab de quien estuviera marcado (al apagar la feature). */
    private void clearAllAndRefresh() {
        if (afk.isEmpty()) {
            lastActivity.clear();
            return;
        }
        java.util.List<UUID> wasAfk = new java.util.ArrayList<>(afk);
        afk.clear();
        lastActivity.clear();
        for (UUID id : wasAfk) {
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) FoliaTasks.entity(plugin, online, this::refreshTab);
        }
    }

    /**
     * Vigilar es estar quieto.
     *
     * <p>Un staff en vanish pasa la guardia parado mirando a alguien, y ademas casi todo lo
     * que hace mientras tanto llega CANCELADO (el modo observador anula el clic sobre un cofre
     * o un bloque), asi que los eventos que sacan del AFK ni siquiera se cuentan. Resultado:
     * quedaba AFK justo mientras trabajaba, y el {@code [AFK]} aparecia en el tab de quien
     * pudiera verlo. Estar oculto es, por definicion, no estar ausente.
     *
     * <p>La marca manual con /afk sigue mandando: esto solo evita que salte SOLA.
     */
    private boolean exempt(Player player) {
        return ignoreVanished && dev.chatalyst.util.Vanish.isVanished(player);
    }

    /** El jugador hizo algo real (mover, chat, comando, romper/poner bloque...): sale de AFK. */
    public void activity(Player p) {
        if (!enabled) return;
        UUID id = p.getUniqueId();
        lastActivity.put(id, System.currentTimeMillis());
        if (afk.contains(id)) {
            unsetAfk(p);
        }
    }

    /** Marca AFK (por inactividad o a mano con /afk). Avisa solo al jugador. */
    public void setAfk(Player p) {
        if (!afk.add(p.getUniqueId())) return;
        notifyAndRefresh(p, "afk.now");
    }

    public void unsetAfk(Player p) {
        UUID id = p.getUniqueId();
        if (!afk.remove(id)) return;
        lastActivity.put(id, System.currentTimeMillis());
        notifyAndRefresh(p, "afk.back");
    }

    // El aviso y el refresco de la tab tocan la tab list y placeholders (PAPI), que NO son
    // seguros fuera del hilo principal — y activity() puede venir del chat asincrono. Por eso
    // los ejecutamos siempre en el hilo principal.
    private void notifyAndRefresh(Player p, String key) {
        FoliaTasks.entity(plugin, p, player -> {
            if (notify) player.sendMessage(Lang.get(player, key));
            refreshTab(player);
        });
    }

    /** /afk: entra o sale de AFK manualmente. */
    public void toggle(Player p) {
        if (afk.contains(p.getUniqueId())) {
            unsetAfk(p);
        } else {
            setAfk(p);
        }
    }

    public boolean isAfk(UUID id) {
        return afk.contains(id);
    }

    /** Sufijo para la tab list ("" si no esta AFK). */
    public String tabTag(UUID id) {
        return afk.contains(id) ? tabSuffix : "";
    }

    public void remove(Player p) {
        UUID id = p.getUniqueId();
        afk.remove(id);
        lastActivity.remove(id);
    }

    // Refresco inmediato del nombre en la tab para que el [AFK] aparezca al instante.
    private void refreshTab(Player p) {
        try {
            plugin.getTabListManager().update(p);
        } catch (Throwable ignored) {
            // el tab list propio puede estar apagado (o lo maneja TAB): no pasa nada
        }
        // El cartel sobre la cabeza tambien lleva {afk_tag}: sin esto esperaba a su propio
        // ciclo, y el [AFK] aparecia (o seguia puesto) un par de segundos tarde.
        try {
            if (plugin.getFancyNametags() != null) plugin.getFancyNametags().refresh(p);
        } catch (Throwable ignored) {
            // nametag fancy apagado o a medio recargar: lo pinta su siguiente ciclo
        }
    }
}
