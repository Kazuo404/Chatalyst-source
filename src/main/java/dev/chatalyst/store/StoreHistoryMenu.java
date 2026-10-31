package dev.chatalyst.store;

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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's purchase history: every rank and every product they got from the store, newest
 * first, with where it came from (web store, link reward, gift) and whether it already arrived.
 *
 * <p>Read-only: a pending card points back to {@code /compras} instead of delivering from here,
 * so there is exactly one place that hands goods over.
 */
public final class StoreHistoryMenu implements Menu {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    // Fila de abajo simetrica: volver | anterior | pagina | siguiente | cerrar.
    private static final int BACK = 36;
    private static final int PREVIOUS = 38;
    private static final int PAGE = 40;
    private static final int NEXT = 42;
    private static final int CLOSE = 44;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final StoreService store;
    private int page = 1;
    private int pages = 1;

    public StoreHistoryMenu(StoreService store) {
        this.store = store;
    }

    @Override
    public Component title() {
        return Lang.get("store.history-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "store.history-title");
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<PurchaseHistory.Entry> history = store.historyFor(viewer.getUniqueId());
        pages = Math.max(1, (history.size() + SLOTS.length - 1) / SLOTS.length);
        page = Math.max(1, Math.min(page, pages));

        if (history.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.BOOK,
                    Lang.raw(viewer, "store.history-empty-name"),
                    Lang.rawList(viewer, "store.history-empty-lore")));
        }
        int from = (page - 1) * SLOTS.length;
        int count = Math.min(SLOTS.length, history.size() - from);
        int[] layout = dev.chatalyst.gui.MenuLayout.insetSlots(Math.max(0, count), 1);
        for (int index = 0; index < layout.length; index++) {
            inventory.setItem(layout[index], card(viewer, history.get(from + index)));
        }

        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page, pages));
        inventory.setItem(PAGE, MenuNavigation.page(viewer, page, pages));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page, pages));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
    }

    private ItemStack card(Player viewer, PurchaseHistory.Entry entry) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "store.history-origin-" + origin(entry.origin())));
        lore.add(Lang.raw(viewer, "store.history-date", "date", DATE.format(Instant.ofEpochMilli(entry.time()))));
        return switch (entry.kind()) {
            case RANK -> {
                lore.add(entry.expiresAt() > 0
                        ? Lang.raw(viewer, "store.history-rank-until",
                        "date", DATE.format(Instant.ofEpochMilli(entry.expiresAt())))
                        : Lang.raw(viewer, "store.history-rank-permanent"));
                yield MenuItems.item(Material.NETHER_STAR, Lang.raw(viewer, "store.history-rank-name",
                        "rank", MM.escape(PurchaseHistory.rankName(entry.rank()))), lore);
            }
            case RANK_REMOVED -> {
                lore.add(Lang.raw(viewer, "store.history-rank-removed-lore"));
                yield MenuItems.item(Material.GRAY_DYE, Lang.raw(viewer, "store.history-rank-removed",
                        "rank", MM.escape(PurchaseHistory.rankName(entry.rank()))), lore);
            }
            case GOODS -> goods(viewer, entry, lore);
        };
    }

    private ItemStack goods(Player viewer, PurchaseHistory.Entry entry, List<String> lore) {
        StoreClaims.Claim claim = entry.claim();
        var resolved = store.contentsOf(claim);
        String name = resolved.map(product -> MM.sanitizeInteraction(product.displayName()))
                .orElse(MM.escape(claim.productId()));
        resolved.ifPresent(product -> {
            if (product.money() > 0d) {
                lore.add(Lang.raw(viewer, "store.menu-line-money",
                        "amount", dev.chatalyst.hook.VaultHook.format(product.money())));
            }
            for (StoreProduct.ItemSpec spec : product.items()) {
                lore.add(Lang.raw(viewer, "store.menu-line-item", "amount", spec.amount(),
                        "item", spec.name().isBlank() ? spec.material().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', ' ') : MM.sanitizeInteraction(spec.name())));
            }
            if (!product.commands().isEmpty()) {
                lore.add(Lang.raw(viewer, "store.menu-line-extra", "count", product.commands().size()));
            }
        });
        lore.add(Lang.raw(viewer, entry.pending() ? "store.history-pending" : "store.history-delivered"));
        // The product's own first item when it has one (a kit looks like the kit); otherwise
        // gold for money-only, a hook for keys and other command rewards.
        ItemStack base;
        if (resolved.isEmpty()) {
            base = new ItemStack(Material.PAPER);
        } else if (!resolved.get().items().isEmpty()) {
            base = resolved.get().items().getFirst().build();
        } else {
            base = new ItemStack(resolved.get().commands().isEmpty()
                    ? Material.GOLD_INGOT : Material.TRIPWIRE_HOOK);
        }
        return MenuItems.item(base, name, lore);
    }

    private static String origin(PurchaseHistory.Origin origin) {
        return switch (origin) {
            case WEB -> "web";
            case REWARD -> "reward";
            case GIFT -> "gift";
            case STORE -> "store";
        };
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case BACK -> Menu.open(player, new StoreMenu(store));
            case PREVIOUS -> {
                if (page > 1) {
                    page--;
                    Menu.open(player, this);
                }
            }
            case NEXT -> {
                if (page < pages) {
                    page++;
                    Menu.open(player, this);
                }
            }
            case CLOSE -> player.closeInventory();
            default -> {
                // Cards are informative only: delivery lives in /compras.
            }
        }
    }
}
