package dev.chatalyst.staff.tools;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.HistoryLog;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * /prunehistory &lt;jugador&gt; [tiempo] [confirmar] — borra el historial de castigos de un
 * jugador: entero, o solo lo anterior a {@code tiempo} ({@code /prunehistory Pepe 90d} deja
 * los ultimos 90 dias). Para quien ya cumplio y merece empezar limpio, o para quitar las filas
 * de un error.
 *
 * <p>Borrar no se deshace, asi que sin {@code confirmar} solo dice cuantas filas se irian y
 * ofrece el boton; ver {@link ConfirmationGate}. Los castigos VIGENTES no se levantan: esto
 * borra el registro, no el ban. Queda en la auditoria. Permiso {@value #PERMISSION}.
 */
public final class PruneHistoryCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.prunehistory";

    private final StaffModerationModule module;
    private final ConfirmationGate gate = new ConfirmationGate();

    public PruneHistoryCommand(StaffModerationModule module) {
        this.module = module;
    }

    /** Que pidio el staff: a quien, cuanto historial respetar y si ya confirma. */
    record Request(String player, long olderThan, String timeText, boolean confirm,
                   boolean valid) {
    }

    /** "/prunehistory Pepe", "Pepe 90d", "Pepe confirmar", "Pepe 90d confirmar". */
    static Request parse(String[] args) {
        if (args.length < 1) return new Request("", 0L, "", false, false);
        boolean confirm = args.length > 1 && ToolsSupport.isConfirm(args[args.length - 1]);
        int last = confirm ? args.length - 1 : args.length;
        if (last <= 1) return new Request(args[0], 0L, "", confirm, true);
        if (last > 2) return new Request(args[0], 0L, "", confirm, false);
        long duration = TimeUtil.parseDuration(args[1]);
        if (duration == TimeUtil.PERMANENT) duration = 0L; // "perm" = todo
        boolean ok = duration > 0L || args[1].equalsIgnoreCase("perm")
                || args[1].equalsIgnoreCase("todo") || args[1].equalsIgnoreCase("all");
        return new Request(args[0], Math.max(0L, duration), args[1], confirm, ok);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!ToolsSupport.available(module, sender)
                || !ToolsSupport.require(sender, PERMISSION)) return true;
        Request request = parse(args);
        if (!request.valid()) {
            sender.sendMessage(Lang.get(sender, args.length < 1
                    ? "staff-tools.prune-usage" : "staff-moderation.invalid-duration",
                    "label", label));
            return true;
        }
        OfflinePlayer target = ToolsSupport.resolve(request.player());
        if (target == null) {
            ToolsSupport.unknown(sender, request.player());
            return true;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? request.player() : target.getName();
        String action = "prune:" + id + ":" + request.olderThan();
        String confirmCommand = "/" + label + " " + name
                + (request.timeText().isEmpty() ? "" : " " + request.timeText()) + " confirmar";
        ToolsSupport.async(module, () -> {
            long now = System.currentTimeMillis();
            var filter = HistoryQueries.pruneFilter(now, request.olderThan());
            List<HistoryLog.Entry> entries = module.history().list(id);
            long matching = entries.stream().filter(filter).count();
            if (!request.confirm()) {
                if (matching > 0) gate.request(sender.getName(), action, now);
                ToolsSupport.reply(module, sender, viewer -> {
                    if (matching == 0) {
                        viewer.sendMessage(Lang.get(viewer, "staff-tools.prune-nothing",
                                "player", MM.escape(name)));
                        return;
                    }
                    viewer.sendMessage(Lang.get(viewer, request.olderThan() > 0
                                    ? "staff-tools.prune-preview-older"
                                    : "staff-tools.prune-preview-all",
                            "player", MM.escape(name), "count", matching,
                            "total", entries.size(), "time", MM.escape(request.timeText())));
                    viewer.sendMessage(Lang.get(viewer, "staff-tools.prune-confirm-button")
                            .clickEvent(ClickEvent.runCommand(confirmCommand))
                            .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                                    "staff-tools.confirm-hover"))));
                });
                return;
            }
            if (!gate.confirm(sender.getName(), action, now)) {
                ToolsSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-tools.confirm-expired", "command",
                        MM.escape(confirmCommand.replace(" confirmar", "")))));
                return;
            }
            int removed = module.history().removeIf(id, filter);
            if (removed >= 0) {
                module.audit(sender, "prunehistory", name + " "
                        + (request.timeText().isEmpty() ? "all" : ">" + request.timeText())
                        + ": " + removed + " entries");
            }
            ToolsSupport.reply(module, sender, viewer -> viewer.sendMessage(removed < 0
                    ? Lang.get(viewer, "plugin.storage-unavailable")
                    : Lang.get(viewer, "staff-tools.prune-done", "player", MM.escape(name),
                            "count", removed)));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, PERMISSION)) return List.of();
        if (args.length == 1) return dev.chatalyst.staff.lookup.LookupSupport.knownNames(sender, args[0]);
        if (args.length == 2) {
            return ToolsSupport.prefix(args[1], List.of("30d", "90d", "180d", "confirmar"));
        }
        if (args.length == 3) return ToolsSupport.prefix(args[2], List.of("confirmar"));
        return List.of();
    }
}
