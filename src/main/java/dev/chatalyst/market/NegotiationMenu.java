package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI de negociacion de una venta: arriba el item en venta, en el centro la oferta del
 * comprador (dinero ajustable + hasta {@link Negotiation#MAX_OFFER_ITEMS} stacks de trueque)
 * y abajo los botones de aceptar/rechazar. AMBAS partes ven la misma negociacion (cada una
 * su propia copia de la GUI, refrescada en vivo) y la venta solo se ejecuta cuando las dos
 * han aceptado; cualquier cambio de la oferta borra las aceptaciones.
 *
 * <p>El comprador añade trueque clicando items de SU inventario (abajo) y los retira
 * clicando el slot de la oferta. Los items añadidos quedan en deposito hasta que la
 * negociacion termina.
 */
final class NegotiationMenu implements Menu {

    private static final int SLOT_ITEM = 4;
    private static final int SLOT_BUYER = 2;
    private static final int SLOT_SELLER = 6;
    private static final int SLOT_MONEY = 13;
    private static final int OFFER_START = 19; // 19..25 (7 slots)
    private static final int SLOT_INFO = 31;
    private static final int SLOT_ACCEPT = 48;
    private static final int SLOT_STATUS = 49;
    private static final int SLOT_REJECT = 50;

    private final MarketManager manager;
    private final Negotiation neg;
    private final MarketListing listing;

    NegotiationMenu(MarketManager manager, Negotiation neg, MarketListing listing) {
        this.manager = manager;
        this.neg = neg;
        this.listing = listing;
    }

    Negotiation negotiation() {
        return neg;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("market.gui-negotiation-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "market.gui-negotiation-title"));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        boolean isBuyer = neg.buyer.equals(viewer.getUniqueId());

        for (int slot = 9; slot <= 17; slot++) {
            if (slot != SLOT_MONEY) {
                inv.setItem(slot, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE,
                        " ", List.of()));
            }
        }

        inv.setItem(SLOT_BUYER, head(viewer, neg.buyer, neg.buyerName,
                neg.buyerAccepted() ? "market.gui-status-accepted" : "market.gui-status-pending"));
        inv.setItem(SLOT_SELLER, head(viewer, neg.seller, neg.sellerName,
                neg.sellerAccepted() ? "market.gui-status-accepted" : "market.gui-status-pending"));

        // El item en venta, con el contexto en el lore (vendedor y precio pedido).
        ItemStack sold = listing.item();
        MenuItems.appendLore(sold, List.of(
                "",
                Lang.raw(viewer, "market.gui-lore-seller", "player", listing.sellerName),
                Lang.raw(viewer, "market.gui-lore-price", "price", MarketManager.money(listing.price))));
        inv.setItem(SLOT_ITEM, sold);

        // Dinero ofrecido (el comprador lo ajusta con clicks; el vendedor solo lo ve).
        List<String> moneyLore = new ArrayList<>();
        moneyLore.add(Lang.raw(viewer, "market.gui-money-lore", "player", neg.buyerName));
        if (isBuyer && VaultHook.hasEconomy()) {
            moneyLore.add("");
            moneyLore.addAll(Lang.rawList(viewer, "market.gui-money-controls"));
        }
        inv.setItem(SLOT_MONEY, MenuItems.item(Material.AMETHYST_SHARD,
                Lang.raw(viewer, "market.gui-money-name", "amount", MarketManager.money(neg.moneyOffer())),
                moneyLore));

        // El trueque ofrecido, tal cual (el tooltip real de cada item importa aqui).
        List<ItemStack> offered = neg.items();
        for (int i = 0; i < Negotiation.MAX_OFFER_ITEMS; i++) {
            if (i < offered.size()) {
                ItemStack shown = offered.get(i).clone();
                if (isBuyer) {
                    MenuItems.appendLore(shown, List.of("", Lang.raw(viewer, "market.gui-offer-remove")));
                }
                inv.setItem(OFFER_START + i, shown);
            } else if (isBuyer && manager.allowItemOffers()) {
                inv.setItem(OFFER_START + i, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE,
                        Lang.raw(viewer, "market.gui-offer-empty"),
                        List.of(Lang.raw(viewer, "market.gui-offer-empty-lore"))));
            }
        }

        inv.setItem(SLOT_INFO, MenuItems.item(Material.RESPAWN_ANCHOR,
                Lang.raw(viewer, "market.gui-info-name"),
                Lang.rawList(viewer, isBuyer ? "market.gui-info-buyer" : "market.gui-info-seller")));

        boolean meAccepted = isBuyer ? neg.buyerAccepted() : neg.sellerAccepted();
        inv.setItem(SLOT_ACCEPT, MenuItems.item(
                meAccepted ? Material.LIME_CONCRETE : Material.AMETHYST_BLOCK,
                Lang.raw(viewer, meAccepted ? "market.gui-accepted-name" : "market.gui-accept-name"),
                List.of(Lang.raw(viewer, "market.gui-accept-lore"))));

        inv.setItem(SLOT_STATUS, MenuItems.item(Material.HEART_OF_THE_SEA,
                Lang.raw(viewer, "market.gui-status-name"),
                List.of(
                        Lang.raw(viewer, neg.buyerAccepted()
                                ? "market.gui-status-accepted" : "market.gui-status-pending",
                                "player", neg.buyerName),
                        Lang.raw(viewer, neg.sellerAccepted()
                                ? "market.gui-status-accepted" : "market.gui-status-pending",
                                "player", neg.sellerName))));

        inv.setItem(SLOT_REJECT, MenuItems.item(Material.RED_CONCRETE,
                Lang.raw(viewer, "market.gui-reject-name"),
                List.of(Lang.raw(viewer, "market.gui-reject-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (stale(player)) return;
        boolean isBuyer = neg.buyer.equals(player.getUniqueId());
        if (slot == SLOT_MONEY && isBuyer) {
            double step = Math.max(1, Math.round(listing.price * 0.05));
            if (click.isShiftClick()) step *= 10;
            if (click.isRightClick()) step = -step;
            manager.offerAdjustMoney(player, neg, step);
        } else if (slot >= OFFER_START && slot < OFFER_START + Negotiation.MAX_OFFER_ITEMS && isBuyer) {
            manager.offerRemoveItem(player, neg, slot - OFFER_START);
        } else if (slot == SLOT_ACCEPT) {
            manager.acceptNegotiation(player, neg);
        } else if (slot == SLOT_REJECT) {
            manager.rejectNegotiation(player, neg);
        }
    }

    @Override
    public void onClickBottom(Player player, int slot, ClickType click) {
        if (stale(player)) return;
        if (neg.buyer.equals(player.getUniqueId())) {
            manager.offerAddItem(player, neg, slot);
        }
    }

    // La negociacion pudo terminar por el otro lado (venta, rechazo, expiracion) mientras
    // esta copia de la GUI seguia abierta: cualquier click sobre una negociacion muerta cierra.
    private boolean stale(Player player) {
        if (manager.negotiationOf(neg.listingId) != neg) {
            player.closeInventory();
            return true;
        }
        // El permiso se miraba al abrir la compra y nunca mas: a quien le quitaban el
        // mercado (o con el mercado apagado en caliente) seguia ofreciendo, aceptando y
        // cerrando ventas desde una GUI ya abierta. Se rechaza la negociacion, que es lo que
        // devuelve el trueque en deposito a su dueño.
        if (!manager.checkActive(player)) {
            manager.rejectNegotiation(player, neg);
            player.closeInventory();
            return true;
        }
        return false;
    }

    private static ItemStack head(Player viewer, java.util.UUID id, String name, String stateKey) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            PlayerHeads.apply(meta, id);
            meta.displayName(MenuItems.noItalic(MM.full(
                    Lang.raw(viewer, "market.gui-party-name", "player", name))));
            meta.lore(List.of(MenuItems.noItalic(MM.full(
                    Lang.raw(viewer, stateKey, "player", name)))));
        });
        return item;
    }

}
