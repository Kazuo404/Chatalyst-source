package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Puerta de entrada segura a ItemsAdder. Si no esta instalado, devuelve el texto sin
 * cambios (patron anti-NoClassDefFound). Permite emojis/font-images custom en el chat.
 */
public final class ItemsAdderHook {

    private static Boolean available = null;

    private ItemsAdderHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
            if (available) {
                try {
                    Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
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

    /** Reemplaza los emojis (:nombre:) del texto segun los permisos del jugador. */
    public static String replaceEmojis(Player who, String text) {
        if (!isAvailable() || text == null || text.isEmpty()) return text;
        try {
            return ItemsAdderBridge.replace(who, text);
        } catch (Throwable t) {
            // El render corre en el hilo async del chat: si ItemsAdder falla, devolvemos el
            // texto tal cual en vez de romper el mensaje.
            return text;
        }
    }

    /**
     * Reemplaza los emojis (:nombre:) SIN chequear permisos. Solo para texto escrito por
     * el STAFF (formatos de chat, prefijos de rango, tab, nametag) — nunca para el mensaje
     * de un jugador, que debe pasar por {@link #replaceEmojis(Player, String)}.
     */
    public static String replaceEmojisUnchecked(String text) {
        if (!isAvailable() || text == null || text.isEmpty()) return text;
        try {
            return ItemsAdderBridge.replaceUnchecked(text);
        } catch (Throwable t) {
            return text;
        }
    }

    /** Devuelve el namespaced id si el ItemStack pertenece a ItemsAdder. */
    public static String itemId(ItemStack item) {
        if (!isAvailable() || item == null || item.getType().isAir()) return null;
        try {
            return ItemsAdderBridge.itemId(item);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resuelve un item registrado por su ID {@code namespace:id}. Devuelve una copia segura
     * para que una GUI pueda editar nombre/lore sin modificar el prototipo de ItemsAdder.
     */
    public static ItemStack item(String namespacedId) {
        if (!isAvailable() || namespacedId == null
                || !namespacedId.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) {
            return null;
        }
        try {
            ItemStack item = ItemsAdderBridge.item(namespacedId);
            return item == null ? null : item.clone();
        } catch (Throwable t) {
            return null;
        }
    }
}
