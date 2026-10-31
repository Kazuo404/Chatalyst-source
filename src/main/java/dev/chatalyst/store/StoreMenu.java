package dev.chatalyst.store;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
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

/**
 * What a buyer is still owed, as a menu instead of a wall of chat lines.
 *
 * <p>Each card previews the product with its own first item as the icon, so a kit looks like
 * the kit. Clicking one hands over that purchase; the button at the bottom takes everything.
 */
public final class StoreMenu implements Menu {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    // Fila de abajo simetrica: cerrar | historial | reclamar todo (centro) | tienda web | actualizar.
    private static final int CLAIM_ALL = 40;
    private static final int CLOSE = 36;
    private static final int HISTORY = 38;
    private static final int WEB = 42;
    private static final int REFRESH = 44;

    private final StoreService store;
    // Transaction AND product per card: a cart shares one transaction across its products.
    private final Map<Integer, StoreClaims.Claim> shown = new LinkedHashMap<>();
    private boolean anyPending;

    public StoreMenu(StoreService store) {
        this.store = store;
    }

    @Override
    public Component title() {
        return Lang.get("store.menu-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "store.menu-title");
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        List<StoreClaims.Claim> pending = store.pendingFor(viewer.getUniqueId());
        anyPending = !pending.isEmpty();

        if (pending.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.ENDER_EYE,
                    Lang.raw(viewer, "store.menu-empty-name"),
                    Lang.rawList(viewer, "store.menu-empty-lore")));
        }
        int[] layout = dev.chatalyst.gui.MenuLayout.insetSlots(Math.min(pending.size(), SLOTS.length), 1);
        for (int index = 0; index < layout.length; index++) {
            StoreClaims.Claim claim = pending.get(index);
            shown.put(layout[index], claim);
            inventory.setItem(layout[index], card(viewer, claim));
        }

        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(CLAIM_ALL, anyPending ? MenuItems.item(Material.CHEST,
                Lang.raw(viewer, "store.menu-claim-all"),
                Lang.rawList(viewer, "store.menu-claim-all-lore"))
                : MenuNavigation.unavailable(viewer));
        inventory.setItem(REFRESH, MenuNavigation.refresh(viewer));
        inventory.setItem(HISTORY, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "store.menu-history-name"),
                Lang.rawList(viewer, "store.menu-history-lore")));
        // Sin tienda web configurada el hueco queda gris: la fila sigue siendo simetrica.
        inventory.setItem(WEB, store.webUrl().isEmpty() ? MenuNavigation.unavailable(viewer)
                : MenuItems.item(Material.EMERALD, Lang.raw(viewer, "store.menu-web-name"),
                Lang.rawList(viewer, "store.menu-web-lore", "url", MM.escape(store.webUrl()))));
    }

    private ItemStack card(Player viewer, StoreClaims.Claim claim) {
        // The card shows what was BOUGHT (the snapshot the delivery will use), not whatever
        // the catalog says today; the catalog is only the fallback for pre-snapshot claims.
        var resolved = store.contentsOf(claim);
        List<String> lore = new ArrayList<>();
        if (resolved.isEmpty()) {
            // The catalog entry was removed after the sale. The purchase is NOT lost, so the
            // card says so rather than hiding it and leaving the player wondering.
            lore.add(Lang.raw(viewer, "store.menu-unknown-lore"));
            lore.add("");
            lore.add(Lang.raw(viewer, "store.menu-bought",
                    "time", TimeUtil.formatElapsed(
                            System.currentTimeMillis() - claim.createdAt())));
            return MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "store.menu-unknown-name",
                            "product", MM.escape(claim.productId())), lore);
        }
        StoreProduct product = resolved.get();
        for (StoreProduct.ItemSpec spec : product.items()) {
            lore.add(Lang.raw(viewer, "store.menu-line-item",
                    "amount", spec.amount(),
                    "item", spec.name().isBlank()
                            ? readable(spec.material()) : MM.sanitizeInteraction(spec.name())));
        }
        if (product.money() > 0d) {
            lore.add(Lang.raw(viewer, "store.menu-line-money",
                    "amount", dev.chatalyst.hook.VaultHook.format(product.money())));
        }
        if (!product.commands().isEmpty()) {
            lore.add(Lang.raw(viewer, "store.menu-line-extra",
                    "count", product.commands().size()));
        }
        lore.add("");
        lore.add(Lang.raw(viewer, "store.menu-bought",
                "time", TimeUtil.formatElapsed(
                        System.currentTimeMillis() - claim.createdAt())));
        lore.add(Lang.raw(viewer, "store.menu-click-claim"));

        // The product's own first item is the icon, so a kit looks like the kit.
        ItemStack icon = product.items().isEmpty()
                ? new ItemStack(product.money() > 0d ? Material.GOLD_INGOT : Material.PAPER)
                : product.items().getFirst().build();
        // Owner-written names keep their formatting (as in /store list); only click/hover go.
        return MenuItems.item(icon, MM.sanitizeInteraction(product.displayName()), lore);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        StoreClaims.Claim card = shown.get(slot);
        if (card != null) {
            player.closeInventory();
            store.claimOne(player, card.transaction(), card.productId());
            return;
        }
        if (slot == CLAIM_ALL && anyPending) {
            player.closeInventory();
            store.claimNow(player);
        } else if (slot == CLOSE) {
            player.closeInventory();
        } else if (slot == HISTORY) {
            Menu.open(player, new StoreHistoryMenu(store));
        } else if (slot == WEB && !store.webUrl().isEmpty()) {
            player.closeInventory();
            player.sendMessage(MM.full(Lang.raw(player, "store.web-link",
                    "url", MM.escape(store.webUrl()))));
        } else if (slot == REFRESH) {
            Menu.open(player, this);
        }
    }

    private static String readable(Material material) {
        String name = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
