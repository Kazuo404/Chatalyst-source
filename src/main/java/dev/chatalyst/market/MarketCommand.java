package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /market (alias /mercado): el mercado nativo. Sin argumentos abre la GUI; los subcomandos
 * (bilingues, como el resto del plugin) cubren lo que necesita un click o un valor escrito:
 * publicar con precio, pujar con cantidad y los botones clicables de los anuncios del chat.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private final MarketManager manager;

    public MarketCommand(MarketManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("credits")) {
            handleCredits(sender, args);
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("settlements")
                || args[0].equalsIgnoreCase("liquidaciones"))) {
            handleSettlements(sender, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "market.players-only"));
            return true;
        }
        if (args.length == 0) {
            if (manager.checkActive(p)) {
                Menu.open(p, new MarketMenu(manager, 0));
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "publish", "publicar" -> manager.openPublish(p);
            case "sell", "vender" -> {
                // Sin precio: abre la GUI de publicacion (elegir tipo/precio/duracion visual).
                if (args.length < 2) {
                    manager.openPublish(p);
                    return true;
                }
                Double price = parseAmount(p, args, 1, "market.sell-usage");
                if (price != null) manager.create(p, MarketListing.Type.SALE, price, 0);
            }
            case "auction", "subastar", "subasta" -> {
                Double price = parseAmount(p, args, 1, "market.auction-usage");
                if (price == null) return true;
                long minutes = 0;
                if (args.length >= 3) {
                    try {
                        minutes = Long.parseLong(args[2]);
                    } catch (NumberFormatException ex) {
                        p.sendMessage(Lang.get(p, "market.auction-usage"));
                        return true;
                    }
                }
                manager.create(p, MarketListing.Type.AUCTION, price, minutes);
            }
            case "buy", "comprar" -> {
                Integer id = parseId(p, args, 1);
                if (id != null) manager.requestBuy(p, id);
            }
            case "bid", "pujar", "puja" -> {
                Integer id = parseId(p, args, 1);
                if (id == null) return true;
                Double amount = parseAmount(p, args, 2, "market.bid-usage");
                if (amount != null) manager.bid(p, id, amount);
            }
            case "view", "ver" -> {
                Integer id = parseId(p, args, 1);
                if (id != null) manager.view(p, id);
            }
            case "cancel", "cancelar" -> {
                Integer id = parseId(p, args, 1);
                if (id != null) manager.cancel(p, id);
            }
            case "claim", "recoger" -> manager.claim(p);
            case "history", "historial" -> manager.sendHistory(p);
            default -> {
                for (String line : Lang.rawList(p, "market.help")) {
                    p.sendMessage(dev.chatalyst.util.MM.full(line));
                }
            }
        }
        return true;
    }

    private void handleCredits(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chatalyst.market.reconcile")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            List<MarketManager.CreditView> credits = manager.pendingCredits();
            if (credits.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "market.admin-credit-empty"));
                return;
            }
            sender.sendMessage(Lang.get(sender, "market.admin-credit-header",
                    "count", credits.size()));
            for (MarketManager.CreditView credit : credits) {
                sender.sendMessage(Lang.get(sender, "market.admin-credit-entry",
                        "id", credit.id(), "status", credit.status(),
                        "player", credit.player(), "amount", credit.amount()));
            }
            return;
        }
        if (args.length < 3) {
            sendCreditUsage(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean paid;
        if (List.of("retry", "reintentar").contains(action)) paid = false;
        else if (List.of("paid", "pagado", "clear", "limpiar").contains(action)) paid = true;
        else {
            sendCreditUsage(sender);
            return;
        }
        MarketManager.CreditReconcileResult result = manager.reconcileCredit(args[2], paid);
        String key = switch (result) {
            case QUEUED -> "market.admin-credit-queued";
            case CLEARED -> "market.admin-credit-cleared";
            case NOT_FOUND -> "market.admin-credit-not-found";
            case INVALID -> "market.admin-credit-invalid";
            case STORAGE_FAILURE -> "market.admin-credit-storage-error";
        };
        sender.sendMessage(Lang.get(sender, key));
    }

    private static void sendCreditUsage(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "market.admin-credit-usage"));
    }

    private void handleSettlements(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chatalyst.market.reconcile")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            List<MarketManager.SettlementView> rows = manager.pendingSettlements();
            if (rows.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "market.admin-settlement-empty"));
                return;
            }
            sender.sendMessage(Lang.get(sender, "market.admin-settlement-header",
                    "count", rows.size()));
            for (MarketManager.SettlementView row : rows) {
                sender.sendMessage(Lang.get(sender, "market.admin-settlement-entry",
                        "id", row.id(), "kind", row.kind(), "status", row.status(),
                        "payer", row.payer(), "payee", row.payee(), "amount", row.amount()));
            }
            return;
        }
        if (args.length < 3) {
            sendSettlementUsage(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        boolean paid;
        if (List.of("paid", "pagado").contains(action)) paid = true;
        else if (List.of("unpaid", "no-pagado", "nopagado").contains(action)) paid = false;
        else {
            sendSettlementUsage(sender);
            return;
        }
        MarketManager.CreditReconcileResult result = manager.reconcileSettlement(args[2], paid);
        String key = switch (result) {
            case CLEARED, QUEUED -> "market.admin-settlement-done";
            case NOT_FOUND -> "market.admin-settlement-not-found";
            case INVALID -> "market.admin-settlement-invalid";
            case STORAGE_FAILURE -> "market.admin-settlement-storage-error";
        };
        sender.sendMessage(Lang.get(sender, key));
    }

    private static void sendSettlementUsage(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "market.admin-settlement-usage"));
    }

    private Integer parseId(Player p, String[] args, int index) {
        if (args.length > index) {
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException ignore) {
                // cae al mensaje de error
            }
        }
        p.sendMessage(Lang.get(p, "market.invalid-id"));
        return null;
    }

    private Double parseAmount(Player p, String[] args, int index, String usageKey) {
        if (args.length <= index) {
            p.sendMessage(Lang.get(p, usageKey));
            return null;
        }
        try {
            double v = Double.parseDouble(args[index]);
            if (v < 0 || !Double.isFinite(v)) {
                p.sendMessage(Lang.get(p, "market.invalid-price"));
                return null;
            }
            return v;
        } catch (NumberFormatException ex) {
            p.sendMessage(Lang.get(p, "market.invalid-price"));
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Lang.isSpanish(sender)
                    ? List.of("publicar", "vender", "subastar", "comprar", "pujar", "ver", "cancelar",
                            "recoger", "historial", "credits", "liquidaciones")
                    : List.of("publish", "sell", "auction", "buy", "bid", "view", "cancel", "claim", "history",
                            "credits", "settlements");
            return filter(subs, args[0]);
        }
        if (args[0].equalsIgnoreCase("credits") && sender.hasPermission("chatalyst.market.reconcile")) {
            if (args.length == 2) return filter(List.of("list", "retry", "paid"), args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("retry")
                    || args[1].equalsIgnoreCase("paid"))) {
                List<String> ids = manager.pendingCredits().stream()
                        .map(MarketManager.CreditView::id).toList();
                return filter(ids, args[2]);
            }
        }
        if ((args[0].equalsIgnoreCase("settlements")
                || args[0].equalsIgnoreCase("liquidaciones"))
                && sender.hasPermission("chatalyst.market.reconcile")) {
            if (args.length == 2) return filter(List.of("list", "paid", "unpaid"), args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("paid")
                    || args[1].equalsIgnoreCase("unpaid"))) {
                return filter(manager.pendingSettlements().stream()
                        .map(MarketManager.SettlementView::id).toList(), args[2]);
            }
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("buy", "comprar", "bid", "pujar", "puja", "view", "ver",
                    "cancel", "cancelar").contains(sub)) {
                List<String> ids = new ArrayList<>();
                // Lo mismo que enseña la vitrina: sin esto el autocompletado delataba los ids
                // de los anuncios programados que todavia no han salido.
                List<MarketListing> shown = sender instanceof org.bukkit.entity.Player viewer
                        ? manager.visibleFor(viewer.getUniqueId()) : manager.listings();
                for (MarketListing l : shown) {
                    ids.add(String.valueOf(l.id));
                }
                return filter(ids, args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(low)) out.add(o);
        }
        return out;
    }
}
