package dev.chatalyst.report;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * /reports                     List open reports.
 * /reports info &lt;id&gt;           Show one report in detail.
 * /reports claim &lt;id&gt;          Mark a report as being handled.
 * /reports resolve &lt;id&gt; [note] Close a report.
 *
 * The management side of the report system (creating a report is /report). Staff can also
 * handle reports from Discord (see the Discord staff channel).
 */
public final class ReportsCommand implements CommandExecutor, TabCompleter {

    private final ReportActions actions;

    public ReportsCommand(ReportActions actions) {
        this.actions = actions;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        ReportManager reports = actions.reports();

        // Sin argumentos, a un jugador le abrimos la GUI; en consola listamos en texto.
        if (args.length == 0 && sender instanceof org.bukkit.entity.Player p) {
            dev.chatalyst.gui.Menu.open(p, new ReportsMenu(actions));
            return true;
        }

        boolean seePriority = actions.canSeePriority(sender);

        if (args.length == 0) {
            // Los tickets prioritarios solo se listan a quien puede verlos (rango alto).
            List<ReportManager.Report> open = reports.listOpen().stream()
                    .filter(r -> seePriority || !r.priority()).toList();
            if (open.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "reports.none"));
                return true;
            }
            sender.sendMessage(Lang.get(sender, "reports.list-header", "count", open.size()));
            for (ReportManager.Report r : open) {
                sender.sendMessage(MM.full(Lang.raw(sender, "reports.list-entry",
                                "id", r.id(),
                                "priority", r.priority() ? Lang.raw(sender, "reports.priority-marker") : "",
                                "status", statusTag(sender, r.status()),
                                "target", r.target(),
                                "reporter", r.reporter(),
                                "ago", ago(sender, r.time())),
                        Placeholder.unparsed("chatalyst_reason", trim(r.reason(), 40))));
            }
            sender.sendMessage(Lang.get(sender, "reports.list-hint"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "reports.usage"));
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Lang.get(sender, "common.invalid-number"));
            return true;
        }
        String actor = sender.getName();

        // No dejar gestionar un ticket prioritario a quien no puede verlo.
        ReportManager.Report existing = reports.get(id);
        if (existing != null && existing.priority() && !seePriority) {
            sender.sendMessage(Lang.get(sender, "reports.unknown", "id", id));
            return true;
        }

        switch (sub) {
            case "info" -> {
                ReportManager.Report r = reports.get(id);
                if (r == null) {
                    sender.sendMessage(Lang.get(sender, "reports.unknown", "id", id));
                    return true;
                }
                sender.sendMessage(Lang.get(sender, "reports.info-header",
                        "id", r.id(), "status", statusTag(sender, r.status())));
                sender.sendMessage(Lang.get(sender, "reports.info-target",
                        "target", r.target(), "reporter", r.reporter(), "ago", ago(sender, r.time())));
                sender.sendMessage(MM.full(Lang.raw(sender, "reports.info-reason") + " <#FBF2FF><chatalyst_reason>",
                        Placeholder.unparsed("chatalyst_reason", r.reason())));
                if (!r.handler().isEmpty()) {
                    sender.sendMessage(Lang.get(sender, "reports.info-handled",
                            "handler", MM.escape(r.handler()),
                            "note", r.note().isEmpty() ? ""
                                    : " <#7B68A0>· <#C0B3D9>" + MM.escape(r.note())));
                }
            }
            case "claim" -> {
                if (actions.claim(id, actor)) {
                    sender.sendMessage(Lang.get(sender, "reports.claimed", "id", id));
                } else {
                    sender.sendMessage(Lang.get(sender, "reports.cannot", "id", id));
                }
            }
            case "resolve", "pending", "reject" -> {
                String note = args.length > 2
                        ? dev.chatalyst.util.Sanitize.stripInvisible(
                                String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))).trim()
                        : "";
                note = dev.chatalyst.util.Sanitize.limit(note, maxNoteLength());
                boolean ok = switch (sub) {
                    case "resolve" -> actions.resolve(id, actor, note);
                    case "pending" -> actions.pending(id, actor, note);
                    default -> actions.reject(id, actor, note);
                };
                sender.sendMessage(Lang.get(sender, ok ? "reports." + sub : "reports.cannot", "id", id));
            }
            case "reopen" -> sender.sendMessage(Lang.get(sender,
                    actions.reopen(id, actor) ? "reports.reopened" : "reports.cannot", "id", id));
            case "reply" -> {
                if (args.length < 3) {
                    sender.sendMessage(Lang.get(sender, "reports.reply-usage"));
                    return true;
                }
                String text = dev.chatalyst.util.Sanitize.stripInvisible(
                        String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))).trim();
                if (text.isEmpty()) {
                    sender.sendMessage(Lang.get(sender, "reports.reply-usage"));
                    return true;
                }
                text = dev.chatalyst.util.Sanitize.limit(text, maxReplyLength());
                sender.sendMessage(Lang.get(sender,
                        actions.reply(id, actor, true, text) ? "reports.replied" : "reports.unknown", "id", id));
            }
            case "delete" -> {
                if (!actions.canDelete(sender)) {
                    sender.sendMessage(Lang.get(sender, "reports.delete-denied"));
                    return true;
                }
                sender.sendMessage(Lang.get(sender,
                        actions.delete(id, actor) ? "reports.deleted" : "reports.unknown", "id", id));
            }
            case "priority" -> {
                // Solo el rango alto puede mover un ticket a la seccion critica.
                if (!seePriority) {
                    sender.sendMessage(Lang.get(sender, "common.no-permission"));
                    return true;
                }
                if (existing == null) {
                    sender.sendMessage(Lang.get(sender, "reports.unknown", "id", id));
                    return true;
                }
                boolean now = !existing.priority();
                boolean changed = actions.setPriority(id, now, actor);
                sender.sendMessage(Lang.get(sender, changed
                        ? (now ? "reports.priority-on" : "reports.priority-off")
                        : "reports.cannot", "id", id));
            }
            default -> sender.sendMessage(Lang.get(sender, "reports.usage"));
        }
        return true;
    }

    static String statusTag(CommandSender viewer, ReportManager.Status s) {
        return switch (s) {
            case OPEN -> Lang.raw(viewer, "reports.status-open-tag");
            case CLAIMED -> Lang.raw(viewer, "reports.status-claimed-tag");
            case PENDING -> Lang.raw(viewer, "reports.status-pending-tag");
            case RESOLVED -> Lang.raw(viewer, "reports.status-resolved-tag");
            case REJECTED -> Lang.raw(viewer, "reports.status-rejected-tag");
        };
    }

    private static String ago(CommandSender viewer, long time) {
        long mins = Duration.ofMillis(System.currentTimeMillis() - time).toMinutes();
        if (mins < 1) return Lang.raw(viewer, "reports.gui-ago-now");
        if (mins < 60) return Lang.raw(viewer, "reports.gui-ago-min", "n", mins);
        long hours = mins / 60;
        return hours < 24 ? Lang.raw(viewer, "reports.gui-ago-hour", "n", hours)
                : Lang.raw(viewer, "reports.gui-ago-day", "n", hours / 24);
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private int maxReplyLength() {
        int value = actions.plugin().getConfig().getInt("Reports.Max_Reply_Length", 512);
        return Math.max(32, Math.min(TicketConversation.MAX_TEXT_LENGTH, value));
    }

    private int maxNoteLength() {
        int value = actions.plugin().getConfig().getInt("Reports.Max_Note_Length", 512);
        return Math.max(32, Math.min(ReportManager.MAX_NOTE_LENGTH, value));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return java.util.stream.Stream.of("info", "claim", "resolve", "pending", "reject", "reopen", "priority", "delete", "reply")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            boolean seePriority = actions.canSeePriority(sender);
            return actions.reports().listOpen().stream()
                    .filter(r -> seePriority || !r.priority())
                    .map(r -> String.valueOf(r.id())).limit(20).toList();
        }
        return List.of();
    }
}
