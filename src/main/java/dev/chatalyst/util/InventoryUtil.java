package dev.chatalyst.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Entrega de items al jugador sin perdida silenciosa. */
public final class InventoryUtil {

    private InventoryUtil() {
    }

    /** Mete el item en el inventario; lo que no quepa cae al suelo a los pies del jugador. */
    public static void giveOrDrop(Player p, ItemStack item) {
        for (ItemStack leftover : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }
}
