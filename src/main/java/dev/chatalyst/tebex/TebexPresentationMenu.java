package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.AdminMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Paginated owner GUI for the global purchase presentation and every Tebex-enabled Rank ID. */
public final class TebexPresentationMenu implements Menu {

    private static final int[] RANK_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int GLOBAL = 4;
    private static final int BACK = 45;
    private static final int PAGE = 49;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TebexPresentationService presentations;
    private final int page;
    private List<String> shown = List.of();

    public TebexPresentationMenu(ChatalystPlugin plugin,
                                 TebexPresentationService presentations,
                                 int page) {
        this.plugin = plugin;
        this.presentations = presentations;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("tebex-presentation.menu-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tebex-presentation.menu-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        TebexPresentation global = presentations.presentation(null);
        inventory.setItem(GLOBAL, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "tebex-presentation.global-name"),
                summary(viewer, global, false)));

        List<String> ranks = presentations.rankIds();
        int pages = Math.max(1, (ranks.size() + RANK_SLOTS.length - 1) / RANK_SLOTS.length);
        int safePage = Math.min(page, pages - 1);
        int from = safePage * RANK_SLOTS.length;
        int to = Math.min(ranks.size(), from + RANK_SLOTS.length);
        shown = from >= to ? List.of() : List.copyOf(ranks.subList(from, to));
        for (int i = 0; i < shown.size(); i++) {
            String rankId = shown.get(i);
            TebexPresentation presentation = presentations.presentation(rankId);
            List<String> lore = new ArrayList<>(summary(viewer, presentation, true));
            lore.add("<#7B68A0>" + Lang.raw(viewer,
                    "tebex-presentation.rank-group", "group",
                    plugin.getConfig().getString(
                            "Rank_Catalog." + rankId + ".LuckPerms_Group", rankId)));
            inventory.setItem(RANK_SLOTS[i], MenuItems.item(
                    presentation.toastIconMaterial(),
                    "<#E4A8FF>" + rankId, lore));
        }
        inventory.setItem(BACK, safePage > 0
                ? MenuNavigation.previous(viewer, safePage + 1, pages)
                : MenuNavigation.home(viewer));
        inventory.setItem(PAGE, MenuNavigation.page(viewer, safePage + 1, pages));
        if (safePage + 1 < pages) {
            inventory.setItem(NEXT,
                    MenuNavigation.next(viewer, safePage + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!allowed(player)) return;
        if (slot == GLOBAL) {
            Menu.open(player, new TebexPresentationEditorMenu(
                    plugin, presentations, null, page));
            return;
        }
        if (slot == BACK) {
            if (page > 0) {
                Menu.open(player, new TebexPresentationMenu(
                        plugin, presentations, page - 1));
            } else {
                Menu.open(player, new AdminMenu(plugin));
            }
            return;
        }
        if (slot == NEXT) {
            Menu.open(player, new TebexPresentationMenu(
                    plugin, presentations, page + 1));
            return;
        }
        for (int i = 0; i < RANK_SLOTS.length && i < shown.size(); i++) {
            if (slot == RANK_SLOTS[i]) {
                Menu.open(player, new TebexPresentationEditorMenu(
                        plugin, presentations, shown.get(i), page));
                return;
            }
        }
    }

    private List<String> summary(Player viewer, TebexPresentation value,
                                 boolean showInheritance) {
        List<String> lore = new ArrayList<>();
        lore.add(value.silent()
                ? "<#7B68A0>" + Lang.raw(viewer, "tebex-presentation.state-silent")
                : "<#3DFFA8>" + Lang.raw(viewer, "tebex-presentation.state-active"));
        if (showInheritance) {
            lore.add("<#C0B3D9>" + Lang.raw(viewer,
                    value.inherited()
                            ? "tebex-presentation.state-inherited"
                            : "tebex-presentation.state-custom"));
        }
        lore.add("<#C0B3D9>" + Lang.raw(viewer,
                "tebex-presentation.summary-audience", "audience",
                audience(viewer, value.audience())));
        lore.add("<#C0B3D9>" + Lang.raw(viewer,
                "tebex-presentation.summary-preset", "preset",
                preset(viewer, value.preset())));
        lore.add("<#7B68A0>" + Lang.raw(viewer,
                "tebex-presentation.click-edit"));
        return lore;
    }

    static String audience(Player viewer, TebexPresentation.Audience audience) {
        return Lang.raw(viewer, "tebex-presentation.audience-"
                + audience.name().toLowerCase(java.util.Locale.ROOT));
    }

    static String preset(Player viewer, TebexPresentation.Preset preset) {
        return Lang.raw(viewer, "tebex-presentation.preset-"
                + preset.name().toLowerCase(java.util.Locale.ROOT));
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.admin")
                || player.hasPermission("chatalyst.admin.tebex.presentation")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }
}
