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

import java.util.ArrayList;
import java.util.List;

/**
 * What a rank or a good actually gives, shown before paying for it.
 *
 * <p>A storefront card has room for a name, a price and a couple of lines. A rank that grants
 * a dozen perks cannot be judged from that, so the preview is where the detail goes: the buyer
 * sees the contents laid out as items and only then decides.
 *
 * <p>Nothing here is bought by accident. The purchase button is a separate, explicit slot, and
 * it goes through the same {@link TebexShopService#select} path as the card — same HTTPS
 * validation, same permission check, same safe chat button.
 */
public final class StorefrontPreviewMenu implements Menu {

    // 28 slots: a full kit is a set of armour, tools, food and blocks, and the biggest one
    // on a real server already came to fifteen. Two rows would have dropped items silently.
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    // Barra simetrica: volver | <- comprar -> | cerrar.
    private static final int BACK = 45;
    private static final int CLOSE = 53;
    private static final int BUY = 49;
    private static final int PREVIOUS = 48;
    private static final int NEXT = 50;

    private final ChatalystPlugin plugin;
    private final TebexShopService shop;
    private final String entryId;
    /** The section to return to, so Back does not dump the player at the top level. */
    private final String category;
    private final int page;
    private int renderedPage;
    private int renderedPages;

    public StorefrontPreviewMenu(ChatalystPlugin plugin, TebexShopService shop,
                                 String entryId, String category) {
        this(plugin, shop, entryId, category, 0);
    }

    public StorefrontPreviewMenu(ChatalystPlugin plugin, TebexShopService shop,
                                 String entryId, String category, int page) {
        this.plugin = plugin;
        this.shop = shop;
        this.entryId = entryId;
        this.category = category;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("tebex-shop.preview-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tebex-shop.preview-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        var resolved = shop.entry(entryId, viewer);
        if (resolved.isEmpty()) {
            inventory.setItem(13, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tebex-shop.unavailable"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            inventory.setItem(CLOSE, MenuNavigation.close(viewer));
            return;
        }
        StorefrontEntry entry = resolved.get();

        // The entry itself, at the top: what you are looking at, with its price.
        List<String> header = new ArrayList<>(entry.description());
        if (!entry.price().isBlank()) {
            header.add("");
            header.add(Lang.raw(viewer, "tebex-shop.price",
                    "price", dev.chatalyst.util.MM.escape(entry.price())));
        }
        if (!entry.duration().isBlank()) {
            header.add(Lang.raw(viewer, "tebex-shop.duration",
                    "duration", dev.chatalyst.util.MM.escape(entry.duration())));
        }
        inventory.setItem(4, MenuItems.item(entry.iconItem(),
                shop.render(entry.displayName(), entry, viewer), header));

        List<StorefrontPreviewItem> contents = shop.previewOf(entry, viewer);
        if (contents.isEmpty()) {
            inventory.setItem(13, MenuItems.item(Material.PAPER,
                    Lang.raw(viewer, "tebex-shop.preview-empty-name"),
                    Lang.rawList(viewer, "tebex-shop.preview-empty-lore")));
        }
        // Paginated rather than capped: a kit is as big as its owner made it, and showing
        // fewer items than are being sold is the one thing a preview must never do.
        renderedPages = Math.max(1,
                (contents.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        renderedPage = Math.min(page, renderedPages - 1);
        int from = renderedPage * CONTENT_SLOTS.length;
        int to = Math.min(contents.size(), from + CONTENT_SLOTS.length);
        int[] layout = dev.chatalyst.gui.MenuLayout.insetSlots(Math.max(0, to - from), 1);
        for (int i = from; i < to; i++) {
            inventory.setItem(layout[i - from], contents.get(i).render());
        }
        if (renderedPages > 1) {
            inventory.setItem(PREVIOUS,
                        MenuNavigation.previousOrFirst(viewer, renderedPage + 1, renderedPages));
            inventory.setItem(NEXT,
                        MenuNavigation.nextOrLast(viewer, renderedPage + 1, renderedPages));
        }

        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(BUY, entry.purchaseUrl(viewer.getName(), viewer.getUniqueId()).isPresent()
                ? shop.buyButton(entry, viewer) : MenuNavigation.unavailable(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("chatalyst.command.ranks")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BUY) {
            shop.select(player, entryId);
        } else if (slot == BACK) {
            Menu.open(player, new TebexShopMenu(plugin, shop, 0, category));
        } else if (slot == PREVIOUS && renderedPage > 0) {
            Menu.open(player, new StorefrontPreviewMenu(
                    plugin, shop, entryId, category, renderedPage - 1));
        } else if (slot == NEXT && renderedPage + 1 < renderedPages) {
            Menu.open(player, new StorefrontPreviewMenu(
                    plugin, shop, entryId, category, renderedPage + 1));
        }
    }
}
