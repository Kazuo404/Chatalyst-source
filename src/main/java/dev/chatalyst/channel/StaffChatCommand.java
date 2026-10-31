package dev.chatalyst.channel;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.chat.ChatRenderer;
import dev.chatalyst.format.ChatFormat;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.Sanitize;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * /staffchat &lt;message&gt; (/sc)  Sends a one-off message to the staff channel without changing
 * the sender's active channel. Uses the staff channel's format and visibility.
 */
public final class StaffChatCommand implements CommandExecutor {

    private final ChatalystPlugin plugin;

    public StaffChatCommand(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!p.hasPermission("chatalyst.channel.staff")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(Lang.get(p, "staffchat.usage"));
            return true;
        }

        ChannelManager channels = plugin.getChannelManager();
        Channel staff = channels.byName("staff");
        if (staff == null) {
            p.sendMessage(Lang.get(p, "channel.unknown", "name", "staff"));
            return true;
        }

        String text = Sanitize.stripInvisible(String.join(" ", args)).trim();
        if (text.isEmpty()) return true;

        ChatFormat fmt = plugin.getConfigManager().getFormat(staff.format);
        Component msg = Component.text(text);
        var prepared = ChatRenderer.prepare(fmt, p, msg, Map.of("channel", "staff", "message_uuid", ""));

        // Despacho por entidad (Folia): cada online.hasPermission/sendMessage corre en el
        // hilo del propio jugador. prepared es inmutable y assemble no tiene estado.
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                online -> channels.canSee(online, p, staff),
                online -> online.sendMessage(ChatRenderer.assemble(prepared, online)));
        plugin.getServer().getConsoleSender().sendMessage(ChatRenderer.assemble(prepared, null));
        // Guard de null: el notifier se crea en onEnable; un evento muy temprano (o el
        // apagado) puede cruzarse antes de que exista.
        if (plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().staffChat(p.getName(), text);
        }
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(p, "staffchat", text);
        }
        return true;
    }
}
