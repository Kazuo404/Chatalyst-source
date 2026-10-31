package dev.chatalyst.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fallback nativo para servidores sin PacketEvents. Reutiliza un conjunto acotado de
 * advancements ocultos y nunca elimina entradas del registro global durante el juego.
 */
final class BukkitToastBridge {

    private static final int MAX_CACHED_PRESENTATIONS = 2_048;
    private static final long CLEANUP_DELAY_TICKS = 5L;
    private static final Set<NamespacedKey> CACHED_PRESENTATIONS =
            ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean LOAD_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CACHE_LIMIT_LOGGED = new AtomicBoolean();

    private BukkitToastBridge() {
    }

    static void show(JavaPlugin plugin, List<Player> targets,
                     String title, String description, Material icon,
                     String frame, boolean styled) {
        showCached(plugin, targets, title, description, icon, frame, styled);
    }

    /**
     * Cada presentacion se registra una sola vez, permanece oculta y se deja incompleta
     * despues de mostrarse. Asi una previsualizacion puede repetirse sin guardar progreso.
     */
    private static void showCached(JavaPlugin plugin, List<Player> targets,
                                   String title, String description, Material icon,
                                   String frame, boolean styled) {
        String modernJson = ToastUtil.json(
                title, description, icon, frame, styled, true);
        NamespacedKey key = new NamespacedKey(plugin,
                "toast/cached/" + ToastUtil.cachedIdentity(modernJson));
        FoliaTasks.global(plugin, () -> {
            Advancement advancement = resolveCached(
                    plugin, key, title, description, icon, frame, styled);
            if (advancement == null) return;
            for (Player player : targets) {
                showCachedTo(plugin, player, advancement);
            }
        });
    }

    private static Advancement resolveCached(JavaPlugin plugin, NamespacedKey key,
                                              String title, String description,
                                              Material icon, String frame,
                                              boolean styled) {
        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null && !CACHED_PRESENTATIONS.contains(key)
                && CACHED_PRESENTATIONS.size() >= MAX_CACHED_PRESENTATIONS) {
            logCacheLimit(plugin);
            return null;
        }
        if (advancement == null) {
            advancement = load(key, title, description, icon, frame, styled, true);
            if (advancement == null) {
                advancement = load(key, title, description, icon, frame, styled, false);
            }
        }
        if (advancement == null) {
            logLoadFailure(plugin);
            return null;
        }
        CACHED_PRESENTATIONS.add(key);
        return advancement;
    }

    private static void showCachedTo(JavaPlugin plugin, Player player,
                                     Advancement advancement) {
        FoliaTasks.entity(plugin, player, current -> {
            try {
                revoke(current, advancement);
                award(current, advancement);
                current.getScheduler().runDelayed(plugin,
                        task -> revoke(current, advancement), null,
                        CLEANUP_DELAY_TICKS);
            } catch (Throwable ignored) {
                // El chat y el sonido configurados siguen siendo el fallback visible.
            }
        });
    }

    private static void award(Player player, Advancement advancement) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        for (String criterion : List.copyOf(progress.getRemainingCriteria())) {
            progress.awardCriteria(criterion);
        }
    }

    private static void revoke(Player player, Advancement advancement) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
            progress.revokeCriteria(criterion);
        }
    }

    @SuppressWarnings("deprecation")
    private static Advancement load(NamespacedKey key, String title, String description,
                                    Material icon, String frame, boolean styled,
                                    boolean modernIcon) {
        try {
            Object unsafe = Bukkit.getUnsafe();
            Method loader = unsafe.getClass().getMethod(
                    "loadAdvancement", NamespacedKey.class, String.class);
            Object result = loader.invoke(unsafe, key,
                    ToastUtil.json(title, description, icon, frame, styled, modernIcon));
            return result instanceof Advancement advancement ? advancement : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logLoadFailure(JavaPlugin plugin) {
        if (LOAD_FAILURE_LOGGED.compareAndSet(false, true)) {
            plugin.getLogger().warning("Minecraft rejected a dynamic toast advancement; "
                    + "chat and sound notifications remain available.");
        }
    }

    private static void logCacheLimit(JavaPlugin plugin) {
        if (CACHE_LIMIT_LOGGED.compareAndSet(false, true)) {
            plugin.getLogger().warning("Dynamic toast fallback reached its safe limit of "
                    + MAX_CACHED_PRESENTATIONS + "; install/enable PacketEvents for unlimited "
                    + "fully dynamic achievement notifications.");
        }
    }
}
