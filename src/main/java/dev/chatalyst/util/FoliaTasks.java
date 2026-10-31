package dev.chatalyst.util;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small scheduler facade for code paths that may run on async, global or entity threads. */
public final class FoliaTasks {

    private FoliaTasks() {
    }

    /** Deteccion recomendada por Paper; evita tocar regiones durante el shutdown de Folia. */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static void global(JavaPlugin plugin, Runnable action) {
        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> action.run());
        } catch (RuntimeException rejected) {
            // Callback async que llego durante disable/reload: ya no hay un hilo global
            // valido donde ejecutar con seguridad. El estado se descarta con el plugin.
        }
    }

    public static void globalLater(JavaPlugin plugin, Runnable action, long delayTicks) {
        try {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin, task -> action.run(), Math.max(1L, delayTicks));
        } catch (RuntimeException rejected) {
            // Plugin deshabilitado entre la comprobacion del llamador y el enqueue.
        }
    }

    public static void entity(JavaPlugin plugin, Player player, Consumer<Player> action) {
        entity(plugin, player, action, null);
    }

    /**
     * Ejecuta sobre la region DUEÑA DE UNA UBICACION (no de una entidad). Necesario para
     * leer o tocar bloques lejanos en Folia: el chunk de un cofre a 5000 bloques lo maneja
     * otro hilo, y accederlo desde el del jugador revienta.
     */
    public static void region(JavaPlugin plugin, org.bukkit.Location location,
                              Runnable action) {
        try {
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> action.run());
        } catch (RuntimeException rejected) {
            // No tocar la region desde el hilo equivocado como fallback.
        }
    }

    /**
     * Ejecuta sobre la region propietaria y garantiza un callback si la entidad se retira o
     * el scheduler ya la habia retirado antes de aceptar la tarea. EntityScheduler devuelve
     * null en ese ultimo caso sin invocar por si mismo el callback retired.
     */
    public static boolean entity(JavaPlugin plugin, Player player, Consumer<Player> action,
                                 Runnable retired) {
        AtomicBoolean completed = new AtomicBoolean(false);
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled;
        try {
            scheduled = player.getScheduler().run(plugin, task -> {
                if (!completed.compareAndSet(false, true)) return;
                if (player.isOnline()) action.accept(player);
                else if (retired != null) retired.run();
            }, () -> {
                if (retired != null && completed.compareAndSet(false, true)) retired.run();
            });
        } catch (RuntimeException rejected) {
            if (retired != null && completed.compareAndSet(false, true)) retired.run();
            return false;
        }
        if (scheduled == null && retired != null && completed.compareAndSet(false, true)) {
            retired.run();
        }
        return scheduled != null;
    }

    public static void onlinePlayers(JavaPlugin plugin, Consumer<Player> action) {
        onlinePlayers(plugin, player -> true, action);
    }

    public static void onlinePlayers(JavaPlugin plugin, Predicate<Player> filter, Consumer<Player> action) {
        global(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                entity(plugin, player, p -> {
                    if (filter.test(p)) action.accept(p);
                });
            }
        });
    }

    /** Igual que onlinePlayers, con callback global cuando todos ejecutaron o se retiraron. */
    public static void onlinePlayers(JavaPlugin plugin, Predicate<Player> filter,
                                     Consumer<Player> action, Runnable completed) {
        global(plugin, () -> {
            java.util.List<Player> players = java.util.List.copyOf(
                    plugin.getServer().getOnlinePlayers());
            if (players.isEmpty()) {
                completed.run();
                return;
            }
            java.util.concurrent.atomic.AtomicInteger remaining =
                    new java.util.concurrent.atomic.AtomicInteger(players.size());
            Runnable oneDone = () -> {
                if (remaining.decrementAndGet() == 0) global(plugin, completed);
            };
            for (Player player : players) {
                entity(plugin, player, current -> {
                    try {
                        if (filter.test(current)) action.accept(current);
                    } finally {
                        oneDone.run();
                    }
                }, oneDone);
            }
        });
    }

    public static void broadcast(JavaPlugin plugin, Component message) {
        onlinePlayers(plugin, player -> player.sendMessage(message));
        global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(message));
    }
}
