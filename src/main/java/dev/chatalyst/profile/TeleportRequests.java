package dev.chatalyst.profile;

import dev.chatalyst.hook.EssentialsHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bloquear o permitir /tpa desde /perfil.
 *
 * <p>Con EssentialsX se usa su API ({@code User#setTeleportEnabled}), que es lo mismo que hace
 * su /tptoggle y deja el estado legible. Sin Essentials, si algun otro plugin registra
 * {@code /tptoggle}, se ejecuta como el jugador; su estado no se puede leer y el interruptor
 * se muestra como "lo gestiona el servidor". Si no hay ninguno, el interruptor no aparece.
 */
final class TeleportRequests {

    static final String COMMAND = "tptoggle";

    private TeleportRequests() {
    }

    static boolean available(Player player) {
        return EssentialsHook.teleportEnabled(player) != null || commandExists();
    }

    /** true = acepta /tpa, false = los bloquea, null = no se sabe (lo lleva otro plugin). */
    static Boolean enabled(Player player) {
        return EssentialsHook.teleportEnabled(player);
    }

    /**
     * Alterna el ajuste. Devuelve el estado nuevo, o null si se delego en el comando (estado
     * desconocido; el propio comando ya le dice al jugador como quedo).
     */
    static Result toggle(Player player) {
        Boolean current = EssentialsHook.teleportEnabled(player);
        if (current != null) {
            boolean next = !current;
            return EssentialsHook.setTeleportEnabled(player, next)
                    ? new Result(true, next) : new Result(false, null);
        }
        if (commandExists()) {
            return new Result(player.performCommand(COMMAND), null);
        }
        return new Result(false, null);
    }

    private static boolean commandExists() {
        try {
            return Bukkit.getCommandMap().getCommand(COMMAND) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    record Result(boolean done, Boolean enabled) {
    }
}
