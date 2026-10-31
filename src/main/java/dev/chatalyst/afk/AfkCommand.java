package dev.chatalyst.afk;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /afk  Toggle your own AFK status manually. */
public final class AfkCommand implements CommandExecutor {

    private final AfkManager afk;

    public AfkCommand(AfkManager afk) {
        this.afk = afk;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player p) {
            afk.toggle(p);
        } else {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
        }
        return true;
    }
}
