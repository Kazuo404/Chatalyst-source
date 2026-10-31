package dev.chatalyst.market;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /trade (alias /intercambio): pide un intercambio directo a otro jugador, o lo acepta/rechaza.
 * {@code /trade <jugador>} pide (o acepta si ese jugador ya te lo pidio); {@code /trade accept}
 * y {@code /trade deny} responden a la ultima solicitud recibida.
 */
public final class TradeCommand implements CommandExecutor, TabCompleter {

    private final TradeManager manager;

    public TradeCommand(TradeManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("settlements")
                || args[0].equalsIgnoreCase("liquidaciones"))) {
            handleSettlements(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "trade.players-only"));
            return true;
        }
        if (!manager.isEnabled()) {
            p.sendMessage(Lang.get(p, "trade.disabled"));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(Lang.get(p, "trade.usage"));
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("accept") || first.equals("aceptar")) {
            Player requester = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
            if (requester != null) {
                manager.accept(p, requester.getUniqueId());
            } else {
                manager.acceptLast(p);
            }
            return true;
        }
        if (first.equals("deny") || first.equals("rechazar") || first.equals("denegar")) {
            manager.deny(p);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            // Puede que hayan escrito el apodo: resolvemos al jugador real online.
            target = NickManager.online(args[0]);
        }
        if (target == null) {
            p.sendMessage(Lang.get(p, "trade.player-not-found", "player", args[0]));
            return true;
        }
        manager.request(p, target);
        return true;
    }

    private void handleSettlements(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chatalyst.market.reconcile")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            List<TradeManager.SettlementView> rows = manager.pendingSettlements();
            if (rows.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "trade.admin-settlement-empty"));
                return;
            }
            sender.sendMessage(Lang.get(sender, "trade.admin-settlement-header",
                    "count", rows.size()));
            for (TradeManager.SettlementView row : rows) {
                sender.sendMessage(Lang.get(sender, "trade.admin-settlement-entry",
                        "id", row.id(), "status", row.status(), "payer", row.payer(),
                        "payee", row.payee(), "amount", row.amount()));
            }
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.get(sender, "trade.admin-settlement-usage"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean paid;
        if (List.of("paid", "pagado").contains(action)) paid = true;
        else if (List.of("unpaid", "no-pagado", "nopagado").contains(action)) paid = false;
        else {
            sender.sendMessage(Lang.get(sender, "trade.admin-settlement-usage"));
            return;
        }
        MarketManager.CreditReconcileResult result = manager.reconcileSettlement(args[2], paid);
        String key = switch (result) {
            case CLEARED, QUEUED -> "trade.admin-settlement-done";
            case NOT_FOUND -> "trade.admin-settlement-not-found";
            case INVALID -> "trade.admin-settlement-invalid";
            case STORAGE_FAILURE -> "trade.admin-settlement-storage-error";
        };
        sender.sendMessage(Lang.get(sender, key));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String low = args[0].toLowerCase(Locale.ROOT);
            for (String s : Lang.isSpanish(sender)
                    ? List.of("aceptar", "rechazar", "liquidaciones")
                    : List.of("accept", "deny", "settlements")) {
                if (s.startsWith(low)) out.add(s);
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                String shown = NickManager.suggestedName(online);
                if (shown.toLowerCase(Locale.ROOT).startsWith(low)) out.add(shown);
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("settlements")
                || args[0].equalsIgnoreCase("liquidaciones"))
                && sender.hasPermission("chatalyst.market.reconcile")) {
            return List.of("list", "paid", "unpaid").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("settlements")
                || args[0].equalsIgnoreCase("liquidaciones"))
                && sender.hasPermission("chatalyst.market.reconcile")) {
            return manager.pendingSettlements().stream().map(TradeManager.SettlementView::id)
                    .filter(id -> id.startsWith(args[2])).toList();
        }
        return List.of();
    }
}
