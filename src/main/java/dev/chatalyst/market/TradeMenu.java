package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.PlayerHeads;
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
 * Contrato visual del trade directo. Cada persona ve su oferta editable a la izquierda y la
 * contraparte, sellada para lectura, a la derecha. La franja central resume ambos lados y
 * deja explicita la regla de doble confirmacion antes de mover un solo item o moneda.
 */
final class TradeMenu implements Menu {

    private static final int[] LEFT = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21};
    private static final int[] RIGHT = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26};
    private static final int[] DIVIDER = {4, 13, 22, 31, 40};
    private static final int SLOT_MY_SUMMARY = 29;
    private static final int SLOT_SECURITY = 31;
    private static final int SLOT_THEIR_SUMMARY = 33;
    private static final int SLOT_MY_HEAD = 36;
    private static final int SLOT_MY_MONEY = 38;
    private static final int SLOT_THEIR_MONEY = 42;
    private static final int SLOT_THEIR_HEAD = 44;
    private static final int SLOT_READY = 47;
    private static final int SLOT_STATUS = 49;
    private static final int SLOT_CANCEL = 51;

    private final TradeManager manager;
    private final DirectTrade trade;

    TradeMenu(TradeManager manager, DirectTrade trade) {
        this.manager = manager;
        this.trade = trade;
    }

    DirectTrade trade() {
        return trade;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("trade.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "trade.gui-title"));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        DirectTrade.Side me = trade.sideOf(viewer.getUniqueId());
        if (me == null) return;
        DirectTrade.Side them = me == DirectTrade.Side.A ? DirectTrade.Side.B : DirectTrade.Side.A;

        for (int slot : DIVIDER) {
            inv.setItem(slot, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of()));
        }

        List<ItemStack> mine = trade.view(me);
        for (int i = 0; i < LEFT.length; i++) {
            if (i < mine.size()) {
                ItemStack shown = mine.get(i).clone();
                MenuItems.appendLore(shown, List.of("", Lang.raw(viewer, "trade.gui-take-back")));
                inv.setItem(LEFT[i], shown);
            } else {
                inv.setItem(LEFT[i], MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE,
                        Lang.raw(viewer, "trade.gui-empty"),
                        Lang.rawList(viewer, "trade.gui-empty-lore")));
            }
        }

        List<ItemStack> theirs = trade.view(them);
        for (int i = 0; i < RIGHT.length; i++) {
            if (i < theirs.size()) {
                ItemStack shown = theirs.get(i).clone();
                MenuItems.appendLore(shown, List.of("", Lang.raw(viewer, "trade.gui-read-only")));
                inv.setItem(RIGHT[i], shown);
            } else {
                inv.setItem(RIGHT[i], MenuItems.item(Material.BLACK_STAINED_GLASS_PANE,
                        Lang.raw(viewer, "trade.gui-their-empty"),
                        Lang.rawList(viewer, "trade.gui-their-empty-lore")));
            }
        }

        inv.setItem(SLOT_MY_SUMMARY, summary(viewer, me, true));
        inv.setItem(SLOT_SECURITY, MenuItems.item(Material.RESPAWN_ANCHOR,
                Lang.raw(viewer, "trade.gui-security-name"),
                Lang.rawList(viewer, "trade.gui-security-lore")));
        inv.setItem(SLOT_THEIR_SUMMARY, summary(viewer, them, false));

        inv.setItem(SLOT_MY_HEAD, head(viewer, viewer.getUniqueId(), trade.name(me),
                trade.isReady(me) ? "trade.gui-you-ready" : "trade.gui-you-editing"));
        inv.setItem(SLOT_THEIR_HEAD, head(viewer, trade.uuid(them), trade.name(them),
                trade.isReady(them) ? "trade.gui-them-ready" : "trade.gui-them-editing"));

        if (manager.allowMoney()) {
            inv.setItem(SLOT_MY_MONEY, MenuItems.item(Material.AMETHYST_SHARD,
                    Lang.raw(viewer, "trade.gui-your-money",
                            "amount", MarketManager.money(trade.money(me))),
                    Lang.rawList(viewer, "trade.gui-money-controls",
                            "balance", MarketManager.money(manager.availableBalance(viewer)))));
            inv.setItem(SLOT_THEIR_MONEY, MenuItems.item(Material.AMETHYST_CLUSTER,
                    Lang.raw(viewer, "trade.gui-their-money",
                            "amount", MarketManager.money(trade.money(them))),
                    Lang.rawList(viewer, "trade.gui-their-money-lore")));
        }

        boolean iReady = trade.isReady(me);
        List<String> readyLore = new ArrayList<>(Lang.rawList(viewer, "trade.gui-ready-lore"));
        if (iReady) readyLore.add(Lang.raw(viewer, "trade.gui-ready-locked"));
        inv.setItem(SLOT_READY, MenuItems.item(
                iReady ? Material.LIME_CONCRETE : Material.AMETHYST_BLOCK,
                Lang.raw(viewer, iReady ? "trade.gui-ready-done" : "trade.gui-ready"),
                readyLore));
        inv.setItem(SLOT_STATUS, MenuItems.item(Material.HEART_OF_THE_SEA,
                Lang.raw(viewer, "trade.gui-status"),
                List.of(
                        Lang.raw(viewer, trade.isReady(me)
                                ? "trade.gui-status-ready" : "trade.gui-status-pending",
                                "player", trade.name(me)),
                        Lang.raw(viewer, trade.isReady(them)
                                ? "trade.gui-status-ready" : "trade.gui-status-pending",
                                "player", trade.name(them)),
                        "",
                        Lang.raw(viewer, trade.bothReady()
                                ? "trade.gui-status-sealed" : "trade.gui-status-safe"))));
        inv.setItem(SLOT_CANCEL, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "trade.gui-cancel"),
                Lang.rawList(viewer, "trade.gui-cancel-lore")));
    }

    private ItemStack summary(Player viewer, DirectTrade.Side side, boolean mine) {
        return MenuItems.item(mine ? Material.BUNDLE : Material.ENDER_CHEST,
                Lang.raw(viewer, mine ? "trade.gui-my-summary" : "trade.gui-their-summary"),
                Lang.rawList(viewer, "trade.gui-summary-lore",
                        "player", trade.name(side),
                        "items", trade.itemCount(side),
                        "money", MarketManager.money(trade.money(side))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (stale(player)) return;
        if (slot == SLOT_READY) {
            manager.setReady(player, trade);
            return;
        }
        if (slot == SLOT_CANCEL) {
            manager.cancel(player, trade);
            return;
        }
        if (slot == SLOT_MY_MONEY && manager.allowMoney()) {
            Menu.open(player, new TradeMoneyMenu(manager, trade));
            return;
        }
        for (int i = 0; i < LEFT.length; i++) {
            if (LEFT[i] == slot) {
                manager.offerRemoveItem(player, trade, i);
                return;
            }
        }
    }

    @Override
    public void onClickBottom(Player player, int slot, ClickType click) {
        if (stale(player)) return;
        manager.offerAddItem(player, trade, slot);
    }

    private boolean stale(Player player) {
        if (!manager.ensureActive(player, trade)) return true;
        if (manager.tradeOf(player.getUniqueId()) == trade) return false;
        player.closeInventory();
        return true;
    }

    private static ItemStack head(Player viewer, java.util.UUID id, String name, String stateKey) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            PlayerHeads.apply(meta, id);
            meta.displayName(MenuItems.noItalic(MM.full(
                    Lang.raw(viewer, "trade.gui-player-name", "player", name))));
            meta.lore(Lang.rawList(viewer, stateKey).stream()
                    .map(line -> MenuItems.noItalic(MM.full(line))).toList());
        });
        return item;
    }
}
