package dev.chatalyst.staff.autoclick;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Altas y bajas de jugadores y, sin PacketEvents, la fuente de clics por eventos del servidor.
 *
 * <p>Con eventos la marca de tiempo es la de PROCESAR el paquete, no la de recibirlo: en un
 * servidor holgado son milisegundos, pero cuando el tick va cargado los paquetes esperan y
 * llegan juntos (hasta ~50 ms de error). Eso solo puede hacer parecer MENOS regular a un
 * robot, nunca mas regular a un humano. Todos los handlers son MONITOR y miran tambien los
 * eventos cancelados: el cliente hizo el gesto aunque otro plugin lo anule.
 */
final class AutoclickListener implements Listener {

    private final AutoclickManager manager;

    AutoclickListener(AutoclickManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.track(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer().getUniqueId());
    }

    /** Tracker solo si los clics salen de eventos; con paquetes esto ya lo apunto la red. */
    private ClickTracker fromEvents(UUID id) {
        return manager.packetSource() ? null : manager.tracker(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.swing(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigStart(BlockDamageEvent event) {
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.digStart(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigAbort(BlockDamageAbortEvent event) {
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.digStop(System.nanoTime(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.digStop(System.nanoTime(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.use(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.use(System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        ClickTracker tracker = fromEvents(event.getPlayer().getUniqueId());
        if (tracker != null) tracker.use(System.nanoTime());
    }
}
