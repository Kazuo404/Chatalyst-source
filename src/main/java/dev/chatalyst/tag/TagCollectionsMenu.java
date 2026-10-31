package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Resumen paginado de categorías tratadas como colecciones de logros. */
public final class TagCollectionsMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 47;
    private static final int PAGE = 49;
    private static final int RANKING = 51;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final boolean returnAdmin;
    private final int returnPage;
    private int page;
    private final List<String> shown = new ArrayList<>();

    public TagCollectionsMenu(ChatalystPlugin plugin, TagManager tags) {
        this(plugin, tags, 0, false, 0);
    }

    public TagCollectionsMenu(ChatalystPlugin plugin, TagManager tags,
                              int page, boolean returnAdmin, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.page = Math.max(0, page);
        this.returnAdmin = returnAdmin;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.collections-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.collections-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        List<TagCollection> collections = tags.catalog().collections();
        int pages = Math.max(1, (collections.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(collections.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            TagCollection collection = collections.get(index);
            shown.add(collection.id());
            inventory.setItem(layout[index - start], collectionItem(viewer, collection));
        }
        if (collections.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_DYE,
                    Lang.raw(viewer, "tag.collections-empty"),
                    List.of(Lang.raw(viewer, "tag.collections-empty-lore"))));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, MenuNavigation.previous(viewer, page + 1, pages));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inventory.setItem(RANKING, MenuItems.item(Material.GOLDEN_HELMET,
                Lang.raw(viewer, "tag.ranking-open"),
                List.of(Lang.raw(viewer, "tag.ranking-open-lore"))));
        if (page + 1 < pages) {
            inventory.setItem(NEXT, MenuNavigation.next(viewer, page + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.tag")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            back(player);
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            open(player, page - 1);
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1,
                    (tags.catalog().collections().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1);
            return;
        }
        if (slot == RANKING) {
            Menu.open(player, new TagRankingMenu(
                    plugin, tags, 0, page, returnAdmin, returnPage));
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) >= 0) {
            Menu.open(player, new TagCollectionDetailMenu(plugin, tags,
                    shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)), 0, page, returnAdmin, returnPage));
        }
    }

    private org.bukkit.inventory.ItemStack collectionItem(
            Player viewer, TagCollection collection) {
        int earned = tags.collectionEarned(viewer.getUniqueId(), collection);
        int total = collection.total();
        boolean completed = tags.collectionCompleted(viewer.getUniqueId(), collection);
        List<String> lore = new ArrayList<>();
        if (!collection.description().isBlank()) {
            lore.add("<#C0B3D9>" + MM.escape(collection.description()));
        }
        lore.add("<#FBF2FF>" + Lang.raw(viewer, "tag.collection-progress",
                "earned", earned, "total", total));
        lore.add(TagPlayerMenu.progressBar(earned, total));
        lore.add("<#FFD166>" + Lang.raw(viewer, "tag.collection-points",
                "base", collection.titlePoints(), "bonus", collection.bonusPoints()));
        lore.add(completed
                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.collection-completed")
                : "<#7B68A0>" + Lang.raw(viewer, "tag.collection-incomplete"));
        lore.add("<#E0AAFF>" + Lang.raw(viewer, "tag.collection-open"));
        return MenuItems.item(collection.icon(),
                (completed ? "<#3DFFA8>" : "<#FBF2FF>")
                        + MM.escape(collection.displayName()), lore);
    }

    private void open(Player player, int nextPage) {
        Menu.open(player, new TagCollectionsMenu(
                plugin, tags, nextPage, returnAdmin, returnPage));
    }

    private void back(Player player) {
        if (returnAdmin && player.hasPermission("chatalyst.tag.admin")) {
            Menu.open(player, new TagAdminMenu(plugin, tags, returnPage));
        } else {
            Menu.open(player, new TagPlayerMenu(plugin, tags));
        }
    }
}
