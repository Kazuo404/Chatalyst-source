package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.staff.BanRecord;
import dev.chatalyst.staff.HistoryLog;
import dev.chatalyst.staff.IpBanManager;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /checkban, /checkmute y /checkwarn: el estado ACTUAL de un castigo
 * quien lo puso, por que, desde cuando y hasta cuando. Conectado o no, y de toda la red.
 */
public final class CheckStatusCommand implements CommandExecutor, TabCompleter {

    private static final Set<String> BAN_TYPES =
            Set.of("ban", "tempban", "banip", "escalation-ban", "freeze-ban", "ipban-alt");
    private static final Set<String> MUTE_TYPES = Set.of("mute", "softmute");

    private final StaffModerationModule module;

    public CheckStatusCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!LookupSupport.allowed(module, sender, name)) return true;
        if (args.length < 1) {
            LookupSupport.usage(sender, label, "<jugador>");
            return true;
        }
        String input = args[0];
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
            if (target == null) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "common.unknown-player", "player", MM.escape(input))));
                return;
            }
            switch (name) {
                case "checkban" -> checkBan(sender, target);
                case "checkmute" -> checkMute(sender, target);
                case "checkwarn" -> checkWarn(sender, target);
                default -> { }
            }
        });
        return true;
    }

    // ---- /checkban -------------------------------------------------------------------------

    private void checkBan(CommandSender sender, LookupSupport.Target target) {
        BanRecord ban = module.bans().get(target.id());
        IpBanManager.IpBan ipBan = null;
        String ipBanHash = null;
        if (module.ipBans() != null) {
            for (var connection : module.alts().connections(target.id())) {
                ipBan = module.ipBans().get(connection.key());
                if (ipBan != null) {
                    ipBanHash = connection.key();
                    break;
                }
            }
        }
        long since = latest(target, BAN_TYPES);
        IpBanManager.IpBan connectionBan = ipBan;
        String connectionId = SharedConnections.shortId(ipBanHash);
        LookupSupport.reply(module, sender, viewer -> {
            String shown = MM.escape(target.name());
            if (ban == null && connectionBan == null) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkban-none",
                        "player", shown));
                return;
            }
            viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkban-header", "player", shown));
            if (ban != null) {
                details(viewer, ban.staff(), ban.reason(), since, ban.until());
            }
            if (connectionBan != null) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-ipban",
                        "id", connectionId,
                        "staff", MM.escape(connectionBan.staff()),
                        "remaining", LookupSupport.remaining(viewer, connectionBan.until()),
                        "reason", LookupSupport.reason(module, viewer, connectionBan.reason())));
            }
            footer(viewer, target);
        });
    }

    // ---- /checkmute ------------------------------------------------------------------------

    private void checkMute(CommandSender sender, LookupSupport.Target target) {
        MuteManager mutes = module.plugin().getMuteManager();
        Mute mute = mutes == null ? null : mutes.getMute(target.id());
        MuteManager.IpMute ipMute = null;
        String ipMuteHash = null;
        if (mutes != null) {
            for (var connection : module.alts().connections(target.id())) {
                ipMute = mutes.ipMuteOf(connection.key());
                if (ipMute != null) {
                    ipMuteHash = connection.key();
                    break;
                }
            }
        }
        long since = latest(target, MUTE_TYPES);
        MuteManager.IpMute connectionMute = ipMute;
        String connectionId = SharedConnections.shortId(ipMuteHash);
        LookupSupport.reply(module, sender, viewer -> {
            String shown = MM.escape(target.name());
            if (mute == null && connectionMute == null) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkmute-none",
                        "player", shown));
                return;
            }
            viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkmute-header", "player", shown));
            if (mute != null) {
                if (mute.soft) viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-soft"));
                details(viewer, mute.by, mute.reason, since, mute.until);
            }
            if (connectionMute != null) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-ipmute",
                        "id", connectionId,
                        "staff", MM.escape(connectionMute.by()),
                        "remaining", LookupSupport.remaining(viewer, connectionMute.until()),
                        "reason", LookupSupport.reason(module, viewer, connectionMute.reason())));
            }
            footer(viewer, target);
        });
    }

    // ---- /checkwarn ------------------------------------------------------------------------

    private void checkWarn(CommandSender sender, LookupSupport.Target target) {
        List<WarnLedger.Warn> active = activeWarns(module, target.id());
        var points = module.plugin().getWarningPoints();
        int total = points == null ? 0 : points.get(target.id());
        LookupSupport.reply(module, sender, viewer -> {
            String shown = MM.escape(target.name());
            if (active.isEmpty()) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkwarn-none",
                        "player", shown, "points", total));
                return;
            }
            viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkwarn-header",
                    "player", shown, "count", active.size(), "points", total));
            for (WarnLedger.Warn warn : active) {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.checkwarn-entry",
                                "id", warn.id(), "staff", MM.escape(warn.staff()),
                                "ago", LookupSupport.ago(viewer, warn.time()),
                                "date", LookupSupport.date(warn.time()),
                                "reason", LookupSupport.reason(module, viewer, warn.reason()))
                        .clickEvent(ClickEvent.suggestCommand("/chatalyst:unwarn "
                                + target.name() + " " + warn.id())));
            }
            footer(viewer, target);
        });
    }

    /** Avisos activos de una cuenta (historial menos retirados, con el decaimiento). */
    static List<WarnLedger.Warn> activeWarns(StaffModerationModule module, java.util.UUID id) {
        var points = module.plugin().getWarningPoints();
        if (points == null) return List.of();
        List<WarnLedger.Warn> warns = new ArrayList<>();
        for (HistoryLog.Entry entry : module.history().list(id)) {
            if (entry.type().equals("warn")) {
                warns.add(new WarnLedger.Warn(0, entry.time(), entry.staff(), entry.detail()));
            }
        }
        return WarnLedger.active(warns, points.revokedWarns(id), points.lastInfraction(id),
                points.decayMillis(), System.currentTimeMillis());
    }

    // ---- Comun ------------------------------------------------------------------------------

    private void details(CommandSender viewer, String staff, String reason, long since,
                         long until) {
        viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-by",
                "staff", MM.escape(staff == null || staff.isBlank() ? "?" : staff)));
        viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-reason",
                "reason", LookupSupport.reason(module, viewer, reason)));
        if (since > 0) {
            viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-since",
                    "date", LookupSupport.date(since), "ago", LookupSupport.ago(viewer, since)));
        }
        viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-until",
                "date", LookupSupport.expires(viewer, until),
                "remaining", LookupSupport.remaining(viewer, until)));
    }

    private void footer(CommandSender viewer, LookupSupport.Target target) {
        viewer.sendMessage(Lang.get(viewer, "staff-lookup.check-line-more",
                        "player", MM.escape(target.name()))
                .clickEvent(ClickEvent.runCommand("/chatalyst:check " + target.name())));
    }

    /** Momento del castigo mas reciente de esos tipos en el historial, o 0. */
    private long latest(LookupSupport.Target target, Set<String> types) {
        long out = 0;
        for (HistoryLog.Entry entry : module.history().list(target.id())) {
            if (types.contains(entry.type())) out = Math.max(out, entry.time());
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !StaffAccess.has(sender, "chatalyst.command."
                + command.getName().toLowerCase(Locale.ROOT))) return List.of();
        return LookupSupport.knownNames(sender, args[0]);
    }
}
