package dev.chatalyst.nick;

import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.TextStyleOption;
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
import java.util.Set;

/**
 * Paleta de UNA categoria (fuertes / pastel / gradientes) para pintar el apodo. Se abre desde
 * {@link NickColorHub}. Al elegir envuelve el texto plano del nick con el color y lo guarda;
 * "volver" regresa al hub. La logica de aplicar color se comparte con el resto del hub via
 * {@link #apply}.
 */
public final class NickColorMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_SLOT = 47;
    private static final int PREVIEW_SLOT = 49;
    private static final int RESET_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final ChatColorPalette.Category category;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();
    private int page;

    public NickColorMenu(JavaPlugin plugin, NickManager nicks, ChatColorPalette.Category category) {
        this(plugin, nicks, category, 0);
    }

    private NickColorMenu(JavaPlugin plugin, NickManager nicks,
                          ChatColorPalette.Category category, int page) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.category = category;
        this.page = Math.max(0, page);
    }

    private String titleKey() {
        return switch (category) {
            case PASTEL -> "nickcolor.cat-pastel";
            case GRADIENT -> "nickcolor.cat-gradients";
            default -> "nickcolor.cat-strong";
        };
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(titleKey()));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<ChatColorPalette.Option> options = ChatColorPalette.options(plugin, category);
        int pages = Math.max(1, (options.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int visible = Math.min(PAGE_SIZE, options.size() - start);
        for (int index = start; index < start + visible; index++) {
            ChatColorPalette.Option option = options.get(index);
            int slot = optionSlot(index - start, visible);
            inv.setItem(slot, icon(option, viewer));
            slots.put(slot, option);
        }
        inv.setItem(PREVIOUS_SLOT, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(PREVIEW_SLOT, preview(viewer));
        inv.setItem(RESET_SLOT, dev.chatalyst.gui.MenuItems.item(Material.BARRIER,
                Lang.get(viewer, "nickcolor.reset-item"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-reset-lore"))));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new NickColorHub(plugin, nicks));
            return;
        }
        if (!player.hasPermission("chatalyst.nick.color")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "nick.anonymous-self"));
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new NickColorMenu(plugin, nicks, category, page - 1));
            return;
        }
        int pages = Math.max(1,
                (ChatColorPalette.options(plugin, category).size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT_SLOT && page + 1 < pages) {
            Menu.open(player, new NickColorMenu(plugin, nicks, category, page + 1));
            return;
        }
        if (slot == RESET_SLOT) {
            apply(nicks, player, "");
            player.sendMessage(Lang.get(player, "nickcolor.reset"));
            player.closeInventory();
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option == null) return;
        if (!option.canUse(player)) {
            player.sendMessage(Lang.get(player, "color.no-permission"));
            return;
        }
        if (!apply(nicks, player, option.tag())) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        player.sendMessage(Lang.get(player, "nickcolor.selected",
                "color", option.displayLabel(player)));
        player.closeInventory();
    }

    /**
     * Envuelve el texto plano del nick con {@code tag} (color o gradiente), conservando sus
     * estilos; con {@code tag} vacio RESTABLECE (quita color y estilos). Compartido por la
     * paleta y el constructor de gradiente.
     */
    public static boolean apply(NickManager nicks, Player player, String tag) {
        String current = nicks.getNick(player.getUniqueId());
        String plain = current != null && !current.isEmpty() ? MM.strip(current) : player.getName();
        Set<TextStyleOption> styles = TextStyleOption.detect(current);
        if (tag.isEmpty()) {
            if (plain.equalsIgnoreCase(player.getName())) {
                return nicks.removeNick(player.getUniqueId());
            }
            // Escapado, como hace decorate(): el texto plano de un apodo puede llevar un "<...>"
            // literal, y guardado tal cual volveria a leerse como etiqueta al pintarlo.
            return nicks.setNick(player.getUniqueId(), MM.escape(plain));
        }
        return nicks.setNick(player.getUniqueId(), TextStyleOption.decorate(tag, styles, plain));
    }

    private ItemStack icon(ChatColorPalette.Option option, Player viewer) {
        ItemStack item = new ItemStack(option.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather && option.leatherColor() != null) {
            leather.setColor(option.leatherColor());
            meta = leather;
        }
        boolean allowed = option.canUse(viewer);
        meta.displayName(MM.full(option.tag() + option.displayLabel(viewer))
                .decoration(TextDecoration.ITALIC, false));
        List<String> lines = new ArrayList<>();
        lines.add("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-preview") + ": "
                + option.tag() + MM.strip(nameOf(viewer)));
        lines.add("");
        lines.add(allowed ? "<#E0AAFF>" + Lang.raw(viewer, "nickcolor.gui-apply")
                : "<#FF4D6D>" + Lang.raw(viewer, "nickcolor.gui-locked")
                        + " <#7B68A0>" + option.permissionHint());
        meta.lore(lines.stream().map(l -> MM.full(l).decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack preview(Player viewer) {
        return dev.chatalyst.gui.MenuItems.item(Material.NAME_TAG,
                MM.full(Lang.raw(viewer, "nickcolor.gui-your-nick")),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-now") + ": " + nameOf(viewer)));
    }

    private String nameOf(Player viewer) {
        String nick = nicks.getNick(viewer.getUniqueId());
        return nick != null && !nick.isEmpty() ? nick : viewer.getName();
    }

    private int optionSlot(int index, int total) {
        // Filas llenas de lado a lado y la ultima centrada de verdad (con un numero par se deja
        // libre la columna del medio): la paleta queda simetrica con cualquier cantidad.
        return dev.chatalyst.gui.MenuLayout.slots(total, 0)[index];
    }
}
