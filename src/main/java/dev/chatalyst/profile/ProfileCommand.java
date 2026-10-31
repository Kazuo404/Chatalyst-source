package dev.chatalyst.profile;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /perfil (alias /profile): abre el perfil propio. */
public final class ProfileCommand implements CommandExecutor, TabCompleter {

    private final ChatalystPlugin plugin;

    public ProfileCommand(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!plugin.getConfig().getBoolean("Profile.Enabled", true)) {
            p.sendMessage(Lang.get(p, "profile.disabled"));
            return true;
        }
        Menu.open(p, new ProfileMenu(plugin));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
