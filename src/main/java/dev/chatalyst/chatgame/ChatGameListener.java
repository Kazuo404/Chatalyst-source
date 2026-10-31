package dev.chatalyst.chatgame;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Mira cada mensaje del chat por si es la respuesta.
 *
 * <p>LOW con ignoreCancelled, como el bot de respuestas: mute, reglas y anti-spam corren antes y
 * un mensaje que ellos cortaron no puede ganar nada. El mensaje NO se cancela: el acierto se ve
 * en el chat, que es la gracia de jugar en publico.
 */
public final class ChatGameListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ChatGameService games;

    public ChatGameListener(ChatGameService games) {
        this.games = games;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (games.current().isEmpty()) return;
        String text = PLAIN.serialize(event.message());
        if (text.length() > 64) return;
        games.attempt(event.getPlayer(), text);
    }
}
