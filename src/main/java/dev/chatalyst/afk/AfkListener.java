package dev.chatalyst.afk;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Registra la actividad del jugador para el {@link AfkManager}: movimiento (solo cambio de
 * bloque, no mirar alrededor), chat, comandos e interacciones. Prioridad MONITOR: solo observa.
 */
public final class AfkListener implements Listener {

    private final AfkManager afk;

    public AfkListener(AfkManager afk) {
        this.afk = afk;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Solo cuenta si cambia de bloque; girar la camara no saca del AFK.
        if (event.getTo().getBlockX() == event.getFrom().getBlockX()
                && event.getTo().getBlockY() == event.getFrom().getBlockY()
                && event.getTo().getBlockZ() == event.getFrom().getBlockZ()) {
            return;
        }
        afk.activity(event.getPlayer());
    }

    // Sin ignoreCancelled: chatear con la TRADUCCION en vivo activa (evento cancelado pero
    // entregado) tambien es actividad y debe sacar del AFK.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()
                && !dev.chatalyst.chat.ChatListener.deliveredByTranslation(event)) return;
        afk.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // /afk lo maneja su comando; cualquier otro comando cuenta como actividad. Se quita el
        // namespace ("/chatalyst:afk" -> "/afk") antes de comparar: sin esto, volver del AFK con
        // la forma namespaceada disparaba activity() (que desmarca) y luego el toggle re-marcaba,
        // dejando al jugador AFK justo cuando pedia salir.
        String root = event.getMessage().toLowerCase(java.util.Locale.ROOT).split("\\s+", 2)[0];
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = "/" + root.substring(namespace + 1);
        if (!root.equals("/afk")) {
            afk.activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        afk.activity(event.getPlayer());
    }

    // Romper/poner bloques cuenta como actividad (en creativo el interact no siempre salta).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        afk.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        afk.activity(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        afk.activity(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        afk.remove(event.getPlayer());
    }
}
