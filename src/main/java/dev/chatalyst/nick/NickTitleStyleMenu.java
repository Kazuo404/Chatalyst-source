package dev.chatalyst.nick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.TextStyleOption;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.tablist.NametagTitleEditor;
import dev.chatalyst.tablist.NametagTitleManager;
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

/**
 * Estilos (negrita, cursiva, subrayado...) del TITULO del nametag, INDEPENDIENTES del apodo.
 * Reusa {@link TextStyleOption}: detecta los estilos del titulo actual, togglea y re-aplica
 * conservando su color, pasando por {@link NametagTitleEditor} (sanitiza, filtra). Requiere un
 * titulo puesto y {@code chatalyst.nametagtitle}.
 */
public final class NickTitleStyleMenu implements Menu {

    private static final int[] STYLE_SLOTS = {9, 11, 13, 15, 17};
    private static final int PREVIEW_SLOT = 18;
    private static final int BACK_SLOT = 22;
    private static final int RESET_SLOT = 26;

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final Map<Integer, TextStyleOption> slots = new HashMap<>();

    public NickTitleStyleMenu(JavaPlugin plugin, NickManager nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nametag-title.style-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nametag-title.style-title");
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
        inv.setItem(PREVIEW_SLOT, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "nametag-title.style-preview"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nametag-title.gui-current") + ": "
                        + previewValue(viewer))));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        // Mismo item de "quitar estilos" que el menu de estilos del apodo (convencion compartida).
        inv.setItem(RESET_SLOT, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "color.style-clear"),
                List.of(Lang.raw(viewer, "color.style-clear-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new NickTitleMenu(plugin, nicks));
            return;
        }
        if (!player.hasPermission("chatalyst.nametagtitle")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        String current = currentTitle(player);
        if (current == null || current.isBlank()) {
            player.sendMessage(Lang.get(player, "nametag-title.style-none"));
            return;
        }
        if (slot == RESET_SLOT) {
            applyStyles(player, Set.of());
            Menu.open(player, new NickTitleStyleMenu(plugin, nicks));
            return;
        }
        TextStyleOption option = slots.get(slot);
        if (option == null) return;
        Set<TextStyleOption> styles = currentStyles(player);
        boolean enabled = !styles.contains(option);
        if (enabled) styles.add(option); else styles.remove(option);
        applyStyles(player, styles);
        player.sendMessage(Lang.get(player, enabled ? "style.enabled" : "style.disabled",
                "style", option.displayLabel(player)));
        Menu.open(player, new NickTitleStyleMenu(plugin, nicks));
    }

    private Set<TextStyleOption> currentStyles(Player player) {
        return TextStyleOption.detect(currentTitle(player));
    }

    /** Re-aplica el titulo con los estilos dados, conservando su color, via el editor del comando. */
    private void applyStyles(Player player, Set<TextStyleOption> styles) {
        String current = currentTitle(player);
        if (current == null || current.isBlank()) return;
        String plain = MM.strip(current);
        String colorTag = TextStyleOption.detectColorTag(current, ChatColorPalette.options(plugin));
        NametagTitleEditor editor = editor();
        if (editor != null) {
            editor.applyDirect(player, NametagTitleManager.TITLE,
                    TextStyleOption.decorate(colorTag, styles, plain));
        }
    }

    private ItemStack styleItem(TextStyleOption option, Player viewer, boolean active) {
        String sample = MM.strip(currentTitle(viewer));
        if (sample.isBlank()) sample = Lang.raw(viewer, "nametag-title.style-sample");
        List<String> lore = List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "color.gui-preview") + ": "
                        + option.tag() + sample,
                "",
                active ? Lang.raw(viewer, "color.style-enabled")
                        : Lang.raw(viewer, "color.style-click-enable"));
        return MenuItems.item(option.material(),
                (active ? "<#3DFFA8>" : "<#C0B3D9>") + option.tag() + option.displayLabel(viewer),
                lore);
    }

    private String previewValue(Player viewer) {
        String raw = currentTitle(viewer);
        return raw == null || raw.isBlank()
                ? "<#7B68A0>" + Lang.raw(viewer, "nametag-title.gui-none") : raw;
    }

    private String currentTitle(Player player) {
        NametagTitleManager mgr = ChatalystPlugin.get() != null
                ? ChatalystPlugin.get().getNametagTitleManager() : null;
        return mgr != null ? mgr.title(player.getUniqueId()) : "";
    }

    private static NametagTitleEditor editor() {
        ChatalystPlugin plugin = ChatalystPlugin.get();
        return plugin != null ? plugin.getNametagTitleEditor() : null;
    }
}
