package dev.chatalyst.gui;

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
 * Constructor de gradiente propio para el CHAT, gemelo del del apodo: el jugador elige dos
 * colores planos de la paleta (clic IZQUIERDO = color 1, clic DERECHO = color 2) y "aplicar"
 * guarda {@code <gradient:A:B>} como su color de chat. El estado viaja por el constructor.
 *
 * <p>Ademas del permiso del gradiente propio, cada extremo respeta el permiso de SU color
 * ({@link ChatColorPalette.Option#canUse}): sin eso, este menu seria una puerta trasera para
 * usar en el chat colores de rango que el jugador no tiene.
 */
public final class ChatGradientBuilderMenu implements Menu {

    /** El mismo nodo que el gradiente propio del apodo. */
    public static final String PERMISSION = "chatalyst.gradient.custom";

    private static final int PALETTE_SIZE = 45;
    private static final int BACK = 45;
    private static final int SLOT_A = 47;
    private static final int PREVIEW = 49;
    private static final int SLOT_B = 51;
    private static final int APPLY = 53;

    private final JavaPlugin plugin;
    private final ChatColorManager colors;
    private final String colorA; // extremo validado ("#FF6B9B" o "red"), o null
    private final String colorB;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();

    public ChatGradientBuilderMenu(JavaPlugin plugin, ChatColorManager colors) {
        this(plugin, colors, null, null);
    }

    private ChatGradientBuilderMenu(JavaPlugin plugin, ChatColorManager colors,
                                    String colorA, String colorB) {
        this.plugin = plugin;
        this.colors = colors;
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

    /** Colores planos de la paleta que sirven como extremo (los gradientes no). */
    private List<ChatColorPalette.Option> endpoints() {
        List<ChatColorPalette.Option> out = new ArrayList<>();
        for (ChatColorPalette.Option o : ChatColorPalette.options(plugin)) {
            if (o.kind() == ChatColorPalette.Kind.COLOR
                    && ChatCustomGradient.valueOf(o.tag()) != null) {
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<ChatColorPalette.Option> palette = endpoints();
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.min(palette.size(), PALETTE_SIZE), 0);
        for (int i = 0; i < layout.length; i++) {
            inv.setItem(layout[i], icon(palette.get(i), viewer));
            slots.put(layout[i], palette.get(i));
        }
        String tag = ChatCustomGradient.tag(colorA, colorB);
        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(SLOT_A, endpointItem(viewer, colorA, "nickcolor.custom-color1"));
        inv.setItem(SLOT_B, endpointItem(viewer, colorB, "nickcolor.custom-color2"));
        inv.setItem(PREVIEW, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "nickcolor.custom-preview"),
                List.of(tag != null ? tag + "Aa Bb Cc 123"
                        : "<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-pick"))));
        // Siempre algo en APPLY (espejo de BACK): gris hasta que hay dos colores elegidos.
        inv.setItem(APPLY, tag != null ? MenuItems.item(Material.LIME_CONCRETE,
                Lang.raw(viewer, "nickcolor.custom-apply"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": "
                        + tag + "Aa Bb Cc 123"))
                : MenuNavigation.unavailable(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.chatcolor")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            Menu.open(player, new ChatColorHub(plugin, colors));
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == SLOT_A) { // clic en un extremo = limpiarlo
            Menu.open(player, new ChatGradientBuilderMenu(plugin, colors, null, colorB));
            return;
        }
        if (slot == SLOT_B) {
            Menu.open(player, new ChatGradientBuilderMenu(plugin, colors, colorA, null));
            return;
        }
        if (slot == APPLY) {
            String tag = ChatCustomGradient.tag(colorA, colorB);
            if (tag == null) return;
            // Se re-comprueba al aplicar: el permiso de un color pudo retirarse mientras tanto.
            if (!endpointsAllowed(player)) {
                player.sendMessage(Lang.get(player, "color.no-permission"));
                return;
            }
            if (!colors.setColor(player.getUniqueId(), tag)) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return;
            }
            player.sendMessage(Lang.get(player, "color.custom-done"));
            player.closeInventory();
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option == null) return;
        if (!option.canUse(player)) {
            player.sendMessage(Lang.get(player, "color.no-permission"));
            return;
        }
        String value = ChatCustomGradient.valueOf(option.tag());
        if (value == null) return;
        if (click.isRightClick()) {
            Menu.open(player, new ChatGradientBuilderMenu(plugin, colors, colorA, value));
        } else {
            Menu.open(player, new ChatGradientBuilderMenu(plugin, colors, value, colorB));
        }
    }

    /** Ambos extremos siguen siendo colores que el jugador puede usar. */
    private boolean endpointsAllowed(Player player) {
        boolean a = false;
        boolean b = false;
        for (ChatColorPalette.Option o : endpoints()) {
            String v = ChatCustomGradient.valueOf(o.tag());
            if (!o.canUse(player)) continue;
            if (v.equals(colorA)) a = true;
            if (v.equals(colorB)) b = true;
        }
        return a && b;
    }

    private ItemStack endpointItem(Player viewer, String value, String nameKey) {
        boolean set = value != null;
        // value ya viene validado por ChatCustomGradient (hex o nombre), nunca es texto libre.
        String preview = set ? "<" + value + ">" + Lang.raw(viewer, "nickcolor.custom-chosen")
                : "<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-empty");
        return MenuItems.item(set ? Material.LEATHER_CHESTPLATE : Material.GRAY_DYE,
                Lang.raw(viewer, nameKey),
                List.of("<#C0B3D9>" + preview,
                        "<#7B68A0>" + Lang.raw(viewer, "nickcolor.custom-clear")));
    }

    private ItemStack icon(ChatColorPalette.Option option, Player viewer) {
        ItemStack item = new ItemStack(option.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather && option.leatherColor() != null) {
            leather.setColor(option.leatherColor());
            meta = leather;
        }
        String v = ChatCustomGradient.valueOf(option.tag());
        boolean isA = v != null && v.equals(colorA);
        boolean isB = v != null && v.equals(colorB);
        meta.displayName(MM.full(option.tag() + option.displayLabel(viewer)
                        + (isA ? " <#3DFFA8>[1]" : "") + (isB ? " <#3DFFA8>[2]" : ""))
                .decoration(TextDecoration.ITALIC, false));
        List<String> lore = new ArrayList<>();
        if (option.canUse(viewer)) {
            lore.add("<#3DFFA8>" + Lang.raw(viewer, "nickcolor.custom-set1"));
            lore.add("<#E0AAFF>" + Lang.raw(viewer, "nickcolor.custom-set2"));
        } else {
            lore.add("<#FF4D6D>" + Lang.raw(viewer, "color.gui-locked"));
            lore.add("<#7B68A0>" + option.permissionHint());
        }
        meta.lore(lore.stream()
                .map(line -> MM.full(line).decoration(TextDecoration.ITALIC, false))
                .toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }
}
