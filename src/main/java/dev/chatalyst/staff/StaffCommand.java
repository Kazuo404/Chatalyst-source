package dev.chatalyst.staff;

import dev.chatalyst.staff.lookup.ActiveListCommand;
import dev.chatalyst.staff.lookup.CheckStatusCommand;
import dev.chatalyst.staff.lookup.IdentityCommand;
import dev.chatalyst.staff.lookup.IpMuteCommand;
import dev.chatalyst.staff.lookup.UnwarnCommand;
import dev.chatalyst.staff.tools.HistoryCommand;
import dev.chatalyst.staff.tools.LockdownCommand;
import dev.chatalyst.staff.tools.PruneHistoryCommand;
import dev.chatalyst.staff.tools.StaffHistoryCommand;
import dev.chatalyst.staff.tools.StaffRollbackCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** Small command router; each moderation domain owns its own executor. */
public final class StaffCommand implements CommandExecutor, TabCompleter {

    private final StaffModeCommand staffMode;
    private final PunishmentCommand punishments;
    private final FreezeCommand freeze;
    private final VanishCommand vanish;
    private final StaffToolsCommand tools;
    private final IpBanCommand ipBans;
    private final ContainerInspectMode chestView;
    // 2026-10-03: consultas e historial de staff (staff/lookup y staff/tools).
    private final IdentityCommand identity;
    private final CheckStatusCommand checks;
    private final ActiveListCommand lists;
    private final IpMuteCommand ipMutes;
    private final UnwarnCommand unwarn;
    private final HistoryCommand history;
    private final StaffHistoryCommand staffHistory;
    private final StaffRollbackCommand rollback;
    private final PruneHistoryCommand prune;
    private final LockdownCommand lockdown;
    private final dev.chatalyst.staff.tools.BanStyleCommand banStyle;

    public StaffCommand(StaffModerationModule module) {
        this.staffMode = new StaffModeCommand(module);
        this.punishments = new PunishmentCommand(module);
        this.freeze = new FreezeCommand(module);
        this.vanish = new VanishCommand(module);
        this.tools = new StaffToolsCommand(module);
        this.ipBans = new IpBanCommand(module);
        this.chestView = module.chestView();
        this.identity = new IdentityCommand(module);
        this.checks = new CheckStatusCommand(module);
        this.lists = new ActiveListCommand(module);
        this.ipMutes = new IpMuteCommand(module);
        this.unwarn = new UnwarnCommand(module);
        this.history = new HistoryCommand(module);
        this.staffHistory = new StaffHistoryCommand(module);
        this.rollback = new StaffRollbackCommand(module);
        this.prune = new PruneHistoryCommand(module);
        this.lockdown = new LockdownCommand(module, module.lockdown());
        this.banStyle = new dev.chatalyst.staff.tools.BanStyleCommand(module);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        return handler(command).onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String permission = StaffCommandCatalog.permissionFor(command.getName());
        if (permission != null && !StaffAccess.has(sender, permission)) return List.of();
        CommandExecutor handler = handler(command);
        return handler instanceof TabCompleter completer
                ? completer.onTabComplete(sender, command, alias, args) : List.of();
    }

    private CommandExecutor handler(Command command) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "staff" -> staffMode;
            case "kick", "warn", "ban", "tempban", "unban" -> punishments;
            case "freeze", "unfreeze" -> freeze;
            case "vanish" -> vanish;
            case "invsee", "echest", "vaultsee", "seen", "check", "note", "tpto", "tphere",
                 "clearinventory" -> tools;
            case "banip", "unbanip" -> ipBans;
            case "chestview" -> chestView;
            case "dupeip", "ipreport", "iphistory", "namehistory", "lastuuid" -> identity;
            case "checkban", "checkmute", "checkwarn" -> checks;
            case "banlist", "mutelist", "warnlist" -> lists;
            case "ipmute", "unipmute" -> ipMutes;
            case "unwarn" -> unwarn;
            case "history" -> history;
            case "staffhistory" -> staffHistory;
            case "staffrollback" -> rollback;
            case "prunehistory" -> prune;
            case "lockdown" -> lockdown;
            case "banstyle" -> banStyle;
            default -> (sender, ignored, label, args) -> false;
        };
    }
}
