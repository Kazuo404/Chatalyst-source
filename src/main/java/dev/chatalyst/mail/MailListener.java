package dev.chatalyst.mail;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Avisa al jugador si tiene correo sin leer cuando entra. */
public final class MailListener implements Listener {

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailNotifier notifier;

    public MailListener(JavaPlugin plugin, MailManager mail, MailNotifier notifier) {
        this.plugin = plugin;
        this.mail = mail;
        this.notifier = notifier;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Con un pequeno retardo para que aparezca despues del MOTD. EntityScheduler: corre
        // en la region del jugador (compatible con Folia/Purpur regionizado).
        p.getScheduler().runDelayed(plugin, task -> {
            if (!p.isOnline()) return;
            int unread = mail.unreadCount(p.getUniqueId());
            if (unread > 0) {
                notifier.notifyUnreadOnJoin(p, unread, mail.latestUnread(p.getUniqueId()));
            }
            int spam = mail.unreadSpamCount(p.getUniqueId());
            if (spam > 0) {
                notifier.notifyUnreadSpamOnJoin(p, spam, mail.latestUnreadSpam(p.getUniqueId()));
            }
        }, null, 40L);
    }
}
