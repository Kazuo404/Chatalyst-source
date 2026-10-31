package dev.chatalyst.hook;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Aislado a proposito: solo se carga cuando WorldGuard esta presente (lo comprueba
 * {@link WorldGuardHook}). Devuelve las regiones donde esta un jugador, para el canal de
 * region (solo se ven entre si quienes comparten alguna region).
 */
final class WorldGuardBridge {

    private WorldGuardBridge() {
    }

    static Set<String> regionIds(Player player) {
        Set<String> ids = new HashSet<>();
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(local.getLocation());
        for (ProtectedRegion region : set) {
            ids.add(region.getId());
        }
        return ids;
    }

    /**
     * Si el jugador es dueno o miembro de alguna region (sin la global) que cubre ese punto Y
     * WorldGuard le deja construir y abrir cofres ahi (prioridades, flags y parcelas superpuestas).
     */
    static boolean isMemberAt(Player player, org.bukkit.Location location) {
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location at = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location);
        ApplicableRegionSet set = query.getApplicableRegions(at);
        if (set.isVirtual()) return false;
        boolean member = false;
        for (ProtectedRegion region : set) {
            if (ProtectedRegion.GLOBAL_REGION.equalsIgnoreCase(region.getId())) continue;
            if (region.isOwner(local) || region.isMember(local)) {
                member = true;
                break;
            }
        }
        return member && query.testBuild(at, local, com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS);
    }

    /** Regiones (sin la global) que cubren un punto. Para los candados de cofres. */
    static Set<String> regionIds(org.bukkit.Location location) {
        Set<String> ids = new HashSet<>();
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(
                com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location));
        // Regiones sin cargar (FailedLoadRegionSet): no se sabe, que no es lo mismo que "no hay".
        if (set.isVirtual()) return null;
        for (ProtectedRegion region : set) {
            if (!ProtectedRegion.GLOBAL_REGION.equalsIgnoreCase(region.getId())) ids.add(region.getId());
        }
        return ids;
    }
}
