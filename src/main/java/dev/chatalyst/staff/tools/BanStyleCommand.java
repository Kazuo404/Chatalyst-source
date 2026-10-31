package dev.chatalyst.staff.tools;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffModerationModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /banstyle [preview]: abre el diseno de los anuncios de castigo o solo lo ensena. */
public final class BanStyleCommand implements CommandExecutor {

    private final StaffModerationModule module;

    public BanStyleCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "staff-tools.banstyle.players-only"));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("preview")
                || args[0].equalsIgnoreCase("ver"))) {
            PunishmentBroadcast.preview(module, player);
            return true;
        }
        Menu.open(player, new BroadcastStyleMenu(module));
        return true;
    }
}
