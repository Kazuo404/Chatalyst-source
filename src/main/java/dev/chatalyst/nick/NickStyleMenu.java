package dev.chatalyst.nick;

import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.TextStyleOption;
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

/** Toggle menu for nickname text decorations. */
public final class NickStyleMenu implements Menu {

    // Fila central espejada alrededor del 13 (con el relleno de cristal se ve alternado).
    private static final int[] STYLE_SLOTS = {9, 11, 13, 15, 17};
    private static final int PREVIEW_SLOT = 18;
    private static final int BACK_SLOT = 22;
    private static final int RESET_SLOT = 26;
    private static final String PERMISSION_PREFIX = "chatalyst.nick.style";

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final Map<Integer, TextStyleOption> slots = new HashMap<>();

    public NickStyleMenu(JavaPlugin plugin, NickManager nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nickcolor.style-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nickcolor.style-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        Set<TextStyleOption> selected = currentStyles(viewer);
        TextStyleOption[] options = TextStyleOption.values();
        for (int i = 0; i < options.length && i < STYLE_SLOTS.length; i++) {
            TextStyleOption option = options[i];
            int slot = STYLE_SLOTS[i];
            inv.setItem(slot, styleItem(option, viewer, selected.contains(option)));
            slots.put(slot, option);
        }
        inv.setItem(PREVIEW_SLOT, dev.chatalyst.gui.MenuItems.item(Material.NAME_TAG, Lang.raw(viewer, "color.style-preview-title"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.style-nick") + ": " + nameOf(viewer))));
        inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
        inv.setItem(RESET_SLOT, dev.chatalyst.gui.MenuItems.item(Material.BARRIER, Lang.raw(viewer, "color.style-clear"),
                List.of(Lang.raw(viewer, "color.style-clear-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
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
        if (slot == BACK_SLOT) {
            Menu.open(player, new NickColorHub(plugin, nicks));
            return;
        }
        if (slot == RESET_SLOT) {
            applyStyles(player, Set.of());
            player.sendMessage(Lang.get(player, "style.reset"));
            Menu.open(player, new NickStyleMenu(plugin, nicks));
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
        Set<TextStyleOption> styles = currentStyles(player);
        boolean enabled = !styles.contains(option);
        if (enabled) {
            styles.add(option);
        } else {
            styles.remove(option);
        }
        if (!applyStyles(player, styles)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        player.sendMessage(Lang.get(player, enabled ? "style.enabled" : "style.disabled",
                "style", option.displayLabel(player)));
        Menu.open(player, new NickStyleMenu(plugin, nicks));
    }

    private Set<TextStyleOption> currentStyles(Player player) {
        return TextStyleOption.detect(nameOf(player));
    }

    private boolean applyStyles(Player player, Set<TextStyleOption> styles) {
        String current = nicks.getNick(player.getUniqueId());
        String plain = current != null && !current.isEmpty() ? MM.strip(current) : player.getName();
        String colorTag = TextStyleOption.detectColorTag(current, ChatColorPalette.options(plugin));
        if (colorTag.isEmpty() && styles.isEmpty() && plain.equalsIgnoreCase(player.getName())) {
            return nicks.removeNick(player.getUniqueId());
        }
        return nicks.setNick(player.getUniqueId(),
                TextStyleOption.decorate(colorTag, styles, plain));
    }

    private String nameOf(Player viewer) {
        String nick = nicks.getNick(viewer.getUniqueId());
        return nick != null && !nick.isEmpty() ? nick : viewer.getName();
    }

    private ItemStack styleItem(TextStyleOption option, Player viewer, boolean active) {
        boolean allowed = option.canUse(viewer, PERMISSION_PREFIX);
        List<String> lore = List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": " + option.tag() + MM.strip(nameOf(viewer)),
                "",
                !allowed ? "<#FF4D6D>" + Lang.raw(viewer, "color.gui-locked")
                        : active ? Lang.raw(viewer, "color.style-enabled")
                        : Lang.raw(viewer, "color.style-click-enable"),
                "<#7B68A0>" + PERMISSION_PREFIX + "." + option.id()
        );
        return dev.chatalyst.gui.MenuItems.item(option.material(),
                (active ? "<#3DFFA8>" : "<#C0B3D9>")
                        + option.tag() + option.displayLabel(viewer), lore);
    }

}
