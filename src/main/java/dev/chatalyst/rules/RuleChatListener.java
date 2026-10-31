package dev.chatalyst.rules;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Evalua las reglas antes de que se formatee el chat (prioridad LOWEST). Puede cancelar
 * el mensaje o reemplazar su texto; el formateo (ChatListener, prioridad NORMAL) actua despues.
 */
public final class RuleChatListener implements Listener {

    private final RuleEngine engine;

    public RuleChatListener(RuleEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!engine.isEnabled()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        RuleResult result = engine.check(event.getPlayer(), text, RuleContext.CHAT);
        if (result.cancelled) {
            event.setCancelled(true);
            if (result.silent) {
                // Deny_Silently: el infractor ve su mensaje como si se hubiera enviado
                // (formateado con su canal), pero nadie mas lo recibe. Como el evento se
                // cancela, tampoco llega al proxy, Discord ni al historial.
                echoToSender(event);
            }
            return;
        }
        if (result.modified) {
            event.message(Component.text(result.message));
        }
    }

    private void echoToSender(AsyncChatEvent event) {
        var pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl == null || pl.getConfigManager() == null) return;
        var p = event.getPlayer();
        dev.chatalyst.channel.Channel ch = pl.getChannelManager() != null
                && pl.getChannelManager().isEnabled()
                ? pl.getChannelManager().getWriteChannel(p) : null;
        var fmt = ch != null ? pl.getConfigManager().getFormat(ch.format)
                : pl.getConfigManager().getChatFormat();
        if (fmt == null) return;
        var prepared = dev.chatalyst.chat.ChatRenderer.prepare(fmt, p, event.message(),
                java.util.Map.of("channel", ch != null ? ch.name : "", "message_uuid", ""));
        p.sendMessage(dev.chatalyst.chat.ChatRenderer.assemble(prepared, p));
    }
}
