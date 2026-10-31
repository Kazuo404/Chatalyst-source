package dev.chatalyst.nick;

import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructor de gradiente propio para el apodo: el jugador elige DOS colores de la paleta
 * (clic IZQUIERDO = color 1, clic DERECHO = color 2) y "aplicar" arma un {@code <gradient:A:B>}
 * sobre su nick. El estado (los dos colores elegidos) viaja por el constructor, como el resto
 * de menus del plugin. Gateado por {@code chatalyst.gradient.custom}.
 */
public final class NickGradientBuilderMenu implements Menu {

    public static final String PERMISSION = "chatalyst.gradient.custom";

    private static final int BACK = 45;
    private static final int SLOT_A = 47;
    private static final int PREVIEW = 49;
    private static final int SLOT_B = 51;
    private static final int APPLY = 53;

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final String colorA; // valor de color ("#FF6B9B" o "red"), o null
    private final String colorB;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();

    public NickGradientBuilderMenu(JavaPlugin plugin, NickManager nicks) {
        this(plugin, nicks, null, null);
    }

    private NickGradientBuilderMenu(JavaPlugin plugin, NickManager nicks,
                                    String colorA, String colorB) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.colorA = colorA;
        this.colorB = colorB;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nickcolor.custom-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nickcolor.custom-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        // Paleta de colores PLANOS (no gradientes) para elegir los dos extremos.
        List<ChatColorPalette.Option> colors = new ArrayList<>();
        for (ChatColorPalette.Option o : ChatColorPalette.options(plugin)) {
            if (o.kind() == ChatColorPalette.Kind.COLOR) colors.add(o);
        }
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.min(colors.size(), 45), 0);
        for (int i = 0; i < layout.length; i++) {
            inv.setItem(layout[i], icon(colors.get(i)));
            slots.put(layout[i], colors.get(i));
        }
        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(SLOT_A, endpointItem(viewer, colorA, "nickcolor.custom-color1"));
        inv.setItem(SLOT_B, endpointItem(viewer, colorB, "nickcolor.custom-color2"));
        inv.setItem(PREVIEW, previewItem(viewer));
        // Siempre algo en APPLY (espejo de BACK): gris hasta que hay dos colores elegidos.
        inv.setItem(APPLY, colorA != null && colorB != null ? MenuItems.item(Material.LIME_CONCRETE,
                Lang.raw(viewer, "nickcolor.custom-apply"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-preview") + ": "
                        + gradientTag() + MM.strip(nameOf(viewer))))
                : MenuNavigation.unavailable(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Menu.open(player, new NickColorHub(plugin, nicks));
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "nick.anonymous-self"));
            return;
        }
        if (slot == SLOT_A) { // clic en el endpoint = limpiar ese color
            Menu.open(player, new NickGradientBuilderMenu(plugin, nicks, null, colorB));
            return;
        }
        if (slot == SLOT_B) {
            Menu.open(player, new NickGradientBuilderMenu(plugin, nicks, colorA, null));
            return;
        }
        if (slot == APPLY) {
            if (colorA == null || colorB == null) return;
            if (!NickColorMenu.apply(nicks, player, gradientTag())) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return;
            }
            player.sendMessage(Lang.get(player, "nickcolor.custom-done"));
            player.closeInventory();
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option == null) return;
        // El permiso del gradiente propio no regala los colores: sin esto, quien lo tuviera
        // podia meter en su apodo un color de rango que la paleta normal le niega.
        if (!option.canUse(player)) {
            player.sendMessage(Lang.get(player, "color.no-permission"));
            return;
        }
        String value = value(option.tag());
        // Izquierdo = color 1, derecho = color 2. Re-abre con la seleccion nueva.
        if (click.isRightClick()) {
            Menu.open(player, new NickGradientBuilderMenu(plugin, nicks, colorA, value));
        } else {
            Menu.open(player, new NickGradientBuilderMenu(plugin, nicks, value, colorB));
        }
    }

    /** {@code <gradient:A:B>} con los dos colores elegidos. */
    private String gradientTag() {
        return "<gradient:" + colorA + ":" + colorB + ">";
    }

    /** Valor de color de un tag: {@code <#FF6B9B>} -> {@code #FF6B9B}; {@code <red>} -> {@code red}. */
    private static String value(String tag) {
        String t = tag == null ? "" : tag.trim();
        return t.startsWith("<") && t.endsWith(">") ? t.substring(1, t.length() - 1) : t;
    }

    private ItemStack endpointItem(Player viewer, String value, String nameKey) {
        boolean set = value != null;
        String preview = set ? "<" + value + ">" + Lang.raw(viewer, "nickcolor.custom-chosen")
                : "<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-empty");
        return MenuItems.item(set ? Material.LEATHER_CHESTPLATE : Material.GRAY_DYE,
                Lang.raw(viewer, nameKey),
                List.of("<#C0B3D9>" + preview,
                        "<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-clear")));
    }

    private ItemStack previewItem(Player viewer) {
        List<String> lore = new ArrayList<>();
        if (colorA != null && colorB != null) {
            lore.add(gradientTag() + MM.strip(nameOf(viewer)));
        } else {
            lore.add("<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-pick"));
        }
        return MenuItems.item(Material.NAME_TAG, Lang.raw(viewer, "nickcolor.custom-preview"), lore);
    }

    private ItemStack icon(ChatColorPalette.Option option) {
        ItemStack item = new ItemStack(option.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather && option.leatherColor() != null) {
            leather.setColor(option.leatherColor());
            meta = leather;
        }
        String v = value(option.tag());
        boolean isA = v.equals(colorA);
        boolean isB = v.equals(colorB);
        meta.displayName(MM.full(option.tag() + option.label()
                + (isA ? " <#3DFFA8>[1]" : "") + (isB ? " <#3DFFA8>[2]" : ""))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MM.full("<#3DFFA8>" + Lang.raw("nickcolor.custom-set1")).decoration(TextDecoration.ITALIC, false),
                MM.full("<#E0AAFF>" + Lang.raw("nickcolor.custom-set2")).decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private String nameOf(Player viewer) {
        String nick = nicks.getNick(viewer.getUniqueId());
        return nick != null && !nick.isEmpty() ? nick : viewer.getName();
    }
}
