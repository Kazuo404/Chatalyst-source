package dev.chatalyst.hook;

import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Aislado a proposito: solo se carga cuando ItemsAdder esta presente (lo comprueba
 * {@link ItemsAdderHook}). Reemplaza los emojis/font-images (:nombre:) del texto,
 * respetando los permisos del jugador para cada emoji.
 */
final class ItemsAdderBridge {

    private ItemsAdderBridge() {
    }

    static String replace(Player who, String text) {
        try {
            return FontImageWrapper.replaceFontImages(who, text);
        } catch (Throwable t) {
            return text;
        }
    }

    static String replaceUnchecked(String text) {
        try {
            return FontImageWrapper.replaceFontImages(text);
        } catch (Throwable t) {
            return text;
        }
    }

    static String itemId(ItemStack item) {
        try {
            CustomStack stack = CustomStack.byItemStack(item);
            return stack != null ? stack.getNamespacedID() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static ItemStack item(String namespacedId) {
        try {
            CustomStack stack = CustomStack.getInstance(namespacedId);
            return stack == null ? null : stack.getItemStack();
        } catch (Throwable t) {
            return null;
        }
    }
}
