package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/** Safe optional bridge to Nexo custom item ids. */
public final class NexoHook {

    private static Boolean available;
    private static Method idFromItem;

    private NexoHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("Nexo") != null;
            if (available) {
                try {
                    Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems");
                    idFromItem = items.getMethod("idFromItem", ItemStack.class);
                } catch (Throwable t) {
                    available = false;
                    idFromItem = null;
                }
            }
        }
        return available;
    }

    public static void reset() {
        available = null;
        idFromItem = null;
    }

    public static String itemId(ItemStack item) {
        if (!isAvailable() || item == null || item.getType().isAir() || idFromItem == null) return null;
        try {
            Object id = idFromItem.invoke(null, item);
            return id instanceof String s && !s.isBlank() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
