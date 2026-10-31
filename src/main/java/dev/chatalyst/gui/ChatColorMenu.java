package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu para elegir el color/gradiente del chat. Usa la paleta compartida con el comando y con
 * el apodo. Con una {@link ChatColorPalette.Category} muestra solo esa seccion y el hueco de
 * estilos pasa a ser "volver al hub" ({@link ChatColorHub}), igual que la paleta del apodo; sin
 * categoria es la rejilla completa de siempre.
 */
public final class ChatColorMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    // Fila inferior centrada y simetrica: styles(o volver) / current / reset alrededor del 49.
    private static final int STYLE_SLOT = 47;
    private static final int CURRENT_SLOT = 49;
    private static final int RESET_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final ChatColorManager colors;
    /** Seccion que se muestra, o null para la paleta completa. */
    private final ChatColorPalette.Category category;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();
    private int page;

    public ChatColorMenu(JavaPlugin plugin, ChatColorManager colors) {
        this(plugin, colors, null, 0);
    }

    public ChatColorMenu(JavaPlugin plugin, ChatColorManager colors,
                         ChatColorPalette.Category category) {
        this(plugin, colors, category, 0);
    }

    private ChatColorMenu(JavaPlugin plugin, ChatColorManager colors,
                          ChatColorPalette.Category category, int page) {
        this.plugin = plugin;
        this.colors = colors;
        this.category = category;
        this.page = Math.max(0, page);
    }

    private List<ChatColorPalette.Option> options() {
        return category == null ? ChatColorPalette.options(plugin)
                : ChatColorPalette.options(plugin, category);
    }

    /** Mismas claves que las secciones del apodo: el texto ("Colores fuertes"...) sirve tal cual. */
    static String titleKey(ChatColorPalette.Category category) {
        if (category == null) return "color.gui-title";
        return switch (category) {
            case PASTEL -> "nickcolor.cat-pastel";
            case GRADIENT -> "nickcolor.cat-gradients";
            case STRONG -> "nickcolor.cat-strong";
        };
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(titleKey(category)));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey(category));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<ChatColorPalette.Option> options = options();
        String selected = colors.getBaseColorTag(viewer.getUniqueId());
        int pages = Math.max(1, (options.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int visible = Math.min(PAGE_SIZE, options.size() - start);
        for (int index = start; index < start + visible; index++) {
            ChatColorPalette.Option option = options.get(index);
            int slot = optionSlot(index - start, visible);
            inv.setItem(slot, icon(option, viewer, selected));
            slots.put(slot, option);
        }
        inv.setItem(PREVIOUS_SLOT, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(STYLE_SLOT, category == null ? styleItem(viewer) : MenuNavigation.back(viewer));
        // El "actual" se busca en la paleta COMPLETA: un color fuerte elegido sigue siendo el
        // actual aunque ahora se este mirando la seccion de gradientes.
        inv.setItem(CURRENT_SLOT, currentItem(plugin, colors, viewer, selected));
        inv.setItem(RESET_SLOT, MenuItems.item(Material.BARRIER, Lang.get(viewer, "color.reset-item"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.gui-reset-lore"))));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.chatcolor")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new ChatColorMenu(plugin, colors, category, page - 1));
            return;
        }
        int pages = Math.max(1, (options().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT_SLOT && page + 1 < pages) {
            Menu.open(player, new ChatColorMenu(plugin, colors, category, page + 1));
            return;
        }
        if (slot == RESET_SLOT) {
            // Restablecer de verdad: color Y estilos. Antes solo quitaba el color y la
            // negrita/cursiva se quedaban puestas, asi que el chat seguia sin verse "normal"
            // y no habia forma obvia de dejarlo limpio.
            if (!colors.clearColor(player.getUniqueId())
                    || !colors.clearStyles(player.getUniqueId())) {
                storageError(player);
                return;
            }
            player.sendMessage(Lang.get(player, "color.reset"));
            Menu.open(player, new ChatColorMenu(plugin, colors, category, page));
            return;
        }
        if (slot == STYLE_SLOT) {
            Menu.open(player, category == null ? new ChatStyleMenu(plugin, colors)
                    : new ChatColorHub(plugin, colors));
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option == null) {
            return;
        }
        if (!option.canUse(player)) {
            player.sendMessage(Lang.get(player, "color.no-permission"));
            return;
        }
        if (!colors.setColor(player.getUniqueId(), option.tag())) {
            storageError(player);
            return;
        }
        player.sendMessage(Lang.get(player, "color.selected", "color", option.displayLabel(player)));
        player.closeInventory();
    }

    private void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }

    private ItemStack icon(ChatColorPalette.Option option, Player viewer, String selected) {
        ItemStack item = new ItemStack(option.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather && option.leatherColor() != null) {
            leather.setColor(option.leatherColor());
            meta = leather;
        }
        boolean allowed = option.canUse(viewer);
        boolean active = option.tag().equals(selected);
        meta.displayName(MM.full(option.tag() + option.displayLabel(viewer)).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore(viewer, option, allowed, active));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(Player viewer, ChatColorPalette.Option option, boolean allowed, boolean active) {
        String kind = option.kind() == ChatColorPalette.Kind.GRADIENT
                ? Lang.raw(viewer, "color.gui-gradient") : Lang.raw(viewer, "color.gui-color");
        List<String> lines = new java.util.ArrayList<>();
        lines.add("<#7B68A0>" + kind + " · " + option.id());
        lines.add("<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": " + option.tag() + "Aa Bb Cc 123");
        lines.add("");
        if (!allowed) {
            lines.add("<#FF4D6D>" + Lang.raw(viewer, "color.gui-locked"));
            lines.add("<#7B68A0>" + option.permissionHint());
        } else if (active) {
            lines.add("<#3DFFA8>" + Lang.raw(viewer, "color.gui-selected"));
        } else {
            lines.add("<#E0AAFF>" + Lang.raw(viewer, "color.gui-click-select"));
        }
        return lines.stream()
                .map(line -> MM.full(line).decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * El color que el jugador tiene AHORA. Antes solo reconocia tags de la paleta, asi que un
     * gradiente propio ({@code <gradient:#AA0000:#FF5555>}) se anunciaba como "por defecto" aunque
     * el chat saliera coloreado. Ahora: opcion de la paleta, gradiente propio o, como mucho, un
     * color que ya no esta en la config (se previsualiza si es un tag valido).
     */
    static ItemStack currentItem(JavaPlugin plugin, ChatColorManager colors, Player viewer,
                                 String selected) {
        String styleTag = colors.getStyleTag(viewer.getUniqueId());
        String label;
        String preview;
        Current current = describe(selected, ChatColorPalette.options(plugin));
        switch (current.kind()) {
            case PALETTE -> {
                label = current.option().displayLabel(viewer);
                preview = current.tag() + styleTag + "Aa Bb Cc 123";
            }
            case CUSTOM -> {
                label = Lang.raw(viewer, "color.gui-custom");
                preview = current.tag() + styleTag + "Aa Bb Cc 123";
            }
            default -> {
                label = Lang.raw(viewer, "color.gui-default");
                preview = styleTag + "<#C0B3D9>" + Lang.raw(viewer, "color.gui-default-format");
            }
        }
        return MenuItems.item(Material.NAME_TAG,
                MM.full("<gradient:#240046:#9D4EDD:#E0AAFF>" + Lang.raw(viewer, "color.gui-current") + ": <#FBF2FF>" + label),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": " + preview));
    }

    enum CurrentKind { DEFAULT, PALETTE, CUSTOM }

    /** Que es el tag guardado; {@code tag} es lo que se puede pintar sin riesgo ("" si nada). */
    record Current(CurrentKind kind, ChatColorPalette.Option option, String tag) {
    }

    static Current describe(String selected, List<ChatColorPalette.Option> options) {
        if (selected == null || selected.isEmpty()) {
            return new Current(CurrentKind.DEFAULT, null, "");
        }
        ChatColorPalette.Option option = ChatColorPalette.byTag(options, selected);
        if (option != null) return new Current(CurrentKind.PALETTE, option, option.tag());
        String safe = ChatCustomGradient.previewTag(selected);
        // Un tag guardado que no se puede pintar con seguridad no se enseña, pero tampoco se
        // dice "por defecto": el jugador SI tiene algo puesto, y el boton de restablecer lo quita.
        return new Current(CurrentKind.CUSTOM, null, safe);
    }

    private ItemStack styleItem(Player viewer) {
        String styleTag = colors.getStyleTag(viewer.getUniqueId());
        String label = styleTag.isEmpty()
                ? Lang.raw(viewer, "color.gui-none")
                : styleNames(viewer, colors.getStyles(viewer.getUniqueId()));
        String preview = colors.getColorTag(viewer.getUniqueId()) + "Aa Bb Cc 123";
        return MenuItems.item(Material.ENCHANTED_BOOK,
                MM.full("<gradient:#240046:#9D4EDD:#E0AAFF>" + Lang.raw(viewer, "color.gui-styles") + ": <#FBF2FF>" + label),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": " + preview,
                        "<#E0AAFF>" + Lang.raw(viewer, "color.gui-edit-effects")));
    }

    private String styleNames(Player viewer, java.util.Set<TextStyleOption> styles) {
        if (styles.isEmpty()) {
            return Lang.raw(viewer, "color.gui-none");
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (TextStyleOption option : TextStyleOption.values()) {
            if (styles.contains(option)) {
                names.add(option.displayLabel(viewer));
            }
        }
        return String.join(", ", names);
    }


    private int optionSlot(int index, int total) {
        // Filas llenas de lado a lado y la ultima centrada de verdad (con un numero par se deja
        // libre la columna del medio): la paleta queda simetrica con cualquier cantidad.
        return dev.chatalyst.gui.MenuLayout.slots(total, 0)[index];
    }

}
