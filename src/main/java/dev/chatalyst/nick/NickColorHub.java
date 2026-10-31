package dev.chatalyst.nick;

import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * Hub del GUI de apodo (/nickcolor): en vez de una rejilla plana, ofrece categorias — colores
 * fuertes, pastel, gradientes, un constructor de gradiente propio (elige 2 colores) — mas los
 * estilos de texto y, con nametag fancy, el titulo/iconos. Cada seccion abre su propio menu.
 */
public final class NickColorHub implements Menu {

    private static final int PREVIEW = 4;
    private static final int STRONG = 10;
    private static final int PASTEL = 12;
    private static final int GRADIENTS = 14;
    private static final int CUSTOM = 16;
    private static final int STYLES = 19;
    private static final int ANIMATED = 21;   // solo con nametag fancy + Per_Player
    private static final int TITLE = 23;      // solo con nametag fancy
    private static final int RESET = 25;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final NickManager nicks;

    public NickColorHub(org.bukkit.plugin.java.JavaPlugin plugin, NickManager nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nickcolor.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nickcolor.gui-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        inv.setItem(PREVIEW, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "nickcolor.gui-your-nick"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-now") + ": " + nameOf(viewer))));

        inv.setItem(STRONG, category(viewer, Material.RED_DYE, "nickcolor.cat-strong",
                ChatColorPalette.Category.STRONG));
        inv.setItem(PASTEL, category(viewer, Material.PINK_DYE, "nickcolor.cat-pastel",
                ChatColorPalette.Category.PASTEL));
        inv.setItem(GRADIENTS, category(viewer, Material.GLOW_INK_SAC, "nickcolor.cat-gradients",
                ChatColorPalette.Category.GRADIENT));
        inv.setItem(CUSTOM, MenuItems.item(Material.BRUSH,
                Lang.raw(viewer, "nickcolor.cat-custom"),
                List.of(Lang.raw(viewer, "nickcolor.cat-custom-lore"))));

        inv.setItem(STYLES, MenuItems.item(Material.ENCHANTED_BOOK,
                Lang.raw(viewer, "color.gui-styles"),
                List.of(Lang.raw(viewer, "color.gui-edit-effects"))));
        if (animationAvailable()) {
            inv.setItem(ANIMATED, MenuItems.item(Material.FIREWORK_STAR,
                    Lang.raw(viewer, "nickcolor.anim-open"),
                    List.of(Lang.raw(viewer, "nickcolor.anim-open-lore"))));
        }
        if (plugin.getConfig().getBoolean("Nametag.Fancy.Enabled", false)
                && viewer.hasPermission("chatalyst.nametagtitle")) {
            inv.setItem(TITLE, MenuItems.item(Material.WHITE_BANNER,
                    Lang.raw(viewer, "nametag-title.gui-open"),
                    List.of(Lang.raw(viewer, "nametag-title.gui-open-lore"))));
        }
        inv.setItem(RESET, MenuItems.item(Material.BARRIER, Lang.get(viewer, "nickcolor.reset-item"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-reset-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == TITLE) {
            if (player.hasPermission("chatalyst.nametagtitle")
                    && plugin.getConfig().getBoolean("Nametag.Fancy.Enabled", false)) {
                Menu.open(player, new NickTitleMenu(plugin, nicks));
            }
            return;
        }
        if (slot == STYLES) {
            Menu.open(player, new NickStyleMenu(plugin, nicks));
            return;
        }
        if (slot == ANIMATED) {
            if (animationAvailable() && player.hasPermission("chatalyst.nick.color")) {
                Menu.open(player, new NickAnimationMenu(plugin, nicks));
            }
            return;
        }
        // El resto (categorias de color, gradiente custom, reset) exige el permiso de color.
        if (!player.hasPermission("chatalyst.nick.color")) {
            if (slot == STRONG || slot == PASTEL || slot == GRADIENTS
                    || slot == CUSTOM || slot == RESET) {
                player.closeInventory();
                player.sendMessage(Lang.get(player, "common.no-permission"));
            }
            return;
        }
        if (dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                && (slot == STRONG || slot == PASTEL || slot == GRADIENTS
                        || slot == CUSTOM || slot == RESET)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "nick.anonymous-self"));
            return;
        }
        switch (slot) {
            case STRONG -> Menu.open(player,
                    new NickColorMenu(plugin, nicks, ChatColorPalette.Category.STRONG));
            case PASTEL -> Menu.open(player,
                    new NickColorMenu(plugin, nicks, ChatColorPalette.Category.PASTEL));
            case GRADIENTS -> Menu.open(player,
                    new NickColorMenu(plugin, nicks, ChatColorPalette.Category.GRADIENT));
            case CUSTOM -> Menu.open(player, new NickGradientBuilderMenu(plugin, nicks));
            case RESET -> {
                NickColorMenu.apply(nicks, player, "");
                clearAnimation(player); // "restablecer" limpia TODO el apodo, animacion incluida
                player.sendMessage(Lang.get(player, "nickcolor.reset"));
                player.closeInventory();
            }
            default -> { }
        }
    }

    private org.bukkit.inventory.ItemStack category(Player viewer, Material material, String key,
                                                    ChatColorPalette.Category category) {
        int count = ChatColorPalette.options(plugin, category).size();
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of(Lang.raw(viewer, "nickcolor.cat-lore", "count", count)));
    }

    /** Quita AMBAS animaciones (apodo y titulo) del jugador si el sistema esta activo. */
    private void clearAnimation(Player player) {
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst == null) return;
        dev.chatalyst.tablist.FancyNametagManager fancy = chatalyst.getFancyNametags();
        if (fancy != null && fancy.animationChoiceEnabled()) {
            fancy.setPlayerAnimation(player, null);
            fancy.setTitleAnimation(player, null);
        }
    }

    /** True si el nametag fancy esta activo y los jugadores pueden elegir animacion (Per_Player). */
    private boolean animationAvailable() {
        if (!plugin.getConfig().getBoolean("Nametag.Fancy.Enabled", false)) return false;
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        return chatalyst != null && chatalyst.getFancyNametags() != null
                && chatalyst.getFancyNametags().animationChoiceEnabled();
    }

    private String nameOf(Player viewer) {
        String nick = nicks.getNick(viewer.getUniqueId());
        return nick != null && !nick.isEmpty() ? nick : viewer.getName();
    }
}
