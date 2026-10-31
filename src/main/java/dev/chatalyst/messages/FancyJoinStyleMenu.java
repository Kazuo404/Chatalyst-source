package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Elige el estilo visual de entrada de un tier, con preview al clicar. Sub-GUI del tier editor. */
public final class FancyJoinStyleMenu implements Menu {

    // 16 estilos en filas centradas (13/22/31/40), como el resto de menus simetricos.
    private static final int[] SLOTS = {
            11, 12, 13, 14, 15,
            20, 21, 22, 23, 24,
            29, 30, 31, 32, 33,
            40};
    private static final int BACK = 49;

    private final ChatalystPlugin plugin;
    private final FancyJoinMenuStore store;
    private final String tierKey;

    public FancyJoinStyleMenu(ChatalystPlugin plugin, String tierKey) {
        this.plugin = plugin;
        this.store = new FancyJoinMenuStore(plugin);
        this.tierKey = tierKey;
    }

    private String path() {
        return "Join.Fancy.Tiers." + tierKey + ".Style";
    }

    @Override
    public Component title() {
        return Lang.get("fancy-join-style.title", "tier", tierKey);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "fancy-join-style.title", "tier", tierKey);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        FancyJoinStyle current = FancyJoinStyle.byId(store.load().getString(path(), "none"));
        FancyJoinStyle[] styles = FancyJoinStyle.values();
        for (int i = 0; i < styles.length && i < SLOTS.length; i++) {
            inv.setItem(SLOTS[i], styleItem(viewer, styles[i], styles[i] == current));
        }
        inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    private ItemStack styleItem(Player viewer, FancyJoinStyle style, boolean selected) {
        String name = (selected ? "<#3DFFA8>" : "<#FBF2FF>")
                + Lang.raw(viewer, "fancy-join-style.name." + style.id());
        List<String> lore = List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "fancy-join-style.desc." + style.id()),
                selected ? "<#3DFFA8>" + Lang.raw(viewer, "fancy-join-style.selected")
                        : "<#E0AAFF>" + Lang.raw(viewer, "fancy-join-style.click-select"));
        ItemStack item = MenuItems.item(style.icon(), name, lore);
        if (selected) {
            item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return item;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.admin")) return;
        if (slot == BACK) {
            Menu.open(player, new FancyJoinTierMenu(plugin, tierKey));
            return;
        }
        int index = indexOf(slot);
        if (index < 0 || index >= FancyJoinStyle.values().length) return;
        FancyJoinStyle style = FancyJoinStyle.values()[index];
        if (store.set(player, path(), style.id())) {
            player.sendMessage(Lang.get(player, "fancy-join-style.set",
                    "style", Lang.raw(player, "fancy-join-style.name." + style.id())));
            if (plugin.getFancyJoin() != null) plugin.getFancyJoin().previewStyle(player, style.id());
        }
        Menu.open(player, this);
    }

    private static int indexOf(int slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) return i;
        }
        return -1;
    }
}
