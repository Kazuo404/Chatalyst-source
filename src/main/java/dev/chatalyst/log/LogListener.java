package dev.chatalyst.log;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.entity.Player;

/**
 * Registra en el log el chat y los comandos que no se cancelaron (prioridad MONITOR, para
 * anotar solo lo que realmente paso los filtros).
 */
public final class LogListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final LogManager log;

    public LogListener(LogManager log) {
        this.log = log;
    }

    // Sin ignoreCancelled: un mensaje cancelado por la TRADUCCION en vivo si se entrego y debe
    // quedar en el log; solo se descarta el chat realmente bloqueado (mute, anti-spam, reglas).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()
                && !dev.chatalyst.chat.ChatListener.deliveredByTranslation(event)) return;
        if (!log.logsChat()) return;
        log.log("CHAT", event.getPlayer().getName(), PLAIN.serialize(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // shouldLogCommand excluye /login, /register y demas (no guardar contrasenas).
        if (!log.shouldLogCommand(event.getMessage())) return;
        log.log("CMD", event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!log.logsSigns()) return;
        String text = event.lines().stream().map(PLAIN::serialize)
                .collect(java.util.stream.Collectors.joining(" | "));
        log.log("SIGN", event.getPlayer().getName(), text + " " + signWhere(event));
    }

    /** Donde esta el cartel que se acaba de escribir (ver {@link ContentText#where}). */
    public static String signWhere(SignChangeEvent event) {
        var b = event.getBlock();
        return ContentText.where(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                event.getSide() == org.bukkit.block.sign.Side.BACK);
    }

    /**
     * Titulo de un libro que se FIRMA, o null. Lo ve todo el que tenga el libro y sale en el
     * tooltip, asi que se registra, se espia y se filtra igual que las paginas.
     */
    public static String signedTitle(PlayerEditBookEvent event) {
        if (!event.isSigning()) return null;
        var title = event.getNewBookMeta().title();
        if (title == null) return null;
        String plain = PLAIN.serialize(title).trim();
        return plain.isEmpty() ? null : plain;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!log.logsBooks()) return;
        StringBuilder text = new StringBuilder();
        String title = signedTitle(event);
        if (title != null) text.append("[titulo: ").append(title).append(']');
        for (var page : event.getNewBookMeta().pages()) {
            if (text.length() > 0) text.append(" | ");
            text.append(PLAIN.serialize(page));
            if (text.length() >= 2048) break;
        }
        log.log("BOOK", event.getPlayer().getName(), text.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (!log.logsAnvil() || event.getView().getTopInventory().getType() != InventoryType.ANVIL
                || event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        var item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;
        log.log("ANVIL", player.getName(), PLAIN.serialize(item.getItemMeta().displayName()));
    }
}
