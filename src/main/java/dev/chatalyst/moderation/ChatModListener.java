package dev.chatalyst.moderation;

import dev.chatalyst.lang.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Aplica el bloqueo global del chat y el slowmode. Prioridad LOW (tras mute y reglas,
 * junto al anti-spam, antes del formateo).
 */
public final class ChatModListener implements Listener {

    private final ChatModeration mod;

    public ChatModListener(ChatModeration mod) {
        this.mod = mod;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (mod.isLocked() && !event.getPlayer().isOp()
                && !event.getPlayer().hasPermission(ChatModeration.BYPASS)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Lang.get(event.getPlayer(), "chatmod.chat-blocked"));
            return;
        }
        long remaining = mod.slowmodeRemaining(event.getPlayer());
        if (remaining > 0) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Lang.get(event.getPlayer(), "chatmod.slowmode-wait", "seconds", remaining));
            return;
        }
        mod.markSpoke(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mod.clear(event.getPlayer().getUniqueId());
    }
}
