package dev.chatalyst.hologram;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Une los carteles con el mundo: chunks, entidades huerfanas, jugadores y clics. */
final class HologramListener implements Listener {

    private final HologramService service;

    HologramListener(HologramService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        service.onChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        service.onChunkUnload(event.getChunk());
    }

    /**
     * Entidades nuestras que vuelven con un chunk: no deberia haber ninguna (no se guardan),
     * pero un crash a mitad de guardado o una version vieja podrian dejarlas. Se borran.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (service.isOrphan(entity)) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer());
    }

    /**
     * Clic derecho (y el toque en Bedrock).
     *
     * <p>En LOWEST y sin {@code ignoreCancelled}: la entidad es nuestra y lo que pasa al
     * pulsarla lo decide el cartel. Escuchando tarde y solo lo no cancelado, un cartel con clic
     * dentro de una zona protegida (el spawn con WorldGuard, que cancela el uso de entidades a
     * quien no es miembro) no hacia nada justo donde mas se usan. Cancelando pronto, ademas,
     * la proteccion ya no llega a mandar su "no puedes usar eso aqui".
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        // La otra mano tambien se cancela (que no se use un objeto contra la caja), sin clic.
        if (event.getHand() != EquipmentSlot.HAND) {
            if (service.isOurs(event.getRightClicked())) event.setCancelled(true);
            return;
        }
        handle(event.getPlayer(), event.getRightClicked(), () -> event.setCancelled(true));
    }

    /** Clic izquierdo: golpear la caja de un cartel tambien cuenta como pulsarlo. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        handle(event.getPlayer(), event.getAttacked(), () -> event.setCancelled(true));
    }

    private void handle(Player player, Entity entity, Runnable cancel) {
        if (!service.isOurs(entity)) return;
        cancel.run();
        String id = service.clickTarget(entity);
        if (id != null) service.click(player, id);
    }
}
