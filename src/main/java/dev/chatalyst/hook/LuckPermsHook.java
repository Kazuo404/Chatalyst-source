package dev.chatalyst.hook;

import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Puerta de entrada segura a LuckPerms. Si no esta instalado, todos los metodos devuelven
 * cadena vacia sin romper nada (patron anti-NoClassDefFound). Provee rango/prefijo/sufijo
 * de forma nativa, sin necesitar PlaceholderAPI.
 */
public final class LuckPermsHook {

    private static Boolean available = null;

    private LuckPermsHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
            if (available) {
                try {
                    Class.forName("net.luckperms.api.LuckPermsProvider");
                } catch (Throwable t) {
                    available = false;
                }
            }
        }
        return available;
    }

    public static void reset() {
        available = null;
    }

    public static String prefix(UUID uuid) {
        return isAvailable() ? safe(() -> LuckPermsBridge.prefix(uuid)) : "";
    }

    public static String suffix(UUID uuid) {
        return isAvailable() ? safe(() -> LuckPermsBridge.suffix(uuid)) : "";
    }

    /**
     * {@code [prefijo, sufijo, grupo, color]} en una sola consulta a LuckPerms. Lo usa el
     * camino caliente del chat; el resto de sitios puede seguir pidiendo lo que necesite.
     */
    public static String[] rankData(UUID uuid) {
        if (!isAvailable()) return new String[] {"", "", "", ""};
        try {
            return LuckPermsBridge.rankData(uuid);
        } catch (Throwable failure) {
            return new String[] {"", "", "", ""};
        }
    }

    public static String group(UUID uuid) {
        return isAvailable() ? safe(() -> LuckPermsBridge.group(uuid)) : "";
    }

    /** Prefijo de un GRUPO por nombre (ej. el tag del grupo por defecto), o "". */
    public static String groupPrefix(String group) {
        return isAvailable() && group != null && !group.isBlank()
                ? safe(() -> LuckPermsBridge.groupPrefix(group.trim())) : "";
    }

    /** Grupos que Chatalyst le tiene puestos a este jugador -&gt; caducidad (0 = permanente). */
    public static java.util.concurrent.CompletableFuture<java.util.Map<String, Long>> ourGroups(
            UUID uuid) {
        if (!isAvailable() || uuid == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.of());
        }
        try {
            return LuckPermsRankBridge.ourGroups(uuid);
        } catch (Throwable unavailable) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.of());
        }
    }

    /** Nombre para mostrar que LuckPerms guarda para el grupo, o "". */
    public static String groupDisplayName(String group) {
        return isAvailable() && group != null && !group.isBlank()
                ? safe(() -> LuckPermsBridge.groupDisplayName(group.trim())) : "";
    }

    /** Sufijo de un GRUPO por nombre, o "". */
    public static String groupSuffix(String group) {
        return isAvailable() && group != null && !group.isBlank()
                ? safe(() -> LuckPermsBridge.groupSuffix(group.trim())) : "";
    }

    public static String meta(UUID uuid, String key) {
        return isAvailable() ? safe(() -> LuckPermsBridge.metaValue(uuid, key)) : "";
    }

    /** Consulta tambien usuarios offline sin bloquear el hilo del servidor. */
    public static java.util.concurrent.CompletableFuture<Boolean> hasPermissionAsync(
            UUID uuid, String permission) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            return LuckPermsBridge.hasPermission(uuid, permission);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /** Loaded LuckPerms groups that explicitly inherit the configured mentionable node. */
    public static java.util.Set<String> groupsWithPermission(String permission) {
        if (!isAvailable() || permission == null || permission.isBlank()) return java.util.Set.of();
        try {
            return LuckPermsBridge.groupsWithPermission(permission);
        } catch (Throwable ignored) {
            return java.util.Set.of();
        }
    }

    /** Todos los grupos cargados en LuckPerms; vacio si la integracion no esta disponible. */
    public static java.util.Set<String> loadedGroups() {
        if (!isAvailable()) return java.util.Set.of();
        try {
            return java.util.Collections.unmodifiableSet(LuckPermsBridge.loadedGroups());
        } catch (Throwable ignored) {
            return java.util.Set.of();
        }
    }

    /** Grupo primario de un usuario online u offline, sin bloquear el hilo del servidor. */
    public static java.util.concurrent.CompletableFuture<String> primaryGroupAsync(UUID uuid) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture("");
        try {
            return LuckPermsRankBridge.primaryGroup(uuid);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Añade un grupo directo. {@code expiresAtMillis <= 0} significa permanente; para rangos
     * Tebex temporales se usa un instante absoluto, de modo que reintentar la misma compra no
     * extiende accidentalmente su duración.
     */
    public static java.util.concurrent.CompletableFuture<Boolean> grantGroupAsync(
            UUID uuid, String group, long expiresAtMillis) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            return LuckPermsRankBridge.grantGroup(uuid, normalizeGroup(group), expiresAtMillis);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Concede un permiso suelto, opcionalmente temporal. {@code expiresAtMillis <= 0} es
     * permanente, igual que en {@link #grantGroupAsync}: un solo modo de decir "para siempre"
     * en todo el hook.
     */
    public static java.util.concurrent.CompletableFuture<Boolean> grantPermissionAsync(
            UUID uuid, String permission, long expiresAtMillis) {
        if (!isAvailable() || permission == null || permission.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        try {
            return LuckPermsRankBridge.grantPermission(
                    uuid, permission.trim().toLowerCase(java.util.Locale.ROOT), expiresAtMillis);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /** Grupos propios y vigentes del jugador; vacio sin LuckPerms. */
    public static java.util.concurrent.CompletableFuture<java.util.Set<String>> heldGroupsAsync(
            UUID uuid) {
        if (!isAvailable()) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Set.of());
        }
        try {
            return LuckPermsRankBridge.heldGroups(uuid);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /** Da el grupo para siempre si no lo tiene ya (ni temporal). */
    public static java.util.concurrent.CompletableFuture<Boolean> addGroupIfAbsentAsync(
            UUID uuid, String group) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            return LuckPermsRankBridge.addGroupIfAbsent(uuid, normalizeGroup(group));
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    public static java.util.concurrent.CompletableFuture<Boolean> removeGroupAsync(
            UUID uuid, String group) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            return LuckPermsRankBridge.removeGroup(uuid, normalizeGroup(group));
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    public static java.util.concurrent.CompletableFuture<Boolean> replaceMappedGroupAsync(
            UUID uuid, java.util.Set<String> mappedGroups, String desiredGroup) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            java.util.Set<String> normalized = mappedGroups.stream()
                    .map(LuckPermsHook::normalizeGroup)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return LuckPermsRankBridge.replaceMappedGroup(
                    uuid, normalized, normalizeGroup(desiredGroup));
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Repairs the groups Chatalyst delivered to this player so they match {@code desired}.
     * See {@link LuckPermsRankBridge#reconcileGroups}: everything outside {@code managed} is
     * left alone, which keeps manual administrator grants safe from the reconciler, and a
     * permanent node is only touched for groups in {@code permanentlyManaged}.
     */
    public static java.util.concurrent.CompletableFuture<Boolean> reconcileGroupsAsync(
            UUID uuid, java.util.Map<String, Long> desired, java.util.Set<String> managed,
            java.util.Set<String> permanentlyManaged) {
        if (!isAvailable()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        try {
            java.util.Map<String, Long> normalizedDesired = new java.util.LinkedHashMap<>();
            desired.forEach((group, expiry) -> {
                String normalized = normalizeGroup(group);
                if (normalized.isBlank()) return;
                // Two entitlements for one group: the most generous expiry wins, and a
                // permanent one (0) always beats a temporary one.
                normalizedDesired.merge(normalized, expiry,
                        (a, b) -> a == 0L || b == 0L ? 0L : Math.max(a, b));
            });
            java.util.Set<String> normalizedManaged = managed.stream()
                    .map(LuckPermsHook::normalizeGroup)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            java.util.Set<String> normalizedPermanent = permanentlyManaged.stream()
                    .map(LuckPermsHook::normalizeGroup)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return LuckPermsRankBridge.reconcileGroups(
                    uuid, normalizedDesired, normalizedManaged, normalizedPermanent);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    public static boolean groupExists(String group) {
        if (!isAvailable()) return false;
        try {
            return LuckPermsRankBridge.groupExists(normalizeGroup(group));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Suscripción aislada a eventos de LuckPerms. El tipo devuelto es JDK-only, por lo que
     * Chatalyst sigue cargando normalmente cuando LuckPerms no está instalado.
     */
    public static AutoCloseable subscribeUserChanges(Object owner,
                                                      java.util.function.Consumer<UUID> listener) {
        if (!isAvailable()) return () -> {
        };
        try {
            return LuckPermsRankBridge.subscribeUserChanges(owner, listener);
        } catch (Throwable failure) {
            return () -> {
            };
        }
    }

    /** Color del rango extraido del prefijo (ej. "<#FF4D6D>"), o "" si no hay. */
    public static String rankColor(UUID uuid) {
        return isAvailable() ? safe(() -> LuckPermsBridge.rankColor(uuid)) : "";
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String normalizeGroup(String group) {
        return group == null ? "" : group.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
