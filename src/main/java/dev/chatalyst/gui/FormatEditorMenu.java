package dev.chatalyst.gui;

import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.format.ChatFormat;
import dev.chatalyst.format.FormatPart;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu que lista las partes de un formato. Al hacer click en una, se pide por chat el nuevo
 * texto de su Message.
 */
public final class FormatEditorMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int CLOSE = 49;
    private static final int NEXT = 53;

    private final ConfigManager config;
    private final FormatEditManager editor;
    private final String formatName;
    private final Map<Integer, String> slots = new HashMap<>();
    private int page;

    public FormatEditorMenu(ConfigManager config, FormatEditManager editor, String formatName) {
        this(config, editor, formatName, 0);
    }

    private FormatEditorMenu(ConfigManager config, FormatEditManager editor, String formatName,
                             int page) {
        this.config = config;
        this.editor = editor;
        this.formatName = formatName;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("editor.gui-title", "format", formatName);
    }

    @Override
    public Component title(org.bukkit.entity.Player viewer) {
        return Lang.get(viewer, "editor.gui-title", "format", formatName);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        ChatFormat format = config.getFormat(formatName);
        int pages = Math.max(1, (format.parts.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(format.parts.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            int slot = layout[index - start];
            FormatPart part = format.parts.get(index);
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(MM.full("<#FBF2FF>" + part.id).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line(part.message.isEmpty()
                    ? Lang.raw(viewer, "editor.empty") : part.message));
            lore.add(Component.empty());
            lore.add(line(Lang.raw(viewer, "editor.click-edit")));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
            slots.put(slot, part.id);
        }
        inv.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
        inv.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.editor")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new FormatEditorMenu(config, editor, formatName, page - 1));
            return;
        }
        if (slot == NEXT) {
            ChatFormat format = config.getFormat(formatName);
            int pages = Math.max(1, (format.parts.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) {
                Menu.open(player, new FormatEditorMenu(config, editor, formatName, page + 1));
            }
            return;
        }
        String partId = slots.get(slot);
        if (partId == null) return;
        editor.startEdit(player.getUniqueId(), formatName, partId);
        player.closeInventory();
        player.sendMessage(Lang.get(player, "editor.prompt", "part", partId));
    }

    private Component line(String mini) {
        return MM.full(mini).decoration(TextDecoration.ITALIC, false);
    }
}
