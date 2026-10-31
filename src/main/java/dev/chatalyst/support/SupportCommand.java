package dev.chatalyst.support;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Staff controls for claiming and closing a private support conversation. */
public final class SupportCommand implements CommandExecutor {

    private final SupportConversationManager support;

    public SupportCommand(SupportConversationManager support) {
        this.support = support;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            support.status(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("close") || args[0].equalsIgnoreCase("cerrar")) {
            support.close(player);
            return true;
        }
        if ((args[0].equalsIgnoreCase("answer") || args[0].equalsIgnoreCase("responder")
                || args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("atender"))
                && args.length == 2) {
            try {
                support.accept(player, UUID.fromString(args[1]));
            } catch (IllegalArgumentException ex) {
                player.sendMessage(Lang.get(player, "support.request-unavailable"));
            }
            return true;
        }
        player.sendMessage(Lang.get(player, "support.usage"));
        return true;
    }
}
