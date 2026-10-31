package dev.chatalyst.market;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Logica pura de la vitrina del mercado. Mantener filtros y orden fuera de {@link MarketMenu}
 * permite probar la navegacion sin un servidor Bukkit y evita mezclar presentacion con negocio.
 */
final class MarketBrowser {

    enum Filter {
        ALL("market.gui-filter-all"),
        SALES("market.gui-filter-sales"),
        AUCTIONS("market.gui-filter-auctions"),
        MINE("market.gui-filter-mine");

        private final String langKey;

        Filter(String langKey) {
            this.langKey = langKey;
        }

        String langKey() {
            return langKey;
        }
    }

    enum Sort {
        NEWEST("market.gui-sort-newest"),
        PRICE_LOW("market.gui-sort-price-low"),
        PRICE_HIGH("market.gui-sort-price-high"),
        ENDING("market.gui-sort-ending");

        private final String langKey;

        Sort(String langKey) {
            this.langKey = langKey;
        }

        String langKey() {
            return langKey;
        }

        Sort cycle(boolean backwards) {
            Sort[] values = values();
            return values[Math.floorMod(ordinal() + (backwards ? -1 : 1), values.length)];
        }
    }

    private MarketBrowser() {
    }

    static List<MarketListing> apply(Collection<MarketListing> source, UUID viewer,
                                     Filter filter, Sort sort) {
        if (source == null || source.isEmpty()) return List.of();
        Filter safeFilter = filter == null ? Filter.ALL : filter;
        Sort safeSort = sort == null ? Sort.NEWEST : sort;
        List<MarketListing> result = new ArrayList<>();
        for (MarketListing listing : source) {
            if (listing == null || !matches(listing, viewer, safeFilter)) continue;
            result.add(listing);
        }
        result.sort(comparator(safeSort));
        return List.copyOf(result);
    }

    private static boolean matches(MarketListing listing, UUID viewer, Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case SALES -> !listing.isAuction();
            case AUCTIONS -> listing.isAuction();
            case MINE -> viewer != null && viewer.equals(listing.seller);
        };
    }

    private static Comparator<MarketListing> comparator(Sort sort) {
        Comparator<MarketListing> byId = Comparator.comparingInt(listing -> listing.id);
        return switch (sort) {
            case NEWEST -> Comparator.comparingLong((MarketListing listing) -> listing.created)
                    .thenComparing(byId).reversed();
            case PRICE_LOW -> Comparator.comparingDouble(MarketBrowser::effectivePrice)
                    .thenComparing(byId);
            case PRICE_HIGH -> Comparator.comparingDouble(MarketBrowser::effectivePrice)
                    .thenComparing(byId).reversed();
            case ENDING -> Comparator.comparingLong(MarketBrowser::endingAt)
                    .thenComparing(byId);
        };
    }

    private static double effectivePrice(MarketListing listing) {
        return listing.isAuction() && listing.topBidder != null ? listing.topBid : listing.price;
    }

    private static long endingAt(MarketListing listing) {
        return listing.expires > 0 ? listing.expires : Long.MAX_VALUE;
    }
}
