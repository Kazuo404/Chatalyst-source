package dev.chatalyst.staff.tools;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.BanRecord;
import dev.chatalyst.staff.HistoryLog;
import dev.chatalyst.staff.PunishmentHistoryMenu;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /history &lt;jugador&gt; [pagina] — el historial de castigos en el chat:
 * cada linea con su numero (#1532), que fue, quien lo puso, hace cuanto y por que, y el ban
 * vigente marcado como [Activo]. Funciona con el jugador conectado o no.
 *
 * <p>{@code /history <jugador> menu} abre el menu de siempre. Permiso
 * {@value #PERMISSION}.
 */
public final class HistoryCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.history";
    static final int PAGE_SIZE = 8;

    private final StaffModerationModule module;

    public HistoryCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!ToolsSupport.available(module, sender)
                || !ToolsSupport.require(sender, PERMISSION)) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-tools.history-usage", "label", label));
            return true;
        }
        OfflinePlayer target = ToolsSupport.resolve(args[0]);
        if (target == null) {
            ToolsSupport.unknown(sender, args[0]);
            return true;
        }
        String name = target.getName() == null ? args[0] : target.getName();
        if (args.length > 1 && isMenuWord(args[1])) {
            if (sender instanceof Player player) {
                Menu.open(player, new PunishmentHistoryMenu(module, target.getUniqueId(), name,
                        null));
            } else {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
            }
            return true;
        }
        int requested = HistoryQueries.pageArg(args.length > 1 ? args[1] : null);
        String commandBase = "/" + label + " " + name;
        ToolsSupport.async(module, () -> {
            List<HistoryLog.Entry> entries =
                    HistoryQueries.newestFirst(module.history().list(target.getUniqueId()));
            BanRecord activeBan = module.bans().get(target.getUniqueId());
            ToolsSupport.reply(module, sender, viewer ->
                    show(viewer, name, entries, activeBan, requested, commandBase));
        });
        return true;
    }

    private void show(CommandSender viewer, String name, List<HistoryLog.Entry> entries,
                      BanRecord activeBan, int requested, String commandBase) {
        if (entries.isEmpty()) {
            viewer.sendMessage(Lang.get(viewer, "staff-tools.history-empty",
                    "player", MM.escape(name)));
            return;
        }
        HistoryQueries.Page<HistoryLog.Entry> page =
                HistoryQueries.page(entries, requested, PAGE_SIZE);
        int active = activeBan == null ? -1 : HistoryQueries.latestBan(entries);
        Component header = Lang.get(viewer, "staff-tools.history-header",
                "player", MM.escape(name), "count", page.total());
        if (viewer instanceof Player) {
            header = header.append(Component.space()).append(menuButton(viewer, commandBase));
        }
        viewer.sendMessage(header);
        long now = System.currentTimeMillis();
        int offset = (page.page() - 1) * PAGE_SIZE;
        for (int i = 0; i < page.items().size(); i++) {
            HistoryLog.Entry entry = page.items().get(i);
            boolean isActive = offset + i == active;
            viewer.sendMessage(line(viewer, entry, now, isActive ? activeBan : null));
        }
        if (page.pages() > 1) {
            viewer.sendMessage(ToolsSupport.pager(viewer, commandBase, page.page(),
                    page.pages()));
        }
    }

    /** Una fila, con la fecha exacta al pasar el raton. */
    Component line(CommandSender viewer, HistoryLog.Entry entry, long now, BanRecord active) {
        String state = "";
        if (active != null) {
            state = Lang.raw(viewer, active.permanent()
                            ? "staff-tools.history-active" : "staff-tools.history-active-temp",
                    "remaining", module.formatter().remainingText(viewer, active.until()));
        }
        Component row = module.formatter().feedback(viewer, "staff-tools.history-entry",
                entry.detail(),
                "ago", TimeUtil.formatElapsed(now - entry.time()),
                "id", PunishmentIds.label(entry.id()),
                "type", MM.escape(ToolsSupport.typeLabel(viewer, entry.type())),
                "staff", MM.escape(entry.staff()),
                "silent", entry.silent() ? Lang.raw(viewer, "staff-tools.silent-mark") : "",
                "state", state);
        return row.hoverEvent(HoverEvent.showText(Lang.get(viewer, "staff-tools.history-hover",
                "date", module.formatter().expiresText(viewer, entry.time()),
                "id", PunishmentIds.label(entry.id()))));
    }

    private static boolean isMenuWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "menu", "gui", "menú" -> true;
            default -> false;
        };
    }

    /** Boton "[Menu]" para quien prefiera el historial en un inventario. */
    static Component menuButton(CommandSender viewer, String commandBase) {
        return Lang.get(viewer, "staff-tools.history-menu-button")
                .clickEvent(ClickEvent.runCommand(commandBase + " menu"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, PERMISSION)) return List.of();
        if (args.length == 1) return dev.chatalyst.staff.lookup.LookupSupport.knownNames(sender, args[0]);
        if (args.length == 2) return ToolsSupport.prefix(args[1], List.of("1", "2", "menu"));
        return List.of();
    }
}
