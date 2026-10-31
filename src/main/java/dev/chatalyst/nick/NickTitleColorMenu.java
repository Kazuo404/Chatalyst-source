package dev.chatalyst.nick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.TextStyleOption;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.tablist.NametagTitleEditor;
import dev.chatalyst.tablist.NametagTitleManager;
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
 * Elige un color o gradiente para el TITULO del nametag (mismo estilo que la paleta del apodo).
 * Re-envuelve el texto actual del titulo con el color elegido conservando sus estilos, y lo
 * aplica pasando por {@link NametagTitleEditor}/comando (sanitizado, permiso de color, reglas,
 * filtro de insultos). Requiere {@code chatalyst.nametagtitle.color}.
 */
public final class NickTitleColorMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_SLOT = 47;
    private static final int PREVIEW_SLOT = 49;
    private static final int RESET_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();
    private int page;

    public NickTitleColorMenu(JavaPlugin plugin, NickManager nicks) {
        this(plugin, nicks, 0);
    }

    private NickTitleColorMenu(JavaPlugin plugin, NickManager nicks, int page) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nametag-title.color-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nametag-title.color-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<ChatColorPalette.Option> options = ChatColorPalette.options(plugin);
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
                Lang.get(viewer, "nametag-title.color-reset"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nametag-title.color-reset-lore"))));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new NickTitleMenu(plugin, nicks));
            return;
        }
        if (!player.hasPermission("chatalyst.nametagtitle.color")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new NickTitleColorMenu(plugin, nicks, page - 1));
            return;
        }
        int pages = Math.max(1, (ChatColorPalette.options(plugin).size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT_SLOT && page + 1 < pages) {
            Menu.open(player, new NickTitleColorMenu(plugin, nicks, page + 1));
            return;
        }
        String currentPlain = MM.strip(currentTitle(player));
        if (slot == RESET_SLOT) {
            if (currentPlain.isBlank()) return;
            applyToTitle(player, currentPlain); // texto sin color/estilo
            Menu.open(player, new NickTitleMenu(plugin, nicks));
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option == null) return;
        if (currentPlain.isBlank()) {
            player.sendMessage(Lang.get(player, "nametag-title.color-none"));
            return;
        }
        if (!option.canUse(player)) {
            player.sendMessage(Lang.get(player, "color.no-permission"));
            return;
        }
        Set<TextStyleOption> styles = TextStyleOption.detect(currentTitle(player));
        applyToTitle(player, TextStyleOption.decorate(option.tag(), styles, currentPlain));
        Menu.open(player, new NickTitleMenu(plugin, nicks));
    }

    /** Aplica el valor ya compuesto al titulo pasando por las defensas del comando/editor. */
    private void applyToTitle(Player player, String composed) {
        NametagTitleEditor editor = ChatalystPlugin.get() != null
                ? ChatalystPlugin.get().getNametagTitleEditor() : null;
        if (editor != null) editor.applyDirect(player, NametagTitleManager.TITLE, composed);
    }

    private String currentTitle(Player player) {
        NametagTitleManager mgr = ChatalystPlugin.get() != null
                ? ChatalystPlugin.get().getNametagTitleManager() : null;
        return mgr != null ? mgr.title(player.getUniqueId()) : "";
    }

    private ItemStack icon(ChatColorPalette.Option option, Player viewer) {
        ItemStack item = new ItemStack(option.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather && option.leatherColor() != null) {
            leather.setColor(option.leatherColor());
            meta = leather;
        }
        boolean allowed = option.canUse(viewer);
        String sample = MM.strip(currentTitle(viewer));
        if (sample.isBlank()) sample = Lang.raw(viewer, "nametag-title.color-sample");
        meta.displayName(MM.full(option.tag() + option.displayLabel(viewer))
                .decoration(TextDecoration.ITALIC, false));
        List<String> lines = new ArrayList<>();
        lines.add("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-preview") + ": " + option.tag() + sample);
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
        String raw = currentTitle(viewer);
        String shown = raw == null || raw.isBlank()
                ? "<#7B68A0>" + Lang.raw(viewer, "nametag-title.gui-none") : raw;
        return dev.chatalyst.gui.MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "nametag-title.color-preview"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-now") + ": " + shown));
    }

    private int optionSlot(int index, int total) {
        // Filas llenas de lado a lado y la ultima centrada de verdad (con un numero par se deja
        // libre la columna del medio): la paleta queda simetrica con cualquier cantidad.
        return dev.chatalyst.gui.MenuLayout.slots(total, 0)[index];
    }
}
