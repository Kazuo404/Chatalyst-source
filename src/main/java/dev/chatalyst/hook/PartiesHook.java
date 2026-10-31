package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Puerta de entrada segura a Parties (AlessioDP) para el canal de party. Si Parties no esta
 * instalado, {@link #sameParty} devuelve true (canal normal) para no romper el chat (patron
 * anti-NoClassDefFound: {@link PartiesBridge} solo se toca cuando esta disponible).
 */
public final class PartiesHook {

    private static Boolean available = null;

    private PartiesHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("Parties") != null;
            if (available) {
                try {
                    Class.forName("com.alessiodp.parties.api.Parties");
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

    /** Id del party del jugador (o null). Se puede llamar desde el hilo principal; Parties
     *  cachea, pero lo consultamos desde el tracker para no tocar la API en el chat async. */
    public static UUID currentPartyId(Player player) {
        if (!isAvailable()) return null;
        try {
            return PartiesBridge.partyId(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Nombre del party del jugador (o null). Main thread only, igual que currentPartyId. */
    public static String currentPartyName(Player player) {
        if (!isAvailable()) return null;
        try {
            return PartiesBridge.partyName(player);
        } catch (Throwable t) {
            return null;
        }
    }
}
