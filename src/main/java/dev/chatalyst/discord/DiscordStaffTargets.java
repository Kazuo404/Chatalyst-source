package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * La jerarquia de bypass para los comandos de moderacion que llegan desde Discord.
 *
 * <p>Esos comandos se despachan como consola, y la consola se salta toda proteccion: un
 * moderador de Discord podia banear a un admin al que dentro del juego no puede ni expulsar.
 * Quien escribe en Discord no tiene un rango del juego que consultar, asi que se le trata como
 * el caso mas prudente: staff NO operador. Es la misma regla que aplica
 * {@code StaffAccess.canAffectAsync} a un jugador sin OP — el objetivo OP (con
 * {@code Exempt_Ops}) o con el nodo bypass de la accion queda fuera de su alcance.
 */
final class DiscordStaffTargets {

    /** El nodo que protege del verbo, el mismo que mira el comando del juego. */
    private static final Map<String, String> BYPASS = Map.ofEntries(
            Map.entry("kick", "chatalyst.bypass.kick"),
            Map.entry("expulsar", "chatalyst.bypass.kick"),
            Map.entry("warn", "chatalyst.bypass.warn"),
            Map.entry("amonestar", "chatalyst.bypass.warn"),
            Map.entry("advertir", "chatalyst.bypass.warn"),
            Map.entry("ban", "chatalyst.bypass.ban"),
            Map.entry("banear", "chatalyst.bypass.ban"),
            Map.entry("tempban", "chatalyst.bypass.ban"),
            Map.entry("bantemporal", "chatalyst.bypass.ban"),
            Map.entry("banip", "chatalyst.bypass.ban"),
            Map.entry("ipban", "chatalyst.bypass.ban"),
            Map.entry("banearip", "chatalyst.bypass.ban"),
            Map.entry("freeze", "chatalyst.bypass.freeze"),
            Map.entry("congelar", "chatalyst.bypass.freeze"),
            Map.entry("mute", "chatalyst.bypass.mute"),
            Map.entry("mutear", "chatalyst.bypass.mute"),
            Map.entry("silenciar", "chatalyst.bypass.mute"));

    private DiscordStaffTargets() {
    }

    /** @return el nodo bypass del verbo, o null si el verbo no castiga a nadie */
    static String bypassFor(String verb) {
        return verb == null ? null : BYPASS.get(verb.toLowerCase(Locale.ROOT));
    }

    /** Una IP escrita a mano en {@code !banip}: ahi no hay cuenta cuyo bypass mirar. */
    static boolean looksLikeAddress(String argument) {
        return argument != null && (argument.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || (argument.contains(":") && argument.matches("[0-9A-Fa-f:]+")));
    }

    /**
     * Si un staff no operador puede actuar sobre {@code target}.
     *
     * <p>Conectado, se lee en el hilo de su entidad (Folia). Desconectado, solo LuckPerms sabe
     * sus permisos: sin el, o si falla, se niega — no se puede demostrar que no tenga el bypass.
     */
    static CompletableFuture<Boolean> canAffect(ChatalystPlugin plugin, OfflinePlayer target,
                                                String bypassPermission) {
        if (target == null) return CompletableFuture.completedFuture(false);
        boolean exemptOps = plugin.getConfig().getBoolean("Staff_Moderation.Exempt_Ops", true);
        Player online = target.getPlayer();
        if (online != null) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            boolean scheduled = FoliaTasks.entity(plugin, online, current -> {
                try {
                    result.complete(!(exemptOps && current.isOp())
                            && !current.hasPermission(bypassPermission));
                } catch (RuntimeException failure) {
                    result.complete(false);
                }
            }, () -> result.complete(false));
            if (!scheduled) result.complete(false);
            return result;
        }
        if (exemptOps && target.isOp()) return CompletableFuture.completedFuture(false);
        if (!dev.chatalyst.hook.LuckPermsHook.isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }
        return dev.chatalyst.hook.LuckPermsHook
                .hasPermissionAsync(target.getUniqueId(), bypassPermission)
                .thenApply(hasBypass -> !hasBypass)
                .exceptionally(failure -> false);
    }
}
