package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;

/**
 * Puerta de entrada segura a WorldGuard para los canales de region. Si WorldGuard no esta
 * instalado devuelve un conjunto vacio (patron anti-NoClassDefFound: {@link WorldGuardBridge}
 * solo se toca si esta).
 *
 * <p>IMPORTANTE: {@link #currentRegions} consulta la ubicacion del jugador, asi que debe
 * llamarse en el HILO PRINCIPAL. El chat es asincrono, por eso {@code RegionPartyTracker}
 * cachea el resultado en el hilo principal y el chat solo lee la cache.
 */
public final class WorldGuardHook {

    private static Boolean available = null;

    private WorldGuardHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
            if (available) {
                try {
                    Class.forName("com.sk89q.worldguard.WorldGuard");
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
     * Regiones de WorldGuard (tambien las de ProtectionStones, que son regiones de WorldGuard)
     * que cubren ese punto, sin la global. Llamar en el hilo que posee esa ubicacion.
     */
    public static Set<String> regionsAt(org.bukkit.Location location) {
        Set<String> r = regionsAtOrUnknown(location);
        return r == null ? Collections.emptySet() : r;
    }

    /**
     * Como {@link #regionsAt}, pero null cuando NO SE SABE (WorldGuard ausente, regiones sin
     * cargar o un error). Quien borra cosas por "no hay region" tiene que distinguirlo.
     */
    public static Set<String> regionsAtOrUnknown(org.bukkit.Location location) {
        if (!isAvailable() || location == null) return null;
        try {
            return WorldGuardBridge.regionIds(location);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Dueno o miembro de alguna region en ese punto. Sin WorldGuard: false. */
    public static boolean isMemberAt(Player player, org.bukkit.Location location) {
        if (!isAvailable() || player == null || location == null) return false;
        try {
            return WorldGuardBridge.isMemberAt(player, location);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Regiones actuales del jugador. Llamar en el hilo principal (accede a la ubicacion). */
    public static Set<String> currentRegions(Player player) {
        if (!isAvailable()) return Collections.emptySet();
        try {
            return WorldGuardBridge.regionIds(player);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }
}
