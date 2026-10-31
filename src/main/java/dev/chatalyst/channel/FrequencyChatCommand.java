package dev.chatalyst.channel;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /f &lt;mensaje&gt;: manda UN mensaje a tu frecuencia de radio sin cambiar de canal, para poder
 * usar la frecuencia y el general a la vez. Requiere tener una frecuencia activa (sintonizala
 * con /frequency). El mensaje pasa por todo el pipeline normal (mute, reglas, anti-spam,
 * formato) y solo lo reciben quienes esten en tu misma frecuencia.
 */
public final class FrequencyChatCommand implements CommandExecutor {

    private final ChannelManager channels;

    public FrequencyChatCommand(ChannelManager channels) {
        this.channels = channels;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "channel.players-only"));
            return true;
        }
        if (!channels.isEnabled()) {
            p.sendMessage(Lang.get(p, "channel.disabled"));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(Lang.get(p, "frequency.chat-usage"));
            return true;
        }
        if (!FrequencyManager.active(p.getUniqueId())) {
            p.sendMessage(Lang.get(p, "frequency.chat-inactive"));
            return true;
        }
        Channel local = channels.firstFrequencyChannel();
        if (local == null || !local.canUse(p)) {
            p.sendMessage(Lang.get(p, "frequency.chat-no-channel"));
            return true;
        }
        String message = String.join(" ", args);
        // Enruta SOLO este mensaje al canal de frecuencia; el jugador sigue en su canal activo.
        // Pasa por el pipeline de chat como un mensaje normal (moderacion incluida).
        channels.routeNextMessage(p, local);
        p.chat(message);
        return true;
    }
}
