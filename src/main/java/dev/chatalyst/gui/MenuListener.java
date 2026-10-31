package dev.chatalyst.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Enruta los clicks de los menus de Chatalyst a su {@link Menu} y bloquea que se saquen items.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getClickedInventory() == event.getInventory()) {
            holder.getMenu().onClick(p, event.getRawSlot(), event.getClick());
        } else if (event.getClickedInventory() != null) {
            // Click en el inventario propio: algunos menus lo usan (oferta del mercado).
            holder.getMenu().onClickBottom(p, event.getSlot(), event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
