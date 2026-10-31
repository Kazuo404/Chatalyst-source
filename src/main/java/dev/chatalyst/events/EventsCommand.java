package dev.chatalyst.events;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** /events: los jugadores ven los proximos; el staff abre el panel de administracion. */
public final class EventsCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String ADMIN = "chatalyst.command.events.admin";

    private final ChatalystPlugin plugin;
    private final EventsManager events;
    private final EventPrompt prompt;

    public EventsCommand(ChatalystPlugin plugin, EventsManager events, EventPrompt prompt) {
        this.plugin = plugin;
        this.events = events;
        this.prompt = prompt;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && (args[0].equalsIgnoreCase("admin")
                || args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("manage"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
                return true;
            }
            if (!player.hasPermission(ADMIN)) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
                return true;
            }
            Menu.open(player, new EventsMenu(plugin, events, prompt));
            return true;
        }
        List<ScheduledEvent> upcoming = events.upcoming();
        if (upcoming.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "events.none"));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "events.header"));
        boolean spanish = Lang.isSpanish(sender);
        for (ScheduledEvent e : upcoming) {
            String when = STAMP.format(Instant.ofEpochMilli(e.whenMillis()).atZone(ZoneId.systemDefault()));
            sender.sendMessage(Lang.get(sender, "events.entry", "event", e.displayName(spanish),
                    "when", when, "in", TimeUtil.formatRemaining(e.whenMillis())));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission(ADMIN) && "admin".startsWith(args[0].toLowerCase())) {
            return List.of("admin");
        }
        return List.of();
    }
}
