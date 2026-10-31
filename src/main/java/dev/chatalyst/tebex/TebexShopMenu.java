package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paginated, YAML-driven storefront for Tebex-enabled ranks. */
public final class TebexShopMenu implements Menu {

    private static final int[] RANK_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int INFO = 4;
    private static final int BACK = 45;
    private static final int HOME = 47;
    private static final int PAGE = 49;
    private static final int NEXT = 53;
    /** Entregas pendientes: el escaparate es ahora /store, asi que su bandeja vive dentro. */
    private static final int PENDING = 51;

    private final ChatalystPlugin plugin;
    private final TebexShopService shop;
    private final int page;
    /** Null while showing the category list; a section id once inside one. */
    private final String category;
    private final Map<Integer, String> shown = new LinkedHashMap<>();
    private final Map<Integer, String> shownCategories = new LinkedHashMap<>();
    private int renderedPage;
    private int renderedPages;

    public TebexShopMenu(ChatalystPlugin plugin, TebexShopService shop, int page) {
        this(plugin, shop, page, null);
    }

    public TebexShopMenu(ChatalystPlugin plugin, TebexShopService shop, int page,
                         String category) {
        this.plugin = plugin;
        this.shop = shop;
        this.page = Math.max(0, page);
        this.category = category;
    }

    /**
     * The categories view is shown only when sections are enabled AND we are not already
     * inside one, so a flat store behaves exactly as it did before this existed.
     */
    private boolean showingCategories() {
        return category == null && shop.sectioned();
    }

    private List<StorefrontEntry> entriesFor(Player viewer) {
        return category == null
                ? shop.visibleEntries(viewer)
                : shop.visibleEntries(viewer, category);
    }

    // One source of truth: Tebex.Shop.Title. Menu#title(viewer) is what actually opens the
    // inventory, so a separate lang key here would silently never be shown.
    @Override
    public Component title() {
        return shop.title(null);
    }

    @Override
    public Component title(Player viewer) {
        return shop.title(viewer);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        shownCategories.clear();
        if (showingCategories()) {
            buildCategories(inventory, viewer);
            return;
        }
        List<StorefrontEntry> entries = entriesFor(viewer);
        renderedPages = Math.max(1,
                (entries.size() + RANK_SLOTS.length - 1) / RANK_SLOTS.length);
        renderedPage = Math.min(page, renderedPages - 1);
        int from = renderedPage * RANK_SLOTS.length;
        int to = Math.min(entries.size(), from + RANK_SLOTS.length);

        inventory.setItem(INFO, MenuItems.item(shop.infoIcon(),
                shop.infoName(viewer), shop.infoLore(viewer)));
        // Resolved once for the whole page instead of once per card.
        Map<String, Long> owned = shop.ownedGroups(viewer);
        for (int index = from; index < to; index++) {
            StorefrontEntry entry = entries.get(index);
            int slot = RANK_SLOTS[index - from];
            shown.put(slot, entry.id());
            inventory.setItem(slot, card(viewer, entry, owned));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.ENDER_EYE,
                    Lang.raw(viewer, "tebex-shop.empty-name"),
                    Lang.rawList(viewer, "tebex-shop.empty-lore")));
        }

        // Inside a section the bottom-left button returns to the sections rather than
        // closing, so a player never has to reopen the store to change category.
        inventory.setItem(BACK, renderedPage > 0
                ? MenuNavigation.previous(viewer, renderedPage + 1, renderedPages)
                : (category != null && shop.sectioned()
                        ? MenuNavigation.back(viewer) : MenuNavigation.close(viewer)));
        if (shop.hasHomeUrl(viewer)) {
            inventory.setItem(HOME, MenuItems.item(Material.SUNFLOWER,
                    Lang.raw(viewer, "tebex-shop.home-name"),
                    Lang.rawList(viewer, "tebex-shop.home-lore")));
        }
        renderPending(inventory, viewer);
        inventory.setItem(PAGE,
                MenuNavigation.page(viewer, renderedPage + 1, renderedPages));
        if (renderedPage + 1 < renderedPages) {
            inventory.setItem(NEXT,
                    MenuNavigation.next(viewer, renderedPage + 1, renderedPages));
        }
    }

    /** One item per section, hiding any that has nothing this player may see. */
    private void buildCategories(Inventory inventory, Player viewer) {
        inventory.setItem(INFO, MenuItems.item(shop.infoIcon(),
                shop.infoName(viewer), shop.infoLore(viewer)));
        List<StorefrontCategory> categories = shop.categories(viewer);
        // Resolved once for the whole view; asking per section re-parsed both catalogs.
        Map<String, List<StorefrontEntry>> grouped = shop.entriesByCategory(viewer);
        int slot = 0;
        boolean any = false;
        for (StorefrontCategory section : categories) {
            if (slot >= RANK_SLOTS.length) break;
            int count = grouped.getOrDefault(section.id(), List.of()).size();
            if (count == 0) continue; // an empty shelf is noise, not a section
            List<String> lore = new ArrayList<>(section.description());
            lore.add("");
            lore.add(Lang.raw(viewer, "tebex-shop.category-count", "count", count));
            inventory.setItem(RANK_SLOTS[slot], MenuItems.item(
                    section.iconItem(), section.displayName(), lore));
            shownCategories.put(RANK_SLOTS[slot], section.id());
            slot++;
            any = true;
        }
        // Entries whose section is missing or misspelled land here instead of vanishing.
        int loose = grouped.getOrDefault(StorefrontCategory.DEFAULT_ID, List.of()).size();
        if (loose > 0 && slot < RANK_SLOTS.length) {
            inventory.setItem(RANK_SLOTS[slot], MenuItems.item(Material.CHEST,
                    Lang.raw(viewer, "tebex-shop.category-other"),
                    List.of(Lang.raw(viewer, "tebex-shop.category-count",
                            "count", loose))));
            shownCategories.put(RANK_SLOTS[slot], StorefrontCategory.DEFAULT_ID);
            any = true;
        }
        if (!any) {
            inventory.setItem(22, MenuItems.item(Material.ENDER_EYE,
                    Lang.raw(viewer, "tebex-shop.empty-name"),
                    Lang.rawList(viewer, "tebex-shop.empty-lore")));
        }
        inventory.setItem(BACK, MenuNavigation.close(viewer));
        renderPending(inventory, viewer);
        if (shop.hasHomeUrl(viewer)) {
            inventory.setItem(HOME, MenuItems.item(Material.SUNFLOWER,
                    Lang.raw(viewer, "tebex-shop.home-name"),
                    Lang.rawList(viewer, "tebex-shop.home-lore")));
        }
    }

    /**
     * Acceso a lo que el jugador ya compro. Solo aparece si la tienda de objetos esta
     * encendida: en un servidor que solo vende rangos seria un boton que no lleva a nada.
     */
    private void renderPending(Inventory inventory, Player viewer) {
        var store = plugin.getStoreService();
        if (store == null || !store.enabled()) return;
        int waiting = store.pendingFor(viewer.getUniqueId()).size();
        inventory.setItem(PENDING, MenuItems.item(
                waiting > 0 ? Material.CHEST_MINECART : Material.CHEST,
                Lang.raw(viewer, waiting > 0
                        ? "tebex-shop.pending-name" : "tebex-shop.pending-empty-name"),
                Lang.rawList(viewer, waiting > 0
                                ? "tebex-shop.pending-lore" : "tebex-shop.pending-empty-lore",
                        "count", waiting)));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.ranks")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        String section = shownCategories.get(slot);
        if (section != null) {
            Menu.open(player, new TebexShopMenu(plugin, shop, 0, section));
            return;
        }
        String rankId = shown.get(slot);
        if (rankId != null) {
            // The card opens the preview; buying is an explicit button inside it, so a
            // misclick in the storefront can never fire a purchase link.
            Menu.open(player, new StorefrontPreviewMenu(plugin, shop, rankId, category));
            return;
        }
        if (slot == BACK) {
            if (renderedPage > 0) {
                Menu.open(player, new TebexShopMenu(
                        plugin, shop, renderedPage - 1, category));
            } else if (category != null && shop.sectioned()) {
                Menu.open(player, new TebexShopMenu(plugin, shop, 0, null));
            } else {
                player.closeInventory();
            }
        } else if (slot == PENDING) {
            var store = plugin.getStoreService();
            if (store != null && store.enabled()) {
                Menu.open(player, new dev.chatalyst.store.StoreMenu(store));
            }
        } else if (slot == HOME) {
            shop.sendHome(player);
        } else if (slot == NEXT && renderedPage + 1 < renderedPages) {
            Menu.open(player, new TebexShopMenu(
                    plugin, shop, renderedPage + 1, category));
        }
    }

    private ItemStack card(Player viewer, StorefrontEntry entry, Map<String, Long> owned) {
        List<String> lore = new ArrayList<>();
        // A badge below the price is not a badge: it goes where the eye lands first.
        if (entry.featured()) {
            lore.add(Lang.raw(viewer, "tebex-shop.featured"));
            lore.add("");
        }
        for (String line : entry.description()) {
            lore.add(shop.render(line, entry, viewer));
        }
        if (!entry.price().isBlank()) {
            lore.add("");
            lore.add(Lang.raw(viewer, "tebex-shop.price",
                    "price", escape(entry.price())));
        }
        // Goods have no duration and cannot be "already owned": buying a second kit is
        // legitimate, so neither line is shown for them.
        if (!entry.duration().isBlank()) {
            lore.add(Lang.raw(viewer, "tebex-shop.duration",
                    "duration", escape(entry.duration())));
        }

        boolean isOwned = !entry.ownedGroup().isBlank()
                && owned.containsKey(entry.ownedGroup());
        if (isOwned) {
            long expiresAt = owned.get(entry.ownedGroup());
            lore.add("");
            lore.add(expiresAt == 0L
                    ? Lang.raw(viewer, "tebex-shop.owned-permanent")
                    : Lang.raw(viewer, "tebex-shop.owned-until", "time",
                            dev.chatalyst.util.TimeUtil.formatElapsed(
                                    expiresAt - System.currentTimeMillis())));
        }
        lore.add("");
        lore.add(Lang.raw(viewer, isOwned
                ? "tebex-shop.click-extend" : "tebex-shop.click-preview"));

        ItemStack item = MenuItems.item(entry.iconItem(),
                shop.render(entry.displayName(), entry, viewer), lore);
        if (entry.featured() || isOwned) {
            item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return item;
    }

    private static String escape(String value) {
        return dev.chatalyst.util.MM.escape(value);
    }
}
