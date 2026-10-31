package dev.chatalyst.antispam;

import dev.chatalyst.lang.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Aplica anti-spam (corta el mensaje, avisa y escala hacia auto-mute) y grammar (lo corrige)
 * despues del mute y las reglas (prioridad LOW) pero antes del formateo (NORMAL).
 */
public final class ChatGuardListener implements Listener {

    private final AntiSpam antiSpam;
    private final Grammar grammar;
    private final SpamPunisher punisher;

    public ChatGuardListener(AntiSpam antiSpam, Grammar grammar, SpamPunisher punisher) {
        this.antiSpam = antiSpam;
        this.grammar = grammar;
        this.punisher = punisher;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player p = event.getPlayer();

        AntiSpam.Result spam = antiSpam.check(p, text);
        if (spam != AntiSpam.Result.OK) {
            event.setCancelled(true);
            p.sendMessage(Lang.get(p, spam == AntiSpam.Result.TOO_FAST
                    ? "antispam.too-fast" : "antispam.duplicate"));
            // Escalating warnings -> auto-mute.
            punisher.onSpam(p);
            return;
        }

        String fixed = grammar.apply(text);
        if (!fixed.equals(text)) {
            event.message(Component.text(fixed));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        antiSpam.clear(event.getPlayer().getUniqueId());
        punisher.clear(event.getPlayer().getUniqueId());
    }
}
