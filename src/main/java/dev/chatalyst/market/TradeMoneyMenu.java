package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** Tesoreria del trade: ajustes precisos, vaciar, ofrecer saldo maximo y volver al contrato. */
final class TradeMoneyMenu implements Menu {

    private static final int[] MINUS_SLOTS = {9, 10, 11, 12};
    private static final int[] PLUS_SLOTS = {14, 15, 16, 17};
    private static final double[] MINUS_STEPS = {-1_000, -100, -10, -1};
    private static final double[] PLUS_STEPS = {1, 10, 100, 1_000};
    private static final int SLOT_BALANCE = 4;
    private static final int SLOT_CURRENT = 13;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_RESET = 22;
    private static final int SLOT_MAX = 26;

    private final TradeManager manager;
    private final DirectTrade trade;

    TradeMoneyMenu(TradeManager manager, DirectTrade trade) {
        this.manager = manager;
        this.trade = trade;
    }

    DirectTrade trade() {
        return trade;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("trade.money-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "trade.money-title"));
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        DirectTrade.Side side = trade.sideOf(viewer.getUniqueId());
        if (side == null) return;
        for (int slot = 0; slot < size(); slot++) {
            inv.setItem(slot, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of()));
        }
        double balance = manager.availableBalance(viewer);
        double offered = trade.money(side);
        inv.setItem(SLOT_BALANCE, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "trade.money-balance-name"),
                Lang.rawList(viewer, "trade.money-balance-lore",
                        "balance", MarketManager.money(balance),
                        "offered", MarketManager.money(offered),
                        "remaining", MarketManager.money(Math.max(0, balance - offered)))));

        for (int i = 0; i < MINUS_SLOTS.length; i++) {
            inv.setItem(MINUS_SLOTS[i], MenuItems.item(Material.MAGENTA_DYE,
                    Lang.raw(viewer, "trade.money-minus",
                            "amount", MarketManager.money(-MINUS_STEPS[i])),
                    Lang.rawList(viewer, "trade.money-adjust-lore")));
        }
        for (int i = 0; i < PLUS_SLOTS.length; i++) {
            inv.setItem(PLUS_SLOTS[i], MenuItems.item(Material.AMETHYST_SHARD,
                    Lang.raw(viewer, "trade.money-plus",
                            "amount", MarketManager.money(PLUS_STEPS[i])),
                    Lang.rawList(viewer, "trade.money-adjust-lore")));
        }
        inv.setItem(SLOT_CURRENT, MenuItems.item(Material.AMETHYST_CLUSTER,
                Lang.raw(viewer, "trade.money-current-name",
                        "amount", MarketManager.money(offered)),
                Lang.rawList(viewer, "trade.money-current-lore")));
        inv.setItem(SLOT_BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        inv.setItem(SLOT_RESET, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "trade.money-reset-name"),
                Lang.rawList(viewer, "trade.money-reset-lore")));
        inv.setItem(SLOT_MAX, MenuItems.item(Material.ENDER_EYE,
                Lang.raw(viewer, "trade.money-max-name"),
                Lang.rawList(viewer, "trade.money-max-lore",
                        "balance", MarketManager.money(balance))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!manager.ensureActive(player, trade)) return;
        // Igual que el contrato: un trade ya ejecutado o cancelado no se sigue editando, y
        // "volver" no debe reabrir el contrato de un trato muerto.
        if (manager.tradeOf(player.getUniqueId()) != trade) {
            player.closeInventory();
            return;
        }
        DirectTrade.Side side = trade.sideOf(player.getUniqueId());
        if (side == null) return;
        for (int i = 0; i < MINUS_SLOTS.length; i++) {
            if (slot == MINUS_SLOTS[i]) {
                manager.offerSetMoney(player, trade, trade.money(side) + MINUS_STEPS[i]);
                rebuild(player);
                return;
            }
        }
        for (int i = 0; i < PLUS_SLOTS.length; i++) {
            if (slot == PLUS_SLOTS[i]) {
                manager.offerSetMoney(player, trade, trade.money(side) + PLUS_STEPS[i]);
                rebuild(player);
                return;
            }
        }
        switch (slot) {
            case SLOT_RESET -> {
                manager.offerSetMoney(player, trade, 0);
                rebuild(player);
            }
            case SLOT_MAX -> {
                manager.offerSetMoney(player, trade, manager.availableBalance(player));
                rebuild(player);
            }
            case SLOT_BACK -> Menu.open(player, new TradeMenu(manager, trade));
            default -> {
            }
        }
    }

    private void rebuild(Player player) {
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof MenuHolder holder && holder.getMenu() == this) {
            top.clear();
            build(top, player);
        }
    }
}
