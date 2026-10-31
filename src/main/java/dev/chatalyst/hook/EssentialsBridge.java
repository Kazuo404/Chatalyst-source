package dev.chatalyst.hook;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Aislado a proposito: solo se carga cuando EssentialsX esta presente (lo comprueba
 * {@link EssentialsHook}). Unica clase con imports de Essentials.
 */
final class EssentialsBridge {

    private EssentialsBridge() {
    }

    /** Si acepta solicitudes de teletransporte (/tptoggle), o null si Essentials no lo conoce. */
    static Boolean isTeleportEnabled(Player player) {
        User user = user(player);
        return user == null ? null : user.isTeleportEnabled();
    }

    static boolean setTeleportEnabled(Player player, boolean enabled) {
        User user = user(player);
        if (user == null) return false;
        user.setTeleportEnabled(enabled);
        return true;
    }

    private static User user(Player player) {
        Essentials ess = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
        return ess == null ? null : ess.getUser(player);
    }

    static void setNickname(Player player, String plainNick) {
        Essentials ess = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess == null) return;
        User user = ess.getUser(player);
        if (user == null) return;
        String current = user.getNickname();
        String next = (plainNick == null || plainNick.isEmpty()
                || plainNick.equals(player.getName())) ? null : plainNick;
        if (java.util.Objects.equals(current, next)) return;
        user.setNickname(next);
        // Re-aplica el display name de Essentials al instante (si su config lo gestiona).
        user.setDisplayNick();
    }
}
