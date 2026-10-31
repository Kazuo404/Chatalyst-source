package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

/**
 * Controles de navegacion compartidos por todas las GUI.
 *
 * <p>Nombres y descripciones son bilingues y viven en lang; materiales, orden y paleta
 * transmiten siempre el mismo significado visual en cualquier modulo.
 */
public final class MenuNavigation {

    private MenuNavigation() {
    }

    public static ItemStack home(CommandSender viewer) {
        return control(viewer, Material.NETHER_STAR, "home");
    }

    public static ItemStack back(CommandSender viewer) {
        return control(viewer, Material.SPECTRAL_ARROW, "back");
    }

    public static ItemStack previous(CommandSender viewer, int page, int pages) {
        return paged(viewer, Material.SPECTRAL_ARROW, "previous", page, pages);
    }

    public static ItemStack next(CommandSender viewer, int page, int pages) {
        return paged(viewer, Material.SPECTRAL_ARROW, "next", page, pages);
    }

    /**
     * "Pagina anterior", o un boton gris desactivado en la primera pagina. Asi la barra de abajo
     * tiene siempre las dos flechas y el menu queda simetrico en todas las paginas.
     * {@code page} empieza en 1.
     */
    public static ItemStack previousOrFirst(CommandSender viewer, int page, int pages) {
        return page > 1 ? previous(viewer, page, pages)
                : paged(viewer, Material.GRAY_DYE, "no-previous", page, pages);
    }

    /** "Pagina siguiente", o un boton gris desactivado en la ultima. {@code page} empieza en 1. */
    public static ItemStack nextOrLast(CommandSender viewer, int page, int pages) {
        return page < Math.max(1, pages) ? next(viewer, page, pages)
                : paged(viewer, Material.GRAY_DYE, "no-next", page, pages);
    }

    public static ItemStack page(CommandSender viewer, int page, int pages) {
        return paged(viewer, Material.RECOVERY_COMPASS, "page", page, pages);
    }

    /**
     * Hueco gris "no disponible": ocupa el sitio de un boton que este espectador no puede usar
     * (permiso, plugin que falta) para que el menu siga siendo simetrico.
     */
    public static ItemStack unavailable(CommandSender viewer) {
        return control(viewer, Material.GRAY_DYE, "unavailable");
    }

    public static ItemStack preview(CommandSender viewer) {
        return control(viewer, Material.ENDER_EYE, "preview");
    }

    public static ItemStack close(CommandSender viewer) {
        return control(viewer, Material.BARRIER, "close");
    }

    public static ItemStack refresh(CommandSender viewer) {
        return control(viewer, Material.ECHO_SHARD, "refresh");
    }

    private static ItemStack control(CommandSender viewer, Material material, String action) {
        String key = "ui-navigation." + action;
        return MenuItems.item(material, Lang.raw(viewer, key + ".name"),
                Lang.rawList(viewer, key + ".lore"));
    }

    private static ItemStack paged(CommandSender viewer, Material material, String action,
                                   int page, int pages) {
        int safePages = Math.max(1, pages);
        int safePage = Math.max(1, Math.min(page, safePages));
        String key = "ui-navigation." + action;
        return MenuItems.item(material,
                Lang.raw(viewer, key + ".name", "page", safePage, "pages", safePages),
                Lang.rawList(viewer, key + ".lore", "page", safePage, "pages", safePages));
    }
}
