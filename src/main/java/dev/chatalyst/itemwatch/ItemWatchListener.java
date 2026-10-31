package dev.chatalyst.itemwatch;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/** Escaneo al entrar, revision de contenedores al abrirlos y limpieza al salir. */
final class ItemWatchListener implements Listener {

    /** Unos segundos tras entrar: deja que otros plugins terminen de cargarle el inventario. */
    private static final long JOIN_DELAY_TICKS = 100L;

    private final ItemWatchManager manager;

    ItemWatchListener(ItemWatchManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.scheduleScan(event.getPlayer(), JOIN_DELAY_TICKS);
    }

    /**
     * Cerrar una mochila de PlayerVaults es cuando cambia su contenido. Se reconoce por el
     * holder de su inventario (com.drtshock.playervaults...VaultHolder), sin depender del
     * plugin al compilar.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        Object holder = top.getHolder(false);
        if (holder != null && holder.getClass().getName().startsWith("com.drtshock.playervaults")) {
            manager.vaultClosed();
            manager.triggerScan(player, true);
            return;
        }
        // Ender chest o cofre/barril/shulker colocado: lo guardado acaba de cambiar.
        if (top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST
                || (top.getLocation() != null
                        && !(holder instanceof dev.chatalyst.gui.MenuHolder))) {
            manager.triggerScan(player, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Solo contenedores COLOCADOS en el mundo: los menus de plugins (tiendas, kits, mochilas
     * virtuales) no tienen ubicacion y no son el escondite de nadie.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof dev.chatalyst.gui.MenuHolder) return;
        Location location = top.getLocation();
        if (location == null) return;
        manager.inspectContainer(player, location, top.getContents());
    }
}
