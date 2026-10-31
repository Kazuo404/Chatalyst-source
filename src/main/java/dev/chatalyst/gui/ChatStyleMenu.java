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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Toggle menu for chat text decorations such as bold, underline and magic text. */
public final class ChatStyleMenu implements Menu {

    // Fila central espejada alrededor del 13 (con el relleno de cristal se ve alternado).
    private static final int[] STYLE_SLOTS = {9, 11, 13, 15, 17};
    private static final int PREVIEW_SLOT = 18;
    private static final int BACK_SLOT = 22;
    private static final int RESET_SLOT = 26;
    private static final String PERMISSION_PREFIX = "chatalyst.chat.style";

    private final JavaPlugin plugin;
    private final ChatColorManager colors;
    private final Map<Integer, TextStyleOption> slots = new HashMap<>();

    public ChatStyleMenu(JavaPlugin plugin, ChatColorManager colors) {
        this.plugin = plugin;
        this.colors = colors;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("color.style-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "color.style-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        Set<TextStyleOption> selected = colors.getStyles(viewer.getUniqueId());
        TextStyleOption[] options = TextStyleOption.values();
        for (int i = 0; i < options.length && i < STYLE_SLOTS.length; i++) {
            TextStyleOption option = options[i];
            int slot = STYLE_SLOTS[i];
            inv.setItem(slot, styleItem(option, viewer, selected.contains(option)));
            slots.put(slot, option);
        }
        inv.setItem(PREVIEW_SLOT, previewItem(viewer));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(RESET_SLOT, MenuItems.item(Material.BARRIER, Lang.raw(viewer, "color.style-clear"),
                List.of(Lang.raw(viewer, "color.style-clear-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.chatcolor")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK_SLOT) {
            // Los estilos se abren desde el hub (igual que en el apodo): volver lleva alli.
            Menu.open(player, new ChatColorHub(plugin, colors));
            return;
        }
        if (slot == RESET_SLOT) {
            if (!colors.clearStyles(player.getUniqueId())) {
                storageError(player);
                return;
            }
            player.sendMessage(Lang.get(player, "style.reset"));
            Menu.open(player, new ChatStyleMenu(plugin, colors));
            return;
        }
        TextStyleOption option = slots.get(slot);
        if (option == null) {
            return;
        }
        if (!option.canUse(player, PERMISSION_PREFIX)) {
            player.sendMessage(Lang.get(player, "style.no-permission"));
            return;
        }
        boolean enabled = !colors.getStyles(player.getUniqueId()).contains(option);
        if (!colors.setStyle(player.getUniqueId(), option, enabled)) {
            storageError(player);
            return;
        }
        player.sendMessage(Lang.get(player, enabled ? "style.enabled" : "style.disabled",
                "style", option.displayLabel(player)));
        Menu.open(player, new ChatStyleMenu(plugin, colors));
    }

    private void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }

    private ItemStack styleItem(TextStyleOption option, Player viewer, boolean active) {
        boolean allowed = option.canUse(viewer, PERMISSION_PREFIX);
        String name = (active ? "<#3DFFA8>" : "<#C0B3D9>")
                + option.tag() + option.displayLabel(viewer);
        List<String> lore = List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": " + option.tag() + "Aa Bb Cc 123",
                "",
                !allowed ? "<#FF4D6D>" + Lang.raw(viewer, "color.gui-locked")
                        : active ? Lang.raw(viewer, "color.style-enabled")
                        : Lang.raw(viewer, "color.style-click-enable"),
                "<#7B68A0>" + PERMISSION_PREFIX + "." + option.id()
        );
        return MenuItems.item(option.material(), name, lore);
    }

    private ItemStack previewItem(Player viewer) {
        String preview = colors.getColorTag(viewer.getUniqueId()) + "Aa Bb Cc 123";
        return MenuItems.item(Material.NAME_TAG, Lang.raw(viewer, "color.style-preview-title"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "color.style-chat") + ": " + preview));
    }

}
