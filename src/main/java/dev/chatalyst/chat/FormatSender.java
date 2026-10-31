package dev.chatalyst.chat;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.format.ChatFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;

/** Entrega un formato de chat a un solo jugador, en el servidor donde este conectado. */
public final class FormatSender {

    private FormatSender() {
    }

    /** Debe llamarse en el hilo de {@code target}. */
    public static void deliver(ChatalystPlugin plugin, Player target, String format, String text) {
        ChatFormat chatFormat = plugin.getConfigManager().getFormat(format);
        var prepared = ChatRenderer.prepare(chatFormat, target, Component.text(text),
                Map.of("channel", "", "message_uuid", ""));
        target.sendMessage(ChatRenderer.assemble(prepared, target));
    }
}
