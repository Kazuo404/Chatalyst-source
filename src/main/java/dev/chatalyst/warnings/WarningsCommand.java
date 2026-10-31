package dev.chatalyst.warnings;

import dev.chatalyst.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /warnings <jugador>        Ver los puntos de advertencia de un jugador.
 * /warnings <jugador> clear  Poner sus puntos a cero.
 */
public final class WarningsCommand implements CommandExecutor, TabCompleter {

    private final WarningPoints warnings;

    public WarningsCommand(WarningPoints warnings) {
        this.warnings = warnings;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("chatalyst.command.warnings")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "warnings.usage"));
            return true;
        }
        Player target = dev.chatalyst.nick.NickManager.online(args[0]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.player-offline"));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            if (warnings.clear(target.getUniqueId())) {
                sender.sendMessage(Lang.get(sender, "warnings.cleared", "player", target.getName()));
            } else {
                sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            }
            return true;
        }
        sender.sendMessage(Lang.get(sender, "warnings.show", "player", target.getName(), "points", warnings.get(target)));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(p) && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
            }
        } else if (args.length == 2) {
            out.add("clear");
        }
        return out;
    }
}
