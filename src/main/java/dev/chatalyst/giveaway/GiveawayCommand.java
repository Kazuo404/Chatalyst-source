package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /sorteo} — the one entry point, for players and staff alike.
 *
 * <p>Bare, it shows a player what is going on (or puts the wheel back in front of them if they
 * closed it), and shows staff the setup menu. Everything that changes something checks {@link
 * #ADMIN_PERMISSION} in code rather than on the command, so the command itself can stay open to
 * everybody: a draw is a public event, and a player should not need a permission to look at it.
 */
public final class GiveawayCommand implements RuntimeCommandRegistrar.Handler {

    public static final String ADMIN_PERMISSION = "chatalyst.command.giveaway.admin";

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayCustomCommands custom;

    GiveawayCommand(ChatalystPlugin plugin, GiveawayManager giveaways,
                    GiveawayCustomPrizes customPrizes) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.custom = new GiveawayCustomCommands(plugin, giveaways, customPrizes);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!giveaways.enabled()) {
            sender.sendMessage(Lang.get(sender, "giveaway.disabled"));
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);

        if (action.isEmpty()) {
            if (sender instanceof Player player) {
                open(player, admin);
            } else {
                status(sender);
            }
            return true;
        }
        if (!admin) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        switch (action) {
            case "start", "iniciar" -> start(sender, label, args);
            case "cancel", "cancelar", "stop" -> cancel(sender);
            case "list", "lista", "premios" -> list(sender);
            case "give", "dar" -> give(sender, label, args);
            case "status", "estado" -> status(sender);
            case "rangos", "ranks" -> ranks(sender, label);
            case "rango", "rank" -> rank(sender, label, args);
            default -> {
                if (!custom.handle(sender, label, action, args)) usage(sender, label);
            }
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return List.of();
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("start", "cancel", "list", "give",
                    "status", "rangos", "rango"));
            options.addAll(GiveawayCustomCommands.SUBCOMMANDS);
            return options.stream().filter(option -> option.startsWith(partial)).toList();
        }
        if (args[0].equalsIgnoreCase("rango") || args[0].equalsIgnoreCase("rank")) {
            String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = args.length == 2 ? giveaways.ranks().offeredGroups(true)
                    : args.length == 3 ? List.of("7d", "15d", "30d", "60d", "90d", "permanente", "on", "off", "config")
                    : List.of();
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
        }
        if (args.length == 2 && isPrizeArgument(args[0])) return prizeIds(args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return prizeIds(args[2]);
        return custom.complete(args);
    }

    // ---- subcommands -----------------------------------------------------------------

    private void open(Player player, boolean admin) {
        if (giveaways.phase() == GiveawayManager.Phase.RUNNING) {
            // Someone who pressed escape mid-draw asking to come back: that is the whole
            // purpose of the bare command for a player, so it reopens the wheel rather
            // than describing it.
            if (!giveaways.showTo(player)) status(player);
            return;
        }
        if (admin) {
            Menu.open(player, new GiveawayMenu(plugin, giveaways));
            return;
        }
        status(player);
    }

    private void start(CommandSender sender, String label, String[] rawArgs) {
        // "ganadores:3" (o winners:3) en cualquier posicion: se saca antes de leer el resto.
        int winners = -1;
        List<String> kept = new ArrayList<>();
        for (String arg : rawArgs) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ganadores:") || lower.startsWith("winners:")) {
                try {
                    winners = Integer.parseInt(lower.substring(lower.indexOf(':') + 1));
                } catch (NumberFormatException notANumber) {
                    sender.sendMessage(Lang.get(sender, "giveaway-custom.winners-invalid",
                            "max", GiveawayCustomPrizes.MAX_WINNERS));
                    return;
                }
                continue;
            }
            kept.add(arg);
        }
        String[] args = kept.toArray(String[]::new);
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "giveaway.usage-start", "command", label));
            return;
        }
        GiveawayPrize prize = giveaways.prize(args[1]).orElse(null);
        if (prize == null) {
            sender.sendMessage(Lang.get(sender, "giveaway.unknown-prize", "command", label));
            return;
        }
        int seconds = -1;
        if (args.length >= 3) {
            try {
                seconds = Integer.parseInt(args[2]);
            } catch (NumberFormatException notANumber) {
                sender.sendMessage(Lang.get(sender, "giveaway.usage-start", "command", label));
                return;
            }
        }
        // Cuarto argumento: la cantidad, para el mismo premio de dinero que en el menu se
        // escribe por chat. Aqui hace falta igual — un sorteo lanzado desde consola o desde un
        // temporizador no tiene menu donde escribirla.
        if (args.length >= 4) {
            if (prize.kind() != GiveawayPrize.Kind.MONEY) {
                sender.sendMessage(Lang.get(sender, "giveaway.amount-not-money"));
                return;
            }
            GiveawayPrize.MoneyBand band = GiveawayAmountPrompt.parse(args[3]);
            if (band == null) {
                sender.sendMessage(Lang.get(sender, "giveaway-gui.amount-invalid"));
                return;
            }
            prize = prize.withMoney(band);
        }
        String failure = giveaways.start(sender, prize, seconds, winners);
        // The failure is a lang key, and some of those name the command in their text;
        // handing the label over always beats letting one of them print "{command}".
        sender.sendMessage(failure == null
                ? Lang.get(sender, "giveaway.started", "prize", prize.id())
                : Lang.get(sender, failure, "command", label));
    }

    private void cancel(CommandSender sender) {
        if (giveaways.phase() == GiveawayManager.Phase.IDLE) {
            sender.sendMessage(Lang.get(sender, "giveaway.not-running"));
            return;
        }
        giveaways.cancel();
        sender.sendMessage(Lang.get(sender, "giveaway.cancelled-by-you"));
    }

    private void list(CommandSender sender) {
        List<GiveawayPrize> prizes = giveaways.catalogue();
        if (prizes.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "giveaway.no-prizes"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "giveaway.list-header"));
        for (GiveawayPrize prize : prizes) {
            String problem = giveaways.delivery().problem(prize);
            sender.sendMessage(Lang.get(sender, "giveaway.list-entry",
                    "id", prize.id(),
                    "kind", Lang.raw(sender, "giveaway.kind." + prize.kind().key()),
                    "prize", prize.displayName(plugin.getConfig(), sender)));
            if (problem != null) {
                sender.sendMessage(Lang.get(sender, "giveaway.list-problem",
                        "problem", problem));
            }
        }
    }

    /**
     * Hands a prize over by hand. The safety net behind {@link GiveawayDelivery}: a winner who
     * disconnected in the final second is paid when they return, but if the server restarts
     * before that, this is how staff finish the job the console log asked them to.
     */
    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Lang.get(sender, "giveaway.usage-give", "command", label));
            return;
        }
        GiveawayPrize prize = giveaways.prize(args[2]).orElse(null);
        if (prize == null) {
            sender.sendMessage(Lang.get(sender, "giveaway.unknown-prize", "command", label));
            return;
        }
        // Un premio de dinero no trae cifra: se escribe al lanzarlo, y aqui igual. Sin esto
        // "give" de un premio de dinero no pagaba nada y aun asi respondia "entregado".
        if (args.length >= 4) {
            if (prize.kind() != GiveawayPrize.Kind.MONEY) {
                sender.sendMessage(Lang.get(sender, "giveaway.amount-not-money"));
                return;
            }
            GiveawayPrize.MoneyBand band = GiveawayAmountPrompt.parse(args[3]);
            if (band == null) {
                sender.sendMessage(Lang.get(sender, "giveaway-gui.amount-invalid"));
                return;
            }
            prize = prize.withMoney(band);
        }
        if (giveaways.delivery().deliversNothing(prize)) {
            sender.sendMessage(Lang.get(sender, "giveaway.prize-empty", "command", label));
            return;
        }
        UUID target = dev.chatalyst.util.BuyerId.resolve(plugin.getServer(), args[1]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "giveaway.unknown-player",
                    "player", dev.chatalyst.util.MM.escape(args[1])));
            return;
        }
        giveaways.delivery().deliver(target, prize);
        sender.sendMessage(Lang.get(sender, "giveaway.given", "player",
                GiveawayNames.shown(plugin.getServer(), target), "prize", prize.id()));
    }

    private void status(CommandSender sender) {
        GiveawayPrize running = giveaways.runningPrize();
        if (running != null) {
            sender.sendMessage(Lang.get(sender, "giveaway.running", "prize",
                    running.displayName(plugin.getConfig(), sender)));
            return;
        }
        UUID winner = giveaways.lastWinner();
        if (winner == null) {
            sender.sendMessage(Lang.get(sender, "giveaway.not-running"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "giveaway.last-winner",
                "player", GiveawayNames.shown(plugin.getServer(), winner),
                "prize", String.valueOf(giveaways.lastWinnerPrize())));
    }

    /** /sorteo rangos: cada rango sorteable con su estado y duracion (todos, tambien los apagados). */
    private void ranks(CommandSender sender, String label) {
        GiveawayRanks ranks = giveaways.ranks();
        GiveawayRankSettings settings = GiveawayRankSettings.get().orElse(null);
        List<String> groups = ranks.offeredGroups(true);
        if (groups.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "giveaway.ranks-none"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "giveaway.ranks-header", "count", groups.size(), "command", label));
        for (String group : groups) {
            boolean on = settings == null || settings.enabled(group);
            String own = settings == null ? null : settings.duration(group);
            sender.sendMessage(Lang.get(sender, on ? "giveaway.ranks-entry-on" : "giveaway.ranks-entry-off",
                    "rank", dev.chatalyst.util.MM.escape(group),
                    "duration", dev.chatalyst.util.MM.escape(ranks.durationLabel(group)),
                    "source", Lang.raw(sender, own != null ? "giveaway.ranks-source-own" : "giveaway.ranks-source-config"))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(
                            "/" + label + " rango " + group + " ")));
        }
        sender.sendMessage(Lang.get(sender, "giveaway.ranks-footer", "command", label));
    }

    /** /sorteo rango <grupo> <duracion|permanente|on|off|config>: se guarda al momento, para toda la red. */
    private void rank(CommandSender sender, String label, String[] args) {
        GiveawayRankSettings settings = GiveawayRankSettings.get().orElse(null);
        if (settings == null || args.length < 3) {
            sender.sendMessage(Lang.get(sender, "giveaway.rank-usage", "command", label));
            return;
        }
        String group = args[1];
        boolean known = giveaways.ranks().offeredGroups(true).stream().anyMatch(g -> g.equalsIgnoreCase(group));
        if (!known) {
            sender.sendMessage(Lang.get(sender, "giveaway.rank-unknown", "rank", dev.chatalyst.util.MM.escape(group),
                    "command", label));
            return;
        }
        String value = args[2].toLowerCase(Locale.ROOT);
        switch (value) {
            case "on", "si", "sí" -> {
                settings.setEnabled(group, true);
                sender.sendMessage(Lang.get(sender, "giveaway.rank-on", "rank", dev.chatalyst.util.MM.escape(group)));
            }
            case "off", "no" -> {
                settings.setEnabled(group, false);
                sender.sendMessage(Lang.get(sender, "giveaway.rank-off", "rank", dev.chatalyst.util.MM.escape(group)));
            }
            case "config", "reset", "global" -> {
                settings.setDuration(group, null);
                sender.sendMessage(Lang.get(sender, "giveaway.rank-duration", "rank", dev.chatalyst.util.MM.escape(group),
                        "duration", dev.chatalyst.util.MM.escape(giveaways.ranks().durationLabel(group))));
            }
            default -> {
                if (!settings.setDuration(group, value)) {
                    sender.sendMessage(Lang.get(sender, "giveaway.rank-bad-duration", "value",
                            dev.chatalyst.util.MM.escape(args[2])));
                    return;
                }
                sender.sendMessage(Lang.get(sender, "giveaway.rank-duration", "rank", dev.chatalyst.util.MM.escape(group),
                        "duration", dev.chatalyst.util.MM.escape(value)));
            }
        }
        plugin.getLogger().info(sender.getName() + " set giveaway rank " + group + " -> " + value);
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Lang.get(sender, "giveaway.usage", "command", label));
    }

    private static boolean isPrizeArgument(String action) {
        return action.equalsIgnoreCase("start") || action.equalsIgnoreCase("iniciar");
    }

    private List<String> prizeIds(String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (GiveawayPrize prize : giveaways.catalogue()) {
            if (prize.id().startsWith(prefix)) ids.add(prize.id());
        }
        return ids;
    }
}
