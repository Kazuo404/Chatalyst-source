package dev.chatalyst.announce;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /announce — abre la GUI de anuncios de administracion (display + sonido + estilo +
 * mensaje, con vista previa y envio). Para anuncios rapidos sin GUI sigue existiendo
 * /broadcast <display> <mensaje>.
 */
public final class AnnounceGuiCommand implements CommandExecutor {

    private final AnnounceDraftManager drafts;

    public AnnounceGuiCommand(AnnounceDraftManager drafts) {
        this.drafts = drafts;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!p.hasPermission("chatalyst.command.announce")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        Menu.open(p, new AnnounceMenu(drafts));
        return true;
    }
}
