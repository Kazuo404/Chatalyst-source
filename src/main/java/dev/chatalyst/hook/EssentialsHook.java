package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Puerta de entrada segura a EssentialsX. Espeja el /nick de Chatalyst en el sistema de
 * nicknames de Essentials, de modo que sus comandos (/tpa &lt;nick&gt;, /whois, etc.)
 * tambien reconozcan el apodo. Si Essentials no esta instalado, todo degrada limpio
 * (patron anti-NoClassDefFound: {@link EssentialsBridge} solo se toca cuando esta).
 */
public final class EssentialsHook {

    private static Boolean available = null;

    private EssentialsHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("Essentials") != null;
            if (available) {
                try {
                    Class.forName("com.earth2me.essentials.Essentials");
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

    /**
     * Estado de /tptoggle de Essentials: true si acepta /tpa, false si los bloquea, null si
     * Essentials no esta (o aun no habilitado) o su API fallo.
     */
    public static Boolean teleportEnabled(Player player) {
        if (!enabled()) return null;
        try {
            return EssentialsBridge.isTeleportEnabled(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Cambia el estado de /tptoggle por la API. False si no se pudo. */
    public static boolean setTeleportEnabled(Player player, boolean value) {
        if (!enabled()) return false;
        try {
            return EssentialsBridge.setTeleportEnabled(player, value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean enabled() {
        if (!isAvailable()) return false;
        org.bukkit.plugin.Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        return ess != null && ess.isEnabled();
    }

    /**
     * Espeja el apodo (texto PLANO, sin formato) en Essentials, o lo quita si es null.
     * El formato/color lo sigue pintando Chatalyst; a Essentials solo le interesa el
     * texto para que sus comandos resuelvan al jugador por el apodo.
     */
    public static void syncNick(Player player, String plainNick) {
        if (!isAvailable()) return;
        // loadbefore Essentials: en un arranque/reload Essentials puede no estar habilitado aun.
        org.bukkit.plugin.Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess == null || !ess.isEnabled()) return;
        try {
            EssentialsBridge.setNickname(player, plainNick);
        } catch (Throwable ignored) {
            // API distinta/rota: el nick de Chatalyst sigue funcionando sin el espejo.
        }
    }
}
