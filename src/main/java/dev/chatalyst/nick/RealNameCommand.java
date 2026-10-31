package dev.chatalyst.nick;

import dev.chatalyst.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * /realname <apodo>  Muestra el nombre real de quien usa ese apodo.
 */
public final class RealNameCommand implements CommandExecutor {

    private final NickManager nicks;

    public RealNameCommand(NickManager nicks) {
        this.nicks = nicks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("chatalyst.command.realname")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "nick.realname-usage"));
            return true;
        }
        // Un apodo puede llevar espacios ("El Rey"): se busca la frase entera, no la
        // primera palabra, igual que /nick la guarda.
        String typed = NickText.joinArguments(args, 0);
        String shown = dev.chatalyst.util.MM.escape(typed);
        UUID id = nicks.findByDisplay(typed);
        Player found = id != null ? Bukkit.getPlayer(id) : Bukkit.getPlayerExact(typed);
        if (found == null) {
            sender.sendMessage(Lang.get(sender, "nick.realname-none", "nick", shown));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "nick.realname", "nick", shown, "player", found.getName()));
        return true;
    }
}
