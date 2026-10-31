package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Hub de /chatcolor, con la misma disposicion que el del apodo (/nickcolor): colores fuertes,
 * pastel, gradientes, un gradiente propio de dos colores, estilos y restablecer. Antes /chatcolor
 * era una rejilla plana sin gradiente propio, asi que un jugador con el apodo en
 * {@code <gradient:dark_red:red>} no podia llevar el mismo color en el chat.
 *
 * <p>Sin "Animados" ni "Titulo e iconos": una linea de chat ya enviada no puede animarse, y el
 * titulo es del nametag, no del chat.
 *
 * <p>Modo anonimo: a diferencia del apodo, aqui NO se bloquea. El color de chat de un anonimo ya
 * no se pinta (el render lo ignora mientras dure el disfraz), asi que elegirlo no delata nada; es
 * el mismo comportamiento que /chatcolor tenia antes de este hub.
 */
public final class ChatColorHub implements Menu {

    private static final int PREVIEW = 4;
    private static final int STRONG = 10;
    private static final int PASTEL = 12;
    private static final int GRADIENTS = 14;
    private static final int CUSTOM = 16;
    private static final int STYLES = 20;
    private static final int RESET = 24;

    private final JavaPlugin plugin;
    private final ChatColorManager colors;

    public ChatColorHub(JavaPlugin plugin, ChatColorManager colors) {
        this.plugin = plugin;
        this.colors = colors;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("color.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "color.gui-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        inv.setItem(PREVIEW, ChatColorMenu.currentItem(plugin, colors, viewer,
                colors.getBaseColorTag(viewer.getUniqueId())));
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
        inv.setItem(RESET, MenuItems.item(Material.BARRIER, Lang.get(viewer, "color.reset-item"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.gui-reset-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.chatcolor")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        switch (slot) {
            case STRONG -> Menu.open(player,
                    new ChatColorMenu(plugin, colors, ChatColorPalette.Category.STRONG));
            case PASTEL -> Menu.open(player,
                    new ChatColorMenu(plugin, colors, ChatColorPalette.Category.PASTEL));
            case GRADIENTS -> Menu.open(player,
                    new ChatColorMenu(plugin, colors, ChatColorPalette.Category.GRADIENT));
            case CUSTOM -> {
                // Mismo permiso que el gradiente propio del apodo.
                if (!player.hasPermission(ChatGradientBuilderMenu.PERMISSION)) {
                    player.sendMessage(Lang.get(player, "color.no-permission"));
                    return;
                }
                Menu.open(player, new ChatGradientBuilderMenu(plugin, colors));
            }
            case STYLES -> Menu.open(player, new ChatStyleMenu(plugin, colors));
            case RESET -> {
                // Igual que el boton del menu y /chatcolor reset: color Y estilos fuera.
                if (!colors.clearColor(player.getUniqueId())
                        || !colors.clearStyles(player.getUniqueId())) {
                    player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                    return;
                }
                player.sendMessage(Lang.get(player, "color.reset"));
                Menu.open(player, new ChatColorHub(plugin, colors));
            }
            default -> { }
        }
    }

    private ItemStack category(Player viewer, Material material, String key,
                               ChatColorPalette.Category category) {
        int count = ChatColorPalette.options(plugin, category).size();
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of(Lang.raw(viewer, "nickcolor.cat-lore", "count", count)));
    }
}
