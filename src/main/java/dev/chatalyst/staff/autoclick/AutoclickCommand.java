package dev.chatalyst.staff.autoclick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /autoclick — sospechosos recientes.
 * /autoclick &lt;jugador&gt; — sus CPS y su regularidad en vivo (action bar) durante unos
 * segundos, mas su ultimo aviso.
 * /autoclick alerts — silencia/reactiva los avisos para quien lo escribe.
 */
final class AutoclickCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.autoclick";

    private final ChatalystPlugin plugin;
    private final AutoclickManager manager;

    AutoclickCommand(ChatalystPlugin plugin, AutoclickManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffAccess.has(sender, PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!manager.enabled()) {
            sender.sendMessage(Lang.get(sender, "autoclick.disabled"));
            return true;
        }
        if (args.length == 0) {
            list(sender);
            return true;
        }
        String arg = args[0];
        switch (arg.toLowerCase(Locale.ROOT)) {
            case "alerts", "avisos" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return true;
                }
                boolean nowMuted = manager.toggleMuted(player.getUniqueId());
                sender.sendMessage(Lang.get(sender,
                        nowMuted ? "autoclick.alerts-off" : "autoclick.alerts-on"));
            }
            case "help", "ayuda" -> sender.sendMessage(Lang.get(sender, "autoclick.usage",
                    "label", label));
            default -> {
                Player target = NickManager.online(arg);
                if (target == null || (dev.chatalyst.util.Vanish.isVanished(target)
                        && !StaffAccess.canSeeVanished(sender))) {
                    sender.sendMessage(Lang.get(sender, "common.player-offline"));
                    return true;
                }
                inspect(sender, target);
            }
        }
        return true;
    }

    private void list(CommandSender sender) {
        List<AutoclickManager.Suspect> suspects = manager.suspects();
        long minutes = manager.settings().recentMillis() / 60_000L;
        if (suspects.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "autoclick.list-empty", "minutes", minutes));
            return;
        }
        sender.sendMessage(Lang.get(sender, "autoclick.list-header", "minutes", minutes,
                "count", suspects.size()));
        long now = System.currentTimeMillis();
        for (AutoclickManager.Suspect suspect : suspects) {
            AutoclickManager.Alert last = suspect.last();
            sender.sendMessage(Lang.get(sender, "autoclick.list-entry",
                            AutoclickManager.vars(sender, suspect.name(), last.verdict(),
                                    "alerts", suspect.alerts(),
                                    "ago", TimeUtil.formatElapsed(now - last.time())))
                    .clickEvent(ClickEvent.runCommand("/autoclick " + suspect.name()))
                    .hoverEvent(HoverEvent.showText(Lang.get(sender,
                            "autoclick.alert-watch-hover", "player", MM.escape(suspect.name())))));
        }
    }

    private void inspect(CommandSender sender, Player target) {
        String name = target.getName();
        String source = Lang.raw(sender, manager.packetSource()
                ? "autoclick.source-packets" : "autoclick.source-events");
        sender.sendMessage(Lang.get(sender, "autoclick.watch-start", "player", MM.escape(name),
                "seconds", manager.settings().liveSeconds(), "source", source));
        AutoclickManager.Suspect suspect = manager.suspect(target.getUniqueId());
        if (suspect == null) {
            sender.sendMessage(Lang.get(sender, "autoclick.watch-no-alert"));
        } else {
            AutoclickManager.Alert last = suspect.last();
            sender.sendMessage(Lang.get(sender, "autoclick.watch-last-alert",
                    AutoclickManager.vars(sender, name, last.verdict(),
                            "alerts", suspect.alerts(),
                            "ago", TimeUtil.formatElapsed(System.currentTimeMillis() - last.time()))));
        }
        // Avisos de por que este jugador nunca saltaria, para que nadie confunda silencio con
        // inocencia.
        if (!target.isOp() && target.hasPermission(AutoclickManager.BYPASS_PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "autoclick.watch-exempt",
                    "reason", Lang.raw(sender, "autoclick.reason-bypass")));
        } else if (ClickTracker.isBedrock(target.getUniqueId())
                && !manager.settings().analyzeBedrock()) {
            sender.sendMessage(Lang.get(sender, "autoclick.watch-exempt",
                    "reason", Lang.raw(sender, "autoclick.reason-bedrock")));
        }
        if (sender instanceof Player staff) {
            manager.watch(staff, target);
            return;
        }
        // La consola no tiene action bar: una foto de este instante.
        ClickAnalysis.Live live = manager.live(target.getUniqueId());
        if (live != null) sender.sendMessage(AutoclickManager.liveLine(sender, name, live));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, PERMISSION) || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        Stream<String> players = plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> !dev.chatalyst.util.Vanish.isVanished(p)
                        || StaffAccess.canSeeVanished(sender))
                .map(Player::getName);
        return Stream.concat(Stream.of("alerts"), players)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
