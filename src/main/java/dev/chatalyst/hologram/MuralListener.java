package dev.chatalyst.hologram;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Los marcos de un mural: aparecen y se van con su chunk y nadie los toca. Fijos e
 * invulnerables ya resisten casi todo; esto cubre lo que queda (romper la pared de detras,
 * un golpe en creativo, sacar o girar el mapa) en LOWEST, antes que las protecciones de zona.
 */
final class MuralListener implements Listener {

    private final MuralService murals;

    MuralListener(MuralService murals) {
        this.murals = murals;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        murals.onChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        murals.onChunkUnload(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (murals.isOrphan(entity)) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(HangingBreakEvent event) {
        if (murals.isOurs(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (murals.isOurs(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (murals.isOurs(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        if (murals.isOurs(event.getItemFrame())) event.setCancelled(true);
    }
}
