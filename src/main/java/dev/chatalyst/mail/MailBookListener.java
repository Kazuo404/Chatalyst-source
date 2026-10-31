package dev.chatalyst.mail;

import dev.chatalyst.lang.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Envia el correo cuando el jugador cierra (o firma) el "libro y pluma" de redaccion que le
 * dimos al empezar a escribir un mail. Reconoce ese libro por su marcador y por la sesion de
 * redaccion activa, para no interferir con otros libros.
 */
public final class MailBookListener implements Listener {

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private final MailNotifier notifier;

    public MailBookListener(JavaPlugin plugin, MailManager mail,
                            MailComposeManager compose, MailNotifier notifier) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.notifier = notifier;
    }

    @EventHandler
    @SuppressWarnings("deprecation") // Paper 1.21.1 no expone un reemplazo para el slot editado.
    public void onEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        MailComposeManager.Session session = compose.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        // El meta anterior conserva el PDC del item entregado por el servidor incluso si el
        // cliente lo firma. Un libro ajeno o de una sesion anterior nunca consume el borrador.
        if (!MailBook.isComposeMeta(event.getPreviousBookMeta(), session.token())) {
            return;
        }
        String text = MailBook.extractText(event.getNewBookMeta());
        compose.end(player.getUniqueId());
        // No guardamos el libro firmado ni dejamos el libro-y-pluma en el inventario.
        event.setCancelled(true);
        // El borrado va el tick siguiente: si tocamos el inventario dentro del propio
        // PlayerEditBookEvent, vanilla lo pisa al terminar el evento y el libro reaparece.
        player.getScheduler().run(plugin, task -> removeComposeBooks(player), null);

        if (text.isEmpty()) {
            player.sendMessage(Lang.get(player, "mail.compose-cancelled"));
            return;
        }
        // Igual que /inbox send y el compositor por chat: persistir el alias anonimo. Este
        // frontend por libro era el unico que aun guardaba/notificaba el nombre real.
        String fromName = dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                ? dev.chatalyst.nick.NickManager.plainName(player) : player.getName();
        MailManager.SendResult result =
                mail.send(session.targetId(), player.getUniqueId(), fromName, text);
        if (result == MailManager.SendResult.TOO_LONG) {
            player.sendMessage(Lang.get(player, "mail.too-long", "max", mail.maxMessageLength()));
            return;
        }
        if (result == MailManager.SendResult.MUTED) {
            dev.chatalyst.ChatalystPlugin.get().getMuteManager().notifyIfMuted(player);
            return;
        }
        if (result == MailManager.SendResult.GONE) {
            player.sendMessage(Lang.get(player, "anonymous.transition-silent"));
            return;
        }
        if (result == MailManager.SendResult.COOLDOWN) {
            player.sendMessage(Lang.get(player, "mail.cooldown", "seconds",
                    mail.sendCooldownRemaining(player.getUniqueId())));
            return;
        }
        if (result == MailManager.SendResult.STORAGE_FAILED) {
            player.sendMessage(Lang.get(player, "mail.storage-error"));
            return;
        }
        if (result == MailManager.SendResult.BLOCKED
                || result == MailManager.SendResult.PRIVACY_DISABLED) {
            player.sendMessage(Lang.get(player, "mail.cannot-send",
                    "player", dev.chatalyst.util.MM.escape(session.targetName())));
            return;
        }
        if (result == MailManager.SendResult.IGNORED) {
            // Le ignoran: misma respuesta que un envio normal y ningun aviso.
            player.sendMessage(Lang.get(player, "mail.sent",
                    "player", dev.chatalyst.util.MM.escape(session.targetName())));
            return;
        }
        if (result == MailManager.SendResult.SPAM) {
            player.sendMessage(Lang.get(player, "mail.sent-spam",
                    "player", dev.chatalyst.util.MM.escape(session.targetName())));
            notifier.notifySpamIfOnline(session.targetId(), fromName, text);
        } else {
            player.sendMessage(Lang.get(player, "mail.sent",
                    "player", dev.chatalyst.util.MM.escape(session.targetName())));
            notifier.notifyIfOnline(session.targetId(), fromName, text);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        compose.end(event.getPlayer().getUniqueId());
        // El libro-y-pluma de redaccion lo dio el servidor GRATIS. Al salir sin enviar se
        // quedaba en el inventario como un libro normal: abrir y abandonar el compositor
        // era una fuente infinita de libros y plumas. Sin sesion ya no sirve para nada.
        removeComposeBooks(event.getPlayer());
    }

    /** Tirado al suelo deja de ser un borrador: se esfuma en vez de regalarse a quien lo coja. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (MailBook.isComposeBook(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    // Quita el libro-y-pluma del slot que se edito y cualquier otro residual del correo, y
    // refresca el inventario del cliente (si no, el libro sigue viendose aunque ya se envio).
    private void removeComposeBooks(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (MailBook.isComposeBook(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
        player.updateInventory();
    }
}
