package dev.chatalyst.market;

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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Vitrina premium del mercado. La primera fila controla filtros, orden y refresco; las cuatro
 * filas centrales muestran resultados; la ultima concentra publicacion, alijo e historial.
 * Los clicks siempre se resuelven contra el snapshot que el jugador esta viendo y el manager
 * vuelve a autorizar la operacion economica antes de ejecutarla.
 */
final class MarketMenu implements Menu {

    private static final int[] LISTING_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    private static final int PAGE_SIZE = LISTING_SLOTS.length;

    private static final int SLOT_PROFILE = 0;
    private static final int SLOT_FILTER_ALL = 1;
    private static final int SLOT_FILTER_SALES = 2;
    private static final int SLOT_FILTER_AUCTIONS = 3;
    private static final int SLOT_CORE = 4;
    private static final int SLOT_FILTER_MINE = 5;
    private static final int SLOT_SORT = 6;
    private static final int SLOT_REFRESH = 7;
    private static final int SLOT_HELP = 8;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SELL = 46;
    private static final int SLOT_CLAIMS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_HISTORY = 50;
    private static final int SLOT_CLOSE = 52;
    private static final int SLOT_NEXT = 53;

    private final MarketManager manager;
    private final int page;
    private final MarketBrowser.Filter filter;
    private final MarketBrowser.Sort sort;
    private final List<MarketListing> shown = new ArrayList<>();
    private int renderedPage;
    private boolean hasNext;

    MarketMenu(MarketManager manager, int page) {
        this(manager, page, MarketBrowser.Filter.ALL, MarketBrowser.Sort.NEWEST);
    }

    private MarketMenu(MarketManager manager, int page, MarketBrowser.Filter filter,
                       MarketBrowser.Sort sort) {
        this.manager = manager;
        this.page = Math.max(0, page);
        this.filter = filter == null ? MarketBrowser.Filter.ALL : filter;
        this.sort = sort == null ? MarketBrowser.Sort.NEWEST : sort;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("market.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "market.gui-title"));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        shown.clear();
        List<MarketListing> visible = manager.visibleFor(viewer.getUniqueId());
        List<MarketListing> filtered = MarketBrowser.apply(
                visible, viewer.getUniqueId(), filter, sort);
        int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.min(page, totalPages - 1);
        renderedPage = safePage;
        int from = safePage * PAGE_SIZE;
        long now = System.currentTimeMillis();

        buildHeader(inv, viewer, visible, filtered);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                Math.max(0, Math.min(filtered.size() - from, PAGE_SIZE)), 1);
        for (int i = from; i < filtered.size() && i - from < PAGE_SIZE; i++) {
            MarketListing listing = filtered.get(i);
            shown.add(listing);
            inv.setItem(layout[i - from], icon(listing, viewer, now));
        }
        if (filtered.isEmpty()) {
            inv.setItem(22, MenuItems.item(Material.ENDER_EYE,
                    Lang.raw(viewer, "market.gui-empty-results-name"),
                    Lang.rawList(viewer, "market.gui-empty-results-lore")));
        }

        hasNext = from + PAGE_SIZE < filtered.size();
        buildFooter(inv, viewer, safePage, totalPages, filtered.size());
    }

    private void buildHeader(Inventory inv, Player viewer, List<MarketListing> visible,
                             List<MarketListing> filtered) {
        int own = 0;
        for (MarketListing listing : visible) {
            if (listing.seller.equals(viewer.getUniqueId())) own++;
        }
        int claims = manager.claimCount(viewer.getUniqueId());
        inv.setItem(SLOT_PROFILE, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "market.gui-profile-name"),
                Lang.rawList(viewer, "market.gui-profile-lore",
                        "visible", visible.size(), "filtered", filtered.size(),
                        "own", own, "claims", claims,
                        "filter", Lang.raw(viewer, filter.langKey()),
                        "sort", Lang.raw(viewer, sort.langKey()))));
        filterButton(inv, viewer, SLOT_FILTER_ALL, Material.ENDER_CHEST,
                MarketBrowser.Filter.ALL);
        filterButton(inv, viewer, SLOT_FILTER_SALES, Material.EMERALD,
                MarketBrowser.Filter.SALES);
        filterButton(inv, viewer, SLOT_FILTER_AUCTIONS, Material.CLOCK,
                MarketBrowser.Filter.AUCTIONS);
        inv.setItem(SLOT_CORE, MenuItems.item(Material.AMETHYST_CLUSTER,
                Lang.raw(viewer, "market.gui-core-name"),
                Lang.rawList(viewer, "market.gui-core-lore")));
        filterButton(inv, viewer, SLOT_FILTER_MINE, Material.CHEST,
                MarketBrowser.Filter.MINE);
        inv.setItem(SLOT_SORT, MenuItems.item(Material.HOPPER,
                Lang.raw(viewer, "market.gui-sort-name", "sort", Lang.raw(viewer, sort.langKey())),
                Lang.rawList(viewer, "market.gui-sort-lore")));
        inv.setItem(SLOT_REFRESH, MenuNavigation.refresh(viewer));
        inv.setItem(SLOT_HELP, MenuItems.item(Material.ENCHANTED_BOOK,
                Lang.raw(viewer, "market.gui-guide-name"),
                Lang.rawList(viewer, "market.gui-guide-lore")));
    }

    private void filterButton(Inventory inv, Player viewer, int slot, Material material,
                              MarketBrowser.Filter target) {
        String stateKey = filter == target
                ? "market.gui-filter-selected" : "market.gui-filter-select";
        List<String> lore = new ArrayList<>(Lang.rawList(viewer, target.langKey() + "-lore"));
        lore.add("");
        lore.add(Lang.raw(viewer, stateKey));
        inv.setItem(slot, MenuItems.item(filter == target ? Material.AMETHYST_BLOCK : material,
                Lang.raw(viewer, target.langKey()), lore));
    }

    private void buildFooter(Inventory inv, Player viewer, int safePage, int totalPages,
                             int filteredCount) {
        inv.setItem(SLOT_PREV, MenuNavigation.previousOrFirst(viewer, safePage + 1, totalPages));
        inv.setItem(SLOT_NEXT, MenuNavigation.nextOrLast(viewer, safePage + 1, totalPages));
        inv.setItem(SLOT_SELL, MenuItems.item(Material.AMETHYST_SHARD,
                Lang.raw(viewer, "market.gui-sell-name"),
                Lang.rawList(viewer, "market.gui-sell-lore")));
        int claims = manager.claimCount(viewer.getUniqueId());
        inv.setItem(SLOT_CLAIMS, MenuItems.item(
                claims > 0 ? Material.ENDER_CHEST : Material.CHEST,
                Lang.raw(viewer, "market.gui-claims-name", "count", claims),
                Lang.rawList(viewer, "market.gui-claims-lore")));
        ItemStack pageItem = MenuNavigation.page(viewer, safePage + 1, totalPages);
        MenuItems.appendLore(pageItem,
                Lang.rawList(viewer, "market.gui-count", "count", filteredCount));
        inv.setItem(SLOT_PAGE, pageItem);
        inv.setItem(SLOT_HISTORY, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "market.gui-history-name"),
                Lang.rawList(viewer, "market.gui-history-lore")));
        inv.setItem(SLOT_CLOSE, MenuNavigation.close(viewer));
    }

    /** El item real del anuncio + contexto de venta añadido a su lore. */
    private ItemStack icon(MarketListing listing, Player viewer, long now) {
        ItemStack icon;
        try {
            icon = listing.item();
        } catch (RuntimeException unreadable) {
            // Un solo anuncio ilegible dejaba la pagina entera sin abrir para todos.
            return MenuItems.item(org.bukkit.Material.BARRIER, "<#C0B3D9>#" + listing.id,
                    List.of());
        }
        highlightName(icon, listing.nameColor);
        boolean own = listing.seller.equals(viewer.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(Lang.raw(viewer, "market.gui-lore-divider"));
        lore.add(Lang.raw(viewer,
                listing.isAuction() ? "market.gui-lore-auction" : "market.gui-lore-sale",
                "id", listing.id));
        lore.add(Lang.raw(viewer, "market.gui-lore-seller", "player", listing.sellerName));
        if (listing.scheduled(now)) {
            lore.add(Lang.raw(viewer, "market.gui-lore-scheduled",
                    "time", timeLeft(viewer, listing.publishAt, now)));
        }
        if (!listing.description.isEmpty()) {
            lore.add("");
            lore.add(Lang.raw(viewer, "market.gui-lore-desc-label"));
            lore.add(Lang.raw(viewer, "market.gui-lore-desc",
                    "text", MM.escape(listing.description)));
        }
        lore.add("");
        if (listing.isAuction()) {
            lore.add(listing.topBidder == null
                    ? Lang.raw(viewer, "market.gui-lore-start-bid",
                            "price", MarketManager.money(listing.price))
                    : Lang.raw(viewer, "market.gui-lore-top-bid",
                            "price", MarketManager.money(listing.topBid),
                            "player", listing.topBidderName));
            lore.add(Lang.raw(viewer, "market.gui-lore-time-left",
                    "time", timeLeft(viewer, listing.expires, now)));
        } else {
            lore.add(Lang.raw(viewer, "market.gui-lore-price",
                    "price", MarketManager.money(listing.price)));
            if (manager.negotiationOf(listing.id) != null) {
                lore.add(Lang.raw(viewer, "market.gui-lore-busy"));
            }
        }
        lore.add("");
        lore.add(Lang.raw(viewer, "market.gui-lore-divider"));
        lore.add(Lang.raw(viewer, own
                ? "market.gui-lore-click-cancel"
                : listing.isAuction() ? "market.gui-lore-click-bid"
                : "market.gui-lore-click-buy"));
        MenuItems.appendLore(icon, lore);
        return icon;
    }

    private static void highlightName(ItemStack icon, String colorTag) {
        var color = MarketManager.parseColor(colorTag);
        if (color == null) return;
        icon.editMeta(meta -> {
            Component base = meta.hasDisplayName()
                    ? meta.displayName()
                    : Component.translatable(icon.getType().translationKey());
            meta.displayName(base.colorIfAbsent(color)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        });
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        int listingIndex = listingIndex(slot);
        if (listingIndex >= 0) {
            if (listingIndex >= shown.size()) {
                if (slot == 22 && shown.isEmpty()) open(player, 0, MarketBrowser.Filter.ALL, sort);
                return;
            }
            MarketListing listing = shown.get(listingIndex);
            if (listing.seller.equals(player.getUniqueId())) {
                if (click.isShiftClick()) {
                    player.closeInventory();
                    manager.cancel(player, listing.id);
                }
                return;
            }
            player.closeInventory();
            if (listing.isAuction()) manager.hintBid(player, listing);
            else manager.requestBuy(player, listing.id);
            return;
        }
        switch (slot) {
            case SLOT_FILTER_ALL -> open(player, 0, MarketBrowser.Filter.ALL, sort);
            case SLOT_FILTER_SALES -> open(player, 0, MarketBrowser.Filter.SALES, sort);
            case SLOT_FILTER_AUCTIONS -> open(player, 0, MarketBrowser.Filter.AUCTIONS, sort);
            case SLOT_FILTER_MINE -> open(player, 0, MarketBrowser.Filter.MINE, sort);
            case SLOT_SORT -> open(player, 0, filter, sort.cycle(click.isRightClick()));
            case SLOT_REFRESH -> open(player, renderedPage, filter, sort);
            case SLOT_PREV -> {
                if (renderedPage > 0) open(player, renderedPage - 1, filter, sort);
            }
            case SLOT_NEXT -> {
                if (hasNext) open(player, renderedPage + 1, filter, sort);
            }
            case SLOT_CLAIMS -> {
                manager.claim(player);
                open(player, renderedPage, filter, sort);
            }
            case SLOT_HISTORY -> {
                player.closeInventory();
                manager.sendHistory(player);
            }
            case SLOT_SELL -> manager.openPublish(player);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    private void open(Player player, int nextPage, MarketBrowser.Filter nextFilter,
                      MarketBrowser.Sort nextSort) {
        Menu.open(player, new MarketMenu(manager, nextPage, nextFilter, nextSort));
    }

    /**
     * Anuncio bajo {@code slot}: -1 fuera de la rejilla; {@code shown.size()} (o mas) si es un
     * hueco vacio de la rejilla. La ultima fila va centrada (menu simetrico).
     */
    private int listingIndex(int slot) {
        if (slot < 9 || slot > 44) return -1;
        int index = dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 1);
        return index < 0 ? shown.size() : index;
    }

    private static String timeLeft(Player viewer, long expires, long now) {
        long seconds = Math.max(0, (expires - now) / 1_000L);
        if (seconds < 60) return Lang.raw(viewer, "market.time-seconds", "value", seconds);
        if (seconds < 3_600) return Lang.raw(viewer, "market.time-minutes", "value", seconds / 60);
        if (seconds < 86_400) return Lang.raw(viewer, "market.time-hours", "value", seconds / 3_600);
        return Lang.raw(viewer, "market.time-days", "value", seconds / 86_400);
    }
}
