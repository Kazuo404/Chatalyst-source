package dev.chatalyst.tag;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

/**
 * Marco de tooltip por rareza para los titulos en los menus (Settings.Rarity_Tooltip_Style).
 *
 * <p>{@code ItemMeta#setTooltipStyle} llego en la API 1.21.2 y el plugin compila contra 1.21.1, asi que
 * se busca por reflexion una sola vez. En un servidor sin el metodo el objeto queda con el tooltip
 * vanilla, sin error.
 */
final class TagTooltips {

    private static final Method SET_STYLE = find();

    private TagTooltips() {
    }

    private static Method find() {
        try {
            return ItemMeta.class.getMethod("setTooltipStyle", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    /** Pone el estilo {@code "ns:id"} al objeto; {@code style} null o invalido lo deja igual. */
    static ItemStack styled(ItemStack item, String style) {
        if (item == null || style == null || SET_STYLE == null) return item;
        NamespacedKey key = NamespacedKey.fromString(style);
        ItemMeta meta = item.getItemMeta();
        if (key == null || meta == null) return item;
        try {
            SET_STYLE.invoke(meta, key);
            item.setItemMeta(meta);
        } catch (ReflectiveOperationException ignored) {
            // Sin marco: el titulo se sigue viendo con el tooltip vanilla.
        }
        return item;
    }
}
