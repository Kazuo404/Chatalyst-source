package dev.chatalyst.mail;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SoundUtil;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends mail notifications through chat/actionbar plus an advancement-style toast. */
public final class MailNotifier {

    private static final String DEFAULT_SOUND = "minecraft:block.note_block.chime";

    private final JavaPlugin plugin;

    public MailNotifier(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void notifyIfOnline(java.util.UUID targetId, String from, String message) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            FoliaTasks.entity(plugin, target, p -> notifyNewMail(p, from, message));
        }
    }

    public void notifySpamIfOnline(java.util.UUID targetId, String from, String message) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            FoliaTasks.entity(plugin, target, p -> notifySpamMail(p, from, message));
        }
    }

    public void notifyFriendRequestIfOnline(java.util.UUID targetId, String from) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            FoliaTasks.entity(plugin, target, p -> {
                // Se MUESTRA el apodo actual, pero los botones mandan el nombre REAL
                // (el accept/deny resuelve la solicitud por nombre).
                String shown = MailManager.shownSender(null, from);
                // Mensaje con botones clicables: aceptar/rechazar sin abrir la GUI.
                Component accept = Lang.get(p, "mail.friend-request-accept-button")
                        .clickEvent(ClickEvent.runCommand("/chatalyst:inbox accept " + from))
                        .hoverEvent(HoverEvent.showText(
                                Lang.get(p, "mail.friend-request-accept-hover", "player", shown)));
                Component deny = Lang.get(p, "mail.friend-request-deny-button")
                        .clickEvent(ClickEvent.runCommand("/chatalyst:inbox deny " + from))
                        .hoverEvent(HoverEvent.showText(
                                Lang.get(p, "mail.friend-request-deny-hover", "player", shown)));
                p.sendMessage(Lang.get(p, "mail.friend-request-received", "from", shown)
                        .append(Component.space()).append(accept)
                        .append(Component.space()).append(deny));
                p.sendActionBar(Lang.get(p, "mail.friend-request-actionbar", "from", shown));
                playSound(p);
                if (plugin.getConfig().getBoolean("Mail.Notifications.Toast", true)) {
                    showToast(p, "Friend request",
                            MM.strip(shown) + " wants to be your mail friend");
                }
            });
        }
    }

    public void notifyNewMail(Player target, String from, String message) {
        if (!plugin.getConfig().getBoolean("Mail.Notifications.Enabled", true)) {
            return;
        }
        String shown = MailManager.shownSender(null, from);
        target.sendMessage(openMailMessage(target, "mail.received", "/chatalyst:inbox", "from", shown));
        target.sendActionBar(Lang.get(target, "mail.actionbar", "from", shown));
        playSound(target);
        if (plugin.getConfig().getBoolean("Mail.Notifications.Toast", true)) {
            showToast(target, "Mail from " + MM.strip(shown), preview(message));
        }
    }

    public void notifySpamMail(Player target, String from, String message) {
        if (!plugin.getConfig().getBoolean("Mail.Notifications.Enabled", true)) {
            return;
        }
        String shown = MailManager.shownSender(null, from);
        target.sendMessage(openMailMessage(target, "mail.spam-received", "/chatalyst:inbox spam", "from", shown));
        target.sendActionBar(Lang.get(target, "mail.spam-actionbar", "from", shown));
        playSound(target);
        if (plugin.getConfig().getBoolean("Mail.Notifications.Toast", true)) {
            showToast(target, "Mail sent to spam", MM.strip(shown) + ": " + preview(message));
        }
    }

    public void notifyUnreadOnJoin(Player target, int unread, MailManager.MailEntry latest) {
        if (!plugin.getConfig().getBoolean("Mail.Notifications.Enabled", true) || unread <= 0) {
            return;
        }
        String from = latest != null
                ? MM.strip(MailManager.shownFrom(latest)) : "?";
        target.sendMessage(openMailMessage(target, "mail.unread", "/chatalyst:inbox", "count", unread));
        target.sendActionBar(Lang.get(target, "mail.unread-actionbar", "count", unread));
        playSound(target);
        if (plugin.getConfig().getBoolean("Mail.Notifications.Toast", true)) {
            String title = unread == 1 ? "Unread mail from " + from : unread + " unread mails";
            String description = latest != null ? "Latest: " + preview(latest.message()) : "Open your inbox";
            showToast(target, title, description);
        }
    }

    public void notifyUnreadSpamOnJoin(Player target, int unread, MailManager.MailEntry latest) {
        if (!plugin.getConfig().getBoolean("Mail.Notifications.Enabled", true) || unread <= 0) {
            return;
        }
        String from = latest != null
                ? MM.strip(MailManager.shownFrom(latest)) : "?";
        target.sendMessage(openMailMessage(target, "mail.spam-unread", "/chatalyst:inbox spam", "count", unread));
        target.sendActionBar(Lang.get(target, "mail.spam-unread-actionbar", "count", unread));
        playSound(target);
        if (plugin.getConfig().getBoolean("Mail.Notifications.Toast", true)) {
            String title = unread == 1 ? "Unread spam from " + from : unread + " spam mails";
            String description = latest != null ? "Latest: " + preview(latest.message()) : "Open spam";
            showToast(target, title, description);
        }
    }

    private Component openMailMessage(Player player, String key, String command, Object... replacements) {
        return Lang.get(player, key, replacements)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Lang.get(player, "mail.open-hover")));
    }

    private void playSound(Player player) {
        if (!plugin.getConfig().getBoolean("Mail.Notifications.Sound.Enabled", true)) {
            return;
        }
        Sound sound = SoundUtil.sound(
                plugin.getConfig().getString("Mail.Notifications.Sound.Key", DEFAULT_SOUND),
                DEFAULT_SOUND,
                (float) plugin.getConfig().getDouble("Mail.Notifications.Sound.Volume", 0.65),
                (float) plugin.getConfig().getDouble("Mail.Notifications.Sound.Pitch", 1.35));
        SoundUtil.play(plugin, player, sound);
    }

    private void showToast(Player player, String title, String description) {
        dev.chatalyst.util.ToastUtil.show(plugin, player, title, description);
    }

    private String preview(String message) {
        String plain = MM.strip(message == null ? "" : message).trim();
        if (plain.length() <= 80) {
            return plain;
        }
        return plain.substring(0, 77) + "...";
    }

}
