package dev.chatalyst.announce;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Captura el mensaje del anuncio que el admin escribe por chat tras pulsar "mensaje" en la
 * GUI del /announce. El texto NO llega al chat publico; al terminar se reabre la GUI.
 */
public final class AnnounceComposeListener implements Listener {

    private final JavaPlugin plugin;
    private final AnnounceDraftManager drafts;

    public AnnounceComposeListener(JavaPlugin plugin, AnnounceDraftManager drafts) {
        this.plugin = plugin;
        this.drafts = drafts;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!drafts.isComposing(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        drafts.endComposing(player.getUniqueId());
        FoliaTasks.entity(plugin, player, p -> {
            if (!drafts.canUse(p)) {
                p.sendMessage(Lang.get(p, "common.no-permission"));
                return;
            }
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("cancelar")) {
                p.sendMessage(Lang.get(p, "announce-gui.compose-cancelled"));
            } else if (!text.isEmpty()) {
                // Sin invisibles, y con :emojis: propios + iconos de ItemsAdder resueltos
                // (es un admin: sin chequeo de permisos por emoji).
                String cleaned = dev.chatalyst.util.Sanitize.stripInvisible(text);
                cleaned = dev.chatalyst.chat.ChatRenderer.applyEmojis(cleaned);
                cleaned = dev.chatalyst.hook.ItemsAdderHook.replaceEmojis(p, cleaned);
                if (!AnnounceDraftManager.validMessage(cleaned)) {
                    p.sendMessage(Lang.get(p, "announce-gui.too-long",
                            "max", AnnounceDraftManager.MAX_DRAFT_MESSAGE_LENGTH));
                    return;
                }
                drafts.draft(p.getUniqueId()).message = cleaned;
            }
            Menu.open(p, new AnnounceMenu(drafts));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.forget(event.getPlayer().getUniqueId());
    }
}
