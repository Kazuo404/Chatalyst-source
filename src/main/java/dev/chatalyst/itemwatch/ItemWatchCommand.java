package dev.chatalyst.itemwatch;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /itemwatch scan &lt;jugador&gt; — recuento de cada regla de un jugador ahora mismo.
 * /itemwatch alerts — silencia/reactiva los avisos para quien lo escribe.
 */
final class ItemWatchCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.itemwatch";

    private final ChatalystPlugin plugin;
    private final ItemWatchManager manager;

    ItemWatchCommand(ChatalystPlugin plugin, ItemWatchManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!manager.enabled()) {
            sender.sendMessage(Lang.get(sender, "itemwatch.disabled"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alerts", "avisos" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return true;
                }
                boolean nowMuted = manager.toggleMuted(player.getUniqueId());
                sender.sendMessage(Lang.get(sender,
                        nowMuted ? "itemwatch.alerts-off" : "itemwatch.alerts-on"));
            }
            case "scan", "revisar" -> {
                if (args.length < 2) {
                    sender.sendMessage(Lang.get(sender, "itemwatch.usage", "label", label));
                    return true;
                }
                Player target = NickManager.online(args[1]);
                if (target == null) {
                    sender.sendMessage(Lang.get(sender, "common.player-offline"));
                    return true;
                }
                scan(sender, target);
            }
            default -> sender.sendMessage(Lang.get(sender, "itemwatch.usage", "label", label));
        }
        return true;
    }

    private void scan(CommandSender sender, Player target) {
        String name = target.getName();
        FoliaTasks.entity(plugin, target, current -> manager.scanNow(current)
                .thenAccept(report -> reply(sender, current.getName(), report)),
                () -> reply(sender, name, null));
    }

    private void reply(CommandSender sender, String name, ItemWatchManager.Report report) {
        Runnable send = () -> {
            if (report == null) {
                sender.sendMessage(Lang.get(sender, "common.player-offline"));
                return;
            }
            sender.sendMessage(Lang.get(sender, "itemwatch.scan-header",
                    "player", MM.escape(name),
                    "sources", Lang.raw(sender, report.vaultsIncluded()
                            ? "itemwatch.scan-sources-vaults" : "itemwatch.scan-sources")));
            boolean flagged = false;
            boolean anything = false;
            for (WatchRule rule : manager.settings().rules()) {
                long amount = report.totals().getOrDefault(rule.id(), 0L);
                // Solo las reglas con algo: nueve lineas a cero tapaban lo que importa.
                if (amount <= 0) continue;
                anything = true;
                boolean over = rule.max() > 0 && amount >= rule.max();
                flagged |= over;
                sender.sendMessage(Lang.get(sender, over
                                ? "itemwatch.scan-line-over" : "itemwatch.scan-line",
                        "rule", MM.escape(rule.displayName()), "amount", amount,
                        "max", rule.max() > 0 ? String.valueOf(rule.max()) : "-"));
                // Donde esta: cada sitio con su cantidad, lo que va en shulkers, y un clic
                // que lo abre (esa mochila, el ender chest o el inventario).
                List<WatchSource> ranked = new java.util.ArrayList<>(report.sources());
                ranked.removeIf(source -> source.total(rule.id()) <= 0);
                ranked.sort((a, b) -> Long.compare(b.total(rule.id()), a.total(rule.id())));
                for (WatchSource source : ranked) {
                    sender.sendMessage(sourceLine(sender, name, source,
                            source.total(rule.id()), source.nested(rule.id())));
                }
            }
            for (WatchSource source : report.sources()) {
                for (WatchedStack stack : source.overstacked()) {
                    flagged = true;
                    anything = true;
                    sender.sendMessage(Lang.get(sender, "itemwatch.scan-illegal-stack",
                                    "amount", stack.amount(),
                                    "item", MM.escape(stack.name().isEmpty()
                                            ? stack.type().name().toLowerCase(Locale.ROOT)
                                                    .replace('_', ' ')
                                            : stack.name()),
                                    "max", stack.maxStack(),
                                    "source", ItemWatchManager.sourceName(sender, source))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                    ItemWatchManager.openCommand(name, source))));
                }
            }
            if (!anything) {
                sender.sendMessage(Lang.get(sender, "itemwatch.scan-nothing"));
            } else if (!flagged) {
                sender.sendMessage(Lang.get(sender, "itemwatch.scan-clean"));
            }
        };
        if (sender instanceof Player player) {
            FoliaTasks.entity(plugin, player, ignored -> send.run());
        } else {
            FoliaTasks.global(plugin, send);
        }
    }

    private static net.kyori.adventure.text.Component sourceLine(CommandSender viewer,
                                                                 String name, WatchSource source,
                                                                 long amount, long nested) {
        String where = ItemWatchManager.sourceName(viewer, source);
        var line = Lang.get(viewer, nested > 0
                        ? "itemwatch.scan-source-nested" : "itemwatch.scan-source",
                "source", where, "amount", amount, "nested", nested);
        if (source.live()) {
            line = line.append(Lang.get(viewer, "itemwatch.scan-source-live"));
        }
        return line
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Lang.get(viewer, "itemwatch.alert-hover-open", "source", where)))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                        ItemWatchManager.openCommand(name, source)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("scan", "alerts")
                    .filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("scan")
                || args[0].equalsIgnoreCase("revisar"))) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .filter(pl -> !dev.chatalyst.util.Vanish.isVanished(pl)
                            || dev.chatalyst.staff.StaffAccess.canSeeVanished(sender))
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p)).toList();
        }
        return List.of();
    }
}
