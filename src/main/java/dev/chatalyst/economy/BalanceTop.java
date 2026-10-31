package dev.chatalyst.economy;

import dev.chatalyst.hook.VaultHook;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Ranking de jugadores con mas dinero (baltop), via Vault. El calculo recorre a todos los
 * jugadores conocidos y consulta su balance, algo costoso, asi que se hace de forma
 * ASINCRONA y periodica, guardando el resultado en cache; el comando y la GUI leen la cache.
 */
public final class BalanceTop {

    /** Una fila del ranking. */
    public record Entry(String name, UUID uuid, double balance) {
    }

    private final JavaPlugin plugin;
    private volatile List<Entry> cached = List.of();
    private volatile long lastUpdate;
    private volatile boolean enabled;
    private volatile boolean active; // se activo de verdad (hay economia compatible)
    private volatile int size;
    private volatile int intervalSeconds;
    private ScheduledTask task;
    // Coalescion de refrescos bajo demanda: varios /baltop a la vez no disparan varios
    // recorridos completos de offline+Vault (caro). El ciclo periodico no lo toca.
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong refreshingRun =
            new java.util.concurrent.atomic.AtomicLong();
    // Piso entre refrescos bajo demanda: si el ranking se recalculo hace nada, no se rehace.
    private static final long MIN_ON_DEMAND_REFRESH_MS = 3_000L;

    public BalanceTop(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Balance_Top.Enabled", true);
        size = Math.max(1, Math.min(54, plugin.getConfig().getInt("Balance_Top.Size", 45)));
        intervalSeconds = Math.max(30, plugin.getConfig().getInt("Balance_Top.Update_Interval", 300));
    }

    /**
     * Arranca solo si hay un plugin de economia COMPATIBLE (via Vault). Detecta cual es y
     * comprueba que deja consultar saldos offline; si no, se queda apagado con un aviso claro
     * en vez de mostrar un ranking vacio o roto.
     */
    public void start() {
        stop();
        active = false;
        if (!enabled) return;
        if (!VaultHook.hasEconomy()) {
            plugin.getLogger().info("Balance top: no Vault economy plugin found; /baltop is off. "
                    + "Install Vault + an economy plugin (EssentialsX, CMI, XConomy...) to enable it.");
            return;
        }
        // Comprobamos con un jugador conocido que el proveedor responde offline.
        var sample = plugin.getServer().getOfflinePlayers();
        boolean offlineOk = sample.length == 0
                || VaultHook.economySupportsOffline(sample[0]);
        if (!offlineOk) {
            plugin.getLogger().warning("Balance top: economy provider '" + VaultHook.economyName()
                    + "' doesn't support offline balances; /baltop stays off to avoid a broken ranking.");
            return;
        }
        active = true;
        long run = generation.incrementAndGet();
        plugin.getLogger().info("Balance top: using " + VaultHook.economyName() + " (via Vault).");
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                t -> refreshIfIdle(run), 5, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        generation.incrementAndGet();
        active = false;
        refreshingRun.set(0L);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isReady() {
        return active;
    }

    public List<Entry> top() {
        return cached;
    }

    public long lastUpdate() {
        return lastUpdate;
    }

    /**
     * Refresco bajo demanda (al abrir /baltop) para que un cambio de saldo reciente se refleje
     * sin esperar al ciclo periodico de {@code Update_Interval}. Corre ASINCRONO (el recorrido
     * de todos los offline + Vault no puede tocar el hilo principal) y esta throttled/coalescido:
     * si el ranking ya es reciente o hay un refresco en curso, no recomputa. {@code onDone} se
     * invoca al terminar SIEMPRE (haya recomputado o no) para que el llamador repinte la vista;
     * puede ejecutarse en un hilo async, asi que el callback debe despachar a su region.
     */
    public void requestRefresh(Runnable onDone) {
        if (!active) {
            if (onDone != null) onDone.run();
            return;
        }
        long age = System.currentTimeMillis() - lastUpdate;
        // Cache ya fresco, o ya hay un refresco en vuelo: el llamador repinta con lo que hay.
        long run = generation.get();
        if (age < MIN_ON_DEMAND_REFRESH_MS || !refreshingRun.compareAndSet(0L, run)) {
            if (onDone != null) onDone.run();
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            try {
                refresh(run);
            } finally {
                refreshingRun.compareAndSet(run, 0L);
            }
            if (onDone != null) onDone.run();
        });
    }

    private void refreshIfIdle(long run) {
        if (!active || generation.get() != run || !refreshingRun.compareAndSet(0L, run)) return;
        try {
            refresh(run);
        } finally {
            refreshingRun.compareAndSet(run, 0L);
        }
    }

    /** Recalcula el ranking (corre en el hilo async del scheduler). */
    private void refresh(long run) {
        List<Entry> out = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() == null) continue;
            try {
                double bal = VaultHook.balance(p);
                out.add(new Entry(p.getName(), p.getUniqueId(), bal));
            } catch (Throwable ignore) {
                // un economy plugin que no soporte consulta offline: saltamos ese jugador
            }
        }
        out.sort(Comparator.comparingDouble(Entry::balance).reversed());
        if (out.size() > size) out = new ArrayList<>(out.subList(0, size));
        // Un refresh iniciado antes de reload/disable no puede publicar resultados viejos
        // ni volver a dejar listo un modulo detenido.
        if (!active || generation.get() != run) return;
        cached = List.copyOf(out);
        lastUpdate = System.currentTimeMillis();
    }
}
