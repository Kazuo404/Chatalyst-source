package dev.chatalyst.market;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trade DIRECTO jugador-a-jugador ({@code /trade <jugador>}): una GUI compartida donde ambos
 * ponen items y/o dinero y los dos confirman; nada se mueve hasta que las DOS partes marcan
 * "listo". Ambos deben estar ONLINE en ESTE servidor (el intercambio es en persona), y los
 * items ofrecidos estan en deposito hasta que el trade se ejecuta o se cancela.
 *
 * <p>Es una feature integrada que viene APAGADA ({@code Trade.Enabled: false}); el dueño la
 * enciende cuando quiere. Reutiliza el alijo del mercado como entrega segura: si al ejecutarse
 * ya no cabe todo, el sobrante queda en {@code /market claim}; nunca se toca un inventario
 * desde otra region ni se tiran items al mundo.
 */
public final class TradeManager {

    private final ChatalystPlugin plugin;
    // Se reutiliza su alijo (/market claim) para no perder items si una parte se desconecta.
    private final MarketManager market;
    private final DurableEscrow escrow;
    private final SettlementLedger settlements;
    private final java.util.Set<String> ambiguousSettlements = ConcurrentHashMap.newKeySet();

    // Trade activo por jugador (ambas partes apuntan al MISMO objeto). Un jugador solo puede
    // estar en un trade a la vez.
    private final Map<UUID, DirectTrade> active = new ConcurrentHashMap<>();
    // Solicitudes pendientes: destinatario -> (emisor, momento). Solo la ultima cuenta.
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean allowMoney;
    private volatile long requestTtlMs;

    private record Request(UUID from, String fromName, long time) {
    }

    public TradeManager(ChatalystPlugin plugin, MarketManager market) {
        this.plugin = plugin;
        this.market = market;
        this.escrow = new DurableEscrow(plugin,
                plugin.getStorageManager().perServerStore("trade_escrow"), market);
        this.settlements = market.settlementLedger();
        recoverTradeSettlements();
        this.escrow.recoverAll(settlements.blockedMarkers(SettlementLedger.Kind.TRADE));
    }

    public void load() {
        var cfg = plugin.getConfig();
        boolean wasEnabled = enabled;
        boolean moneyWasAllowed = allowMoney;
        // Desactivado de fabrica (como el mercado): el dueño lo enciende como novedad.
        boolean nextEnabled = cfg.getBoolean("Trade.Enabled", false);
        boolean nextAllowMoney = cfg.getBoolean("Trade.Allow_Money", true);
        enabled = nextEnabled;
        allowMoney = nextAllowMoney;
        requestTtlMs = requestTimeoutMillis(
                cfg.getLong("Trade.Request_Timeout_Seconds", 60));
        // Un reload no puede dejar viva una GUI bajo reglas antiguas. En especial, apagar
        // Allow_Money mientras habia dinero ofertado permitia confirmarlo igualmente.
        if (wasEnabled && (!nextEnabled || (moneyWasAllowed && !nextAllowMoney))) {
            for (DirectTrade trade : java.util.Set.copyOf(active.values())) {
                cancelInternal(trade, trade.a);
            }
            requests.clear();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean allowMoney() {
        return allowMoney && VaultHook.hasEconomy();
    }

    boolean canUse(Player player) {
        return enabled && player != null
                && player.hasPermission("chatalyst.command.trade");
    }

    /** Reautoriza una GUI abierta; si el rango/feature cambio, cancela y devuelve el escrow. */
    boolean ensureActive(Player player, DirectTrade trade) {
        if (canUse(player)) return true;
        if (trade != null && trade.sideOf(player.getUniqueId()) != null) {
            cancelInternal(trade, player.getUniqueId());
        }
        player.closeInventory();
        player.sendMessage(Lang.get(player,
                enabled ? "common.no-permission" : "trade.disabled"));
        return false;
    }

    static long requestTimeoutMillis(long configuredSeconds) {
        long seconds = Math.max(5L, Math.min(3_600L, configuredSeconds));
        return seconds * 1_000L;
    }

    /** Cancela (devolviendo el deposito) todo trade en curso al apagar el plugin. */
    public void shutdown() {
        for (DirectTrade t : List.copyOf(active.values())) {
            cancelInternal(t, null);
        }
        active.clear();
        requests.clear();
    }

    // ---- Solicitud / aceptacion -------------------------------------------------------------

    /** {@code /trade <jugador>}: pide un trade (o lo acepta si el otro ya te lo pidio). */
    public void request(Player from, Player to) {
        UUID fromId = from.getUniqueId();
        String fromName = NickManager.safeName(from);
        UUID toId = to.getUniqueId();
        String toName = NickManager.safeName(to);
        if (!canUse(from)) {
            from.sendMessage(Lang.get(from,
                    enabled ? "common.no-permission" : "trade.disabled"));
            return;
        }
        if (fromId.equals(toId)) {
            from.sendMessage(Lang.get(from, "trade.self"));
            return;
        }
        if (active.containsKey(fromId)) {
            from.sendMessage(Lang.get(from, "trade.already-trading"));
            return;
        }
        if (active.containsKey(toId)) {
            from.sendMessage(Lang.get(from, "trade.other-busy", "player", toName));
            return;
        }
        // Si el destinatario ya me habia pedido trade, mi /trade lo ACEPTA.
        Request pending = requests.get(fromId);
        if (pending != null && pending.from.equals(toId)
                && System.currentTimeMillis() - pending.time < requestTtlMs) {
            accept(from, toId);
            return;
        }
        Request request = new Request(fromId, fromName, System.currentTimeMillis());
        requests.put(toId, request);
        from.sendMessage(Lang.get(from, "trade.sent", "player", toName));
        FoliaTasks.entity(plugin, to,
                target -> target.sendMessage(Lang.get(target, "trade.received",
                        "player", fromName)),
                () -> requests.remove(toId, request));
    }

    /** El destinatario acepta la solicitud (boton o {@code /trade <emisor>}). */
    public void accept(Player accepter, UUID fromId) {
        if (!canUse(accepter)) {
            accepter.sendMessage(Lang.get(accepter,
                    enabled ? "common.no-permission" : "trade.disabled"));
            return;
        }
        Request req = requests.get(accepter.getUniqueId());
        if (req == null || !req.from.equals(fromId)
                || System.currentTimeMillis() - req.time >= requestTtlMs) {
            accepter.sendMessage(Lang.get(accepter, "trade.no-request"));
            return;
        }
        Player from = Bukkit.getPlayer(fromId);
        if (from == null) {
            accepter.sendMessage(Lang.get(accepter, "trade.requester-offline"));
            requests.remove(accepter.getUniqueId());
            return;
        }
        if (active.containsKey(fromId) || active.containsKey(accepter.getUniqueId())) {
            accepter.sendMessage(Lang.get(accepter, "trade.already-trading"));
            return;
        }
        // Alijo lleno en cualquiera de los dos: lo intercambiado que no quepa en el inventario
        // va al alijo compartido, y si ya esta al tope no habria donde entregarlo. Se rechaza
        // el trade y se pide vaciarlo con /market claim, para no perder nunca un item.
        boolean accepterFull = market.stashFull(accepter.getUniqueId());
        boolean fromFull = market.stashFull(fromId);
        if (accepterFull || fromFull) {
            accepter.sendMessage(accepterFull
                    ? Lang.get(accepter, "trade.stash-full-self")
                    : Lang.get(accepter, "trade.stash-full-other", "player", req.fromName));
            from.sendMessage(fromFull
                    ? Lang.get(from, "trade.stash-full-self")
                    : Lang.get(from, "trade.stash-full-other", "player", NickManager.safeName(accepter)));
            requests.remove(accepter.getUniqueId());
            return;
        }
        requests.remove(accepter.getUniqueId());
        // A = emisor original, B = quien acepta.
        DirectTrade trade = new DirectTrade(fromId, req.fromName,
                accepter.getUniqueId(), NickManager.safeName(accepter));
        // Atomico: los dos jugadores quedan ligados a la vez o ninguno.
        if (active.putIfAbsent(fromId, trade) != null) {
            accepter.sendMessage(Lang.get(accepter, "trade.already-trading"));
            return;
        }
        if (active.putIfAbsent(accepter.getUniqueId(), trade) != null) {
            active.remove(fromId, trade);
            accepter.sendMessage(Lang.get(accepter, "trade.already-trading"));
            return;
        }
        openMenu(from, trade);
        openMenu(accepter, trade);
    }

    /** Acepta la ultima solicitud recibida (sin nombrar al emisor). */
    public void acceptLast(Player p) {
        Request req = requests.get(p.getUniqueId());
        if (req == null || System.currentTimeMillis() - req.time >= requestTtlMs) {
            p.sendMessage(Lang.get(p, "trade.no-request"));
            return;
        }
        accept(p, req.from);
    }

    /** Rechaza la ultima solicitud recibida. */
    public void deny(Player p) {
        Request req = requests.remove(p.getUniqueId());
        if (req == null) {
            p.sendMessage(Lang.get(p, "trade.no-request"));
            return;
        }
        p.sendMessage(Lang.get(p, "trade.denied-you", "player", req.fromName));
        notify(req.from, "trade.denied-by", "player", NickManager.safeName(p));
    }

    public DirectTrade tradeOf(UUID who) {
        return active.get(who);
    }

    private void openMenu(Player p, DirectTrade trade) {
        UUID id = p.getUniqueId();
        FoliaTasks.entity(plugin, p, viewer -> Menu.open(viewer, new TradeMenu(this, trade)),
                () -> abandon(id));
    }

    // ---- Edicion de la oferta ---------------------------------------------------------------

    public void offerAddItem(Player p, DirectTrade trade, int invSlot) {
        DirectTrade.Side side = trade.sideOf(p.getUniqueId());
        if (side == null) return;
        ItemStack item = p.getInventory().getItem(invSlot);
        if (item == null || item.getType().isAir()) return;
        synchronized (trade) {
            if (trade.itemCount(side) >= DirectTrade.MAX_ITEMS) {
                p.sendMessage(Lang.get(p, "trade.offer-full"));
                return;
            }
        }
        ItemStack deposited = escrow.deposit(p, invSlot, item);
        if (deposited == null) {
            p.sendMessage(Lang.get(p, "trade.storage-error"));
            return;
        }
        synchronized (trade) {
            if (!trade.addItem(side, deposited)) {
                escrow.release(deposited, p.getUniqueId());
                return;
            }
        }
        refresh(trade);
    }

    public void offerRemoveItem(Player p, DirectTrade trade, int index) {
        DirectTrade.Side side = trade.sideOf(p.getUniqueId());
        if (side == null) return;
        synchronized (trade) {
            ItemStack item = trade.removableItem(side, index);
            if (item == null) return;
            // El stack permanece en el contrato hasta que el journal confirme su destino.
            // Asi un fallo de storage no lo vuelve invisible en una oferta aun abierta.
            if (!escrow.release(item, p.getUniqueId())) {
                p.sendMessage(Lang.get(p, "trade.storage-error"));
                refresh(trade);
                return;
            }
            trade.removeItem(side, index);
        }
        market.deliverStashIfAny(p.getUniqueId());
        refresh(trade);
    }

    public void offerAdjustMoney(Player p, DirectTrade trade, double delta) {
        DirectTrade.Side side = trade.sideOf(p.getUniqueId());
        if (side == null || !allowMoney()) return;
        synchronized (trade) {
            trade.adjustMoney(side, delta);
        }
        refresh(trade);
    }

    /** Fija el dinero ofrecido sin permitir que la GUI prometa mas que el saldo disponible. */
    public void offerSetMoney(Player p, DirectTrade trade, double amount) {
        DirectTrade.Side side = trade.sideOf(p.getUniqueId());
        if (side == null || !allowMoney() || !Double.isFinite(amount)) return;
        double safe = Math.max(0, Math.min(amount, availableBalance(p)));
        synchronized (trade) {
            trade.setMoney(side, safe);
        }
        refresh(trade);
    }

    double availableBalance(Player player) {
        if (!allowMoney() || player == null) return 0;
        double balance = VaultHook.balance(player);
        return Double.isFinite(balance) ? Math.max(0, balance) : 0;
    }

    /** Una parte marca "listo". Cuando ambas lo estan, el trade se ejecuta (una sola vez). */
    public void setReady(Player p, DirectTrade trade) {
        DirectTrade.Side side = trade.sideOf(p.getUniqueId());
        if (side == null) return;
        // Anti-estafa cambiar-e-insta-confirmar: tras CUALQUIER cambio de oferta hay una
        // ventana en la que no se puede confirmar, para que el otro lado vea el cambio
        // renderizado antes de que un click tardio de "listo" selle una oferta distinta.
        DirectTrade.Confirmation result = trade.confirmIfStable(side, 1_500L);
        if (result == DirectTrade.Confirmation.TOO_RECENT) {
            p.sendMessage(Lang.get(p, "trade.offer-just-changed"));
            refresh(trade);
            return;
        }
        if (result == DirectTrade.Confirmation.ALREADY_READY) return;
        if (result == DirectTrade.Confirmation.SEALED) {
            execute(trade);
        } else {
            notify(trade.other(p.getUniqueId()), "trade.other-ready",
                    "player", NickManager.safeName(p));
            refresh(trade);
        }
    }

    public void cancel(Player p, DirectTrade trade) {
        if (trade.sideOf(p.getUniqueId()) == null) return;
        cancelInternal(trade, p.getUniqueId());
    }

    /** Cancela todo trade del jugador (al desconectarse). */
    public void abandon(UUID who) {
        DirectTrade trade = active.get(who);
        if (trade != null) cancelInternal(trade, who);
        requests.remove(who);
        requests.values().removeIf(r -> r.from.equals(who));
    }

    // ---- Ejecucion / cancelacion ------------------------------------------------------------

    private synchronized void execute(DirectTrade trade) {
        // confirm() ya sello el objeto atomica e indivisiblemente con la segunda
        // confirmacion. A partir de aqui ninguna oferta puede mutar ni una cancelacion ganar.
        active.remove(trade.a, trade);
        active.remove(trade.b, trade);

        List<ItemStack> itemsA = trade.drain(DirectTrade.Side.A); // A ofrece -> los recibe B
        List<ItemStack> itemsB = trade.drain(DirectTrade.Side.B); // B ofrece -> los recibe A
        double moneyA = trade.money(DirectTrade.Side.A);
        double moneyB = trade.money(DirectTrade.Side.B);

        // Tras sellar, no consultamos Entity de la otra region. Una desconexion simultanea
        // no invalida lo ya confirmado: dinero e items se resuelven por UUID/ledgers y el
        // receptor recoge del alijo al volver.
        // Solo hace falta transferir el NETO: A paga moneyA y recibe moneyB (y viceversa).
        // Reducirlo a un retiro+abono permite compensar de forma completa si Vault rechaza
        // el credito. Ningun item cruza hasta que el resultado es SUCCESS.
        double net = moneyA - moneyB;
        EconomyTransaction.Result payment = EconomyTransaction.Result.SUCCESS;
        boolean delivered;
        List<SettlementLedger.Transfer> transfers = new java.util.ArrayList<>();
        for (ItemStack item : itemsA) transfers.add(SettlementLedger.Transfer.of(
                SettlementLedger.Source.ESCROW, trade.a, trade.b,
                market.escrowMarker(item)));
        for (ItemStack item : itemsB) transfers.add(SettlementLedger.Transfer.of(
                SettlementLedger.Source.ESCROW, trade.b, trade.a,
                market.escrowMarker(item)));
        if (net == 0) {
            if (transfers.isEmpty()) {
                delivered = true;
            } else {
                SettlementLedger.Settlement prepared = settlements.create(
                        SettlementLedger.Kind.TRADE, trade.a, trade.b, 0, -1, transfers);
                SettlementLedger.Settlement paid = prepared == null ? null
                        : settlements.transition(prepared, SettlementLedger.Status.PAID);
                delivered = paid != null && finalizeTradeSettlement(paid);
                if (prepared == null) {
                    returnItems(trade.a, itemsA);
                    returnItems(trade.b, itemsB);
                }
            }
        } else {
            UUID payer = net > 0 ? trade.a : trade.b;
            UUID payee = net > 0 ? trade.b : trade.a;
            SettlementLedger.Settlement settlement = settlements.create(
                    SettlementLedger.Kind.TRADE, payer, payee, Math.abs(net), -1, transfers);
            if (settlement == null) {
                returnItems(trade.a, itemsA);
                returnItems(trade.b, itemsB);
                closeMenus(trade);
                notify(trade.a, "trade.payment-provider-failed");
                notify(trade.b, "trade.payment-provider-failed");
                return;
            }
            SettlementLedger.Settlement processing = settlements.transition(
                    settlement, SettlementLedger.Status.PROCESSING);
            if (processing == null) {
                SettlementLedger.Settlement current = settlements.get(settlement.id());
                if (current != null && current.status() == SettlementLedger.Status.PREPARED) {
                    SettlementLedger.Settlement unpaid = settlements.transition(
                            current, SettlementLedger.Status.UNPAID);
                    if (unpaid != null) finalizeTradeSettlement(unpaid);
                }
                // Si el WAL PREPARED sigue sin poder cambiarse, los markers permanecen
                // bloqueados y el arranque lo resolvera como UNPAID.
                closeMenus(trade);
                notify(trade.a, "trade.payment-provider-failed");
                notify(trade.b, "trade.payment-provider-failed");
                return;
            }
            payment = market.transferMoney(payer, payee, Math.abs(net));
            SettlementLedger.Settlement resolved = settlements.transition(processing,
                    payment == EconomyTransaction.Result.SUCCESS
                            ? SettlementLedger.Status.PAID : SettlementLedger.Status.UNPAID);
            if (resolved == null) {
                ambiguousSettlements.add(processing.id());
                plugin.getLogger().severe("Trade settlement " + processing.id()
                        + " stopped in PROCESSING. Items remain locked until /trade settlements"
                        + " paid|unpaid confirms the Vault result.");
                closeMenus(trade);
                notify(trade.a, "trade.payment-pending");
                notify(trade.b, "trade.payment-pending");
                return;
            }
            delivered = finalizeTradeSettlement(resolved);
        }
        if (payment != EconomyTransaction.Result.SUCCESS || !delivered) {
            UUID payer = net >= 0 ? trade.a : trade.b;
            if (payment == EconomyTransaction.Result.WITHDRAW_FAILED) {
                notify(payer, "trade.you-cannot-pay");
                notify(payer.equals(trade.a) ? trade.b : trade.a, "trade.other-cannot-pay",
                        "player", payer.equals(trade.a) ? trade.nameA : trade.nameB);
            } else if (payment == EconomyTransaction.Result.SUCCESS) {
                notify(trade.a, "trade.payment-pending");
                notify(trade.b, "trade.payment-pending");
            } else {
                notify(trade.a, "trade.payment-provider-failed");
                notify(trade.b, "trade.payment-provider-failed");
            }
            closeMenus(trade);
            return;
        }
        closeMenus(trade);
        notify(trade.a, "trade.done", "player", trade.nameB);
        notify(trade.b, "trade.done", "player", trade.nameA);
        // Lo intercambiado se deposita en el alijo compartido (escrow.release -> stashFor). Se
        // entrega y avisa a ambas partes ya conectadas para que no quede olvidado sin aviso.
        market.deliverStashIfAny(trade.a);
        market.deliverStashIfAny(trade.b);
    }

    private void cancelInternal(DirectTrade trade, UUID by) {
        if (!trade.tryClose()) return; // ya se ejecuto o se cancelo por otra via
        active.remove(trade.a, trade);
        active.remove(trade.b, trade);
        if (by == null) {
            // Durante onDisable no se deben programar tareas de entidad: el scheduler puede
            // rechazarlas o cancelarlas antes de entregar. Persistimos ambos depositos en el
            // alijo mientras storage sigue abierto; se recuperan con /market claim al volver.
            releaseItems(trade.a, trade.drain(DirectTrade.Side.A));
            releaseItems(trade.b, trade.drain(DirectTrade.Side.B));
            return;
        }
        returnItems(trade.a, trade.drain(DirectTrade.Side.A));
        returnItems(trade.b, trade.drain(DirectTrade.Side.B));
        closeMenus(trade);
        notify(trade.a, "trade.cancelled", "player", trade.nameB);
        notify(trade.b, "trade.cancelled", "player", trade.nameA);
    }

    // ---- Entrega de items -------------------------------------------------------------------

    private void returnItems(UUID who, List<ItemStack> items) {
        releaseItems(who, items);
    }

    private void releaseItems(UUID owner, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (!escrow.release(item, owner)) {
                plugin.getLogger().severe("Could not release trade escrow item to " + owner);
            }
        }
    }

    private synchronized void recoverTradeSettlements() {
        for (SettlementLedger.Settlement original : settlements.all(SettlementLedger.Kind.TRADE)) {
            SettlementLedger.Settlement settlement = original;
            if (settlement.status() == SettlementLedger.Status.PREPARED) {
                settlement = settlements.transition(settlement, SettlementLedger.Status.UNPAID);
                if (settlement == null) continue;
            }
            if (settlement.status() == SettlementLedger.Status.PROCESSING) {
                if (ambiguousSettlements.add(settlement.id())) {
                    plugin.getLogger().severe("Trade settlement " + settlement.id()
                            + " was interrupted during Vault payment. Items remain locked;"
                            + " use /trade settlements paid|unpaid <id> after verification.");
                }
                continue;
            }
            if (finalizeTradeSettlement(settlement)) ambiguousSettlements.remove(settlement.id());
        }
    }

    private boolean finalizeTradeSettlement(SettlementLedger.Settlement settlement) {
        boolean paid = settlement.status() == SettlementLedger.Status.PAID;
        if (!paid && settlement.status() != SettlementLedger.Status.UNPAID) return false;
        for (SettlementLedger.Transfer transfer : settlement.transfers()) {
            UUID owner = paid ? transfer.paidOwner() : transfer.originalOwner();
            if (!escrow.release(transfer.marker(), owner)) return false;
        }
        return settlements.remove(settlement);
    }

    public List<SettlementView> pendingSettlements() {
        recoverTradeSettlements();
        return settlements.all(SettlementLedger.Kind.TRADE).stream()
                .map(s -> new SettlementView(s.id(), s.status().name(), s.payer(), s.payee(),
                        s.amount(), s.created())).toList();
    }

    public synchronized MarketManager.CreditReconcileResult reconcileSettlement(String id,
            boolean confirmedPaid) {
        SettlementLedger.Settlement settlement = settlements.get(id);
        if (settlement == null || settlement.kind() != SettlementLedger.Kind.TRADE) {
            return MarketManager.CreditReconcileResult.NOT_FOUND;
        }
        if (settlement.status() == SettlementLedger.Status.PROCESSING) {
            settlement = settlements.transition(settlement, confirmedPaid
                    ? SettlementLedger.Status.PAID : SettlementLedger.Status.UNPAID);
            if (settlement == null) return MarketManager.CreditReconcileResult.STORAGE_FAILURE;
        } else if ((settlement.status() == SettlementLedger.Status.PAID) != confirmedPaid
                && settlement.status() != SettlementLedger.Status.PREPARED) {
            return MarketManager.CreditReconcileResult.INVALID;
        } else if (settlement.status() == SettlementLedger.Status.PREPARED) {
            if (confirmedPaid) return MarketManager.CreditReconcileResult.INVALID;
            settlement = settlements.transition(settlement, SettlementLedger.Status.UNPAID);
            if (settlement == null) return MarketManager.CreditReconcileResult.STORAGE_FAILURE;
        }
        if (!finalizeTradeSettlement(settlement)) {
            return MarketManager.CreditReconcileResult.STORAGE_FAILURE;
        }
        ambiguousSettlements.remove(id);
        return confirmedPaid ? MarketManager.CreditReconcileResult.CLEARED
                : MarketManager.CreditReconcileResult.QUEUED;
    }

    public record SettlementView(String id, String status, UUID payer, UUID payee,
                                 double amount, long created) {
    }

    // ---- GUI helpers ------------------------------------------------------------------------

    /** Reconstruye la GUI de ambas partes si la tienen abierta. */
    void refresh(DirectTrade trade) {
        for (UUID who : List.of(trade.a, trade.b)) {
            Player p = Bukkit.getPlayer(who);
            if (p == null) continue;
            FoliaTasks.entity(plugin, p, viewer -> {
                var top = viewer.getOpenInventory().getTopInventory();
                if (!(top.getHolder() instanceof MenuHolder holder)) return;
                if (holder.getMenu() instanceof TradeMenu tm && tm.trade() == trade) {
                    top.clear();
                    tm.build(top, viewer);
                } else if (holder.getMenu() instanceof TradeMoneyMenu money
                        && money.trade() == trade) {
                    top.clear();
                    money.build(top, viewer);
                }
            });
        }
    }

    private void closeMenus(DirectTrade trade) {
        for (UUID who : List.of(trade.a, trade.b)) {
            Player p = Bukkit.getPlayer(who);
            if (p == null) continue;
            FoliaTasks.entity(plugin, p, viewer -> {
                var top = viewer.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof MenuHolder holder
                        && ((holder.getMenu() instanceof TradeMenu tm && tm.trade() == trade)
                        || (holder.getMenu() instanceof TradeMoneyMenu money
                        && money.trade() == trade))) {
                    viewer.closeInventory();
                }
            });
        }
    }

    private void notify(UUID who, String key, Object... repl) {
        Player p = Bukkit.getPlayer(who);
        if (p == null) return;
        FoliaTasks.entity(plugin, p, v -> v.sendMessage(Lang.get(v, key, repl)));
    }
}
