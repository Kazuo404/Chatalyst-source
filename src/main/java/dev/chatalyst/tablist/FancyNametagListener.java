package dev.chatalyst.tablist;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mantiene el cartel fancy pegado al jugador durante su ciclo de vida: join/quit,
 * muerte/respawn (el passenger se desmonta al morir), teleport (idem) y agacharse.
 */
public final class FancyNametagListener implements Listener {

    /** 8 bloques: por debajo es una perla o un asiento, el cartel se re-engancha sin parpadeo. */
    private static final double FAR_TELEPORT_SQ = 8 * 8;

    private final JavaPlugin plugin;
    private final FancyNametagManager fancy;

    public FancyNametagListener(JavaPlugin plugin, FancyNametagManager fancy) {
        this.plugin = plugin;
        this.fancy = fancy;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Un tick despues: da tiempo a que carguen apodo y prefijos del rango.
        later(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        fancy.remove(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // Al morir el passenger queda flotando en el punto de muerte: fuera.
        fancy.remove(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // El teleport expulsa a los passengers. Lejos o a otro mundo, cartel nuevo en el destino:
        // re-montar el viejo lo deja flotando en el cliente. Cerca (perla, asiento) basta re-enganchar.
        if (farTeleport(event.getFrom(), event.getTo())) fancy.detachForTeleport(event.getPlayer());
        later(event.getPlayer());
    }

    static boolean farTeleport(Location from, Location to) {
        if (to == null) return false;
        return from.getWorld() != to.getWorld() || from.distanceSquared(to) > FAR_TELEPORT_SQ;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        fancy.onSneak(event.getPlayer(), event.isSneaking());
    }

    private void later(Player p) {
        p.getScheduler().runDelayed(plugin, t -> {
            if (p.isOnline()) fancy.refresh(p);
        }, null, 2L);
    }
}
