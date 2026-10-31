package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /players  Abre la lista visual de jugadores para staff. */
public final class PlayerListCommand implements CommandExecutor {

    private final ChatalystPlugin plugin;

    public PlayerListCommand(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffAccess.has(sender, "chatalyst.command.players")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        PlayerListMenu.open(plugin, p);
        return true;
    }
}
