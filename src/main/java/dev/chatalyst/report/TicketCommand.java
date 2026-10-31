package dev.chatalyst.report;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * /ticket             Lists the player's own open reports.
 * /ticket &lt;id&gt;        Shows that report's conversation (must be the reporter).
 * /ticket &lt;id&gt; &lt;msg&gt;  Adds a reply to the conversation.
 *
 * Lets the reporter follow up on their own report without needing Discord — the report id
 * doubles as the ticket reference. Ownership is checked by name, so nobody reads or writes
 * on someone else's ticket.
 */
public final class TicketCommand implements CommandExecutor {

    private final ReportActions actions;

    public TicketCommand(ReportActions actions) {
        this.actions = actions;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }

        if (args.length == 0) {
            // Lista los reportes abiertos del propio jugador.
            var mine = actions.reports().all().stream()
                    .filter(r -> !r.status().isClosed()
                            && ReportManager.ownedBy(r, p.getUniqueId(), p.getName()))
                    .sorted((a, b) -> Integer.compare(b.id(), a.id()))
                    .toList();
            if (mine.isEmpty()) {
                p.sendMessage(Lang.get(p, "ticket.none"));
                return true;
            }
            p.sendMessage(Lang.get(p, "ticket.header"));
            for (ReportManager.Report r : mine) {
                p.sendMessage(Lang.get(p, "ticket.entry",
                        "id", r.id(),
                        "status", ReportsMenu.statusWord(p, r.status()),
                        "target", MM.escape(r.targetDisplay()),
                        "count", actions.conversation().count(r.id())));
            }
            p.sendMessage(Lang.get(p, "ticket.list-hint"));
            return true;
        }

        int id;
        try {
            id = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            p.sendMessage(Lang.get(p, "ticket.usage"));
            return true;
        }
        ReportManager.Report r = actions.reports().get(id);
        // Solo el reportante puede ver/responder su propio ticket.
        if (!ReportManager.ownedBy(r, p.getUniqueId(), p.getName())) {
            p.sendMessage(Lang.get(p, "ticket.not-yours", "id", id));
            return true;
        }

        if (args.length == 1) {
            // Mostrar la conversacion.
            p.sendMessage(Lang.get(p, "ticket.detail-header", "id", id,
                    "status", ReportsMenu.statusWord(p, r.status())));
            p.sendMessage(MM.full(Lang.raw(p, "ticket.reason") + " <#FBF2FF><chatalyst_reason>",
                    Placeholder.unparsed("chatalyst_reason", r.reason())));
            var msgs = actions.conversation().get(id);
            if (msgs.isEmpty()) {
                p.sendMessage(Lang.get(p, "ticket.no-replies-action", "id", id));
            } else {
                for (var m : msgs) {
                    p.sendMessage(MM.full("<#7B68A0>" + Lang.raw(p, m.staff() ? "ticket.staff" : "ticket.you")
                            + " <#7B68A0>» <#FBF2FF><chatalyst_text>",
                            Placeholder.unparsed("chatalyst_text", m.text())));
                }
            }
            return true;
        }

        if (r.status().isClosed()) {
            p.sendMessage(Lang.get(p, "ticket.closed", "id", id));
            return true;
        }
        String text = Sanitize.stripInvisible(
                String.join(" ", Arrays.copyOfRange(args, 1, args.length))).trim();
        if (text.isEmpty()) return true;
        text = Sanitize.limit(text, maxReplyLength(actions));
        p.sendMessage(Lang.get(p,
                actions.reply(id, p.getName(), false, text) ? "ticket.sent" : "ticket.cannot",
                "id", id));
        return true;
    }

    private static int maxReplyLength(ReportActions actions) {
        int configured = actions.plugin().getConfig().getInt("Reports.Max_Reply_Length", 512);
        return Math.max(32, Math.min(TicketConversation.MAX_TEXT_LENGTH, configured));
    }
}
