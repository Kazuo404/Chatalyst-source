package dev.chatalyst.staff.itemaudit;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Quien puede QUEDAR en cada gamemode: el "configurador" del dueño son los permisos
 * {@code chatalyst.gamemode.<modo>}, que se reparten por rango en LuckPerms.
 *
 * @param restrict     apagado = cualquiera puede estar en cualquier modo (solo se registra)
 * @param defaultMode  a donde se devuelve a quien esta en un modo prohibido; siempre permitido,
 *                     o un jugador sin ningun permiso no tendria ningun modo valido
 * @param exemptWorlds mundos donde no se restringe (un mundo de construccion en creativo)
 */
record GamemodeRules(boolean restrict, String defaultMode, Set<String> exemptWorlds) {

    static final String PERMISSION_PREFIX = "chatalyst.gamemode.";

    GamemodeRules {
        defaultMode = normalize(defaultMode);
        if (defaultMode.isEmpty()) defaultMode = "SURVIVAL";
        exemptWorlds = exemptWorlds == null ? Set.of()
                : exemptWorlds.stream().map(w -> w.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static GamemodeRules off() {
        return new GamemodeRules(false, "SURVIVAL", Set.of());
    }

    static String permission(String mode) {
        return PERMISSION_PREFIX + normalize(mode).toLowerCase(Locale.ROOT);
    }

    boolean allowed(String mode, String world, Predicate<String> hasPermission) {
        if (!restrict) return true;
        String wanted = normalize(mode);
        if (wanted.equals(defaultMode)) return true;
        if (world != null && exemptWorlds.contains(world.toLowerCase(Locale.ROOT))) return true;
        return hasPermission.test(permission(wanted));
    }

    static String normalize(String mode) {
        return mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
    }
}
