package dev.chatalyst.messages;

import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /motd — reenvia al jugador el saludo de entrada bajo demanda.
 * /motd first — previsualiza la variante de primera visita (util para el staff).
 */
public final class MotdCommand implements CommandExecutor, TabCompleter {

    private final ServerMessages messages;

    public MotdCommand(ServerMessages messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!messages.motdEnabled()) {
            p.sendMessage(Lang.get(p, "motd.disabled"));
            return true;
        }
        boolean firstTime = args.length > 0 && args[0].equalsIgnoreCase("first");
        List<Component> motd = messages.motd(p, firstTime);
        if (motd.isEmpty()) {
            p.sendMessage(Lang.get(p, "motd.disabled"));
            return true;
        }
        motd.forEach(p::sendMessage);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && "first".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("first");
        }
        return List.of();
    }
}
