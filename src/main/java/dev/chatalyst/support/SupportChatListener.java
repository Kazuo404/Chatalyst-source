package dev.chatalyst.support;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Captures active support chat before the normal renderer, history and network monitor. */
public final class SupportChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final SupportConversationManager support;

    public SupportChatListener(SupportConversationManager support) {
        this.support = support;
    }

    // LOW, no NORMAL: el renderer publico corre en NORMAL y dentro de una misma prioridad
    // Bukkit ejecuta por orden de registro. Con LOW la captura precede SIEMPRE al renderer
    // (privacidad garantizada por estructura, no por el orden de lineas de onEnable);
    // mute/sanitizer/reglas (LOWEST) siguen aplicando antes.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!support.handleChat(event.getPlayer(), PLAIN.serialize(event.message()))) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        support.sync(event.getPlayer());
    }
}
