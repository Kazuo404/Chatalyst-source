package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /mail send <jugador> <mensaje>
 * /mail read
 * /mail clear
 */
public final class MailCommand implements CommandExecutor, TabCompleter {

    private final MailManager mail;
    private final JavaPlugin plugin;
    private final MailComposeManager compose;
    private final MailNotifier notifier;
    // DateTimeFormatter es inmutable y thread-safe (a diferencia de SimpleDateFormat).
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    public MailCommand(JavaPlugin plugin, MailManager mail, MailComposeManager compose, MailNotifier notifier) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.notifier = notifier;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length == 0) {
            Menu.open(p, new MailMenu(plugin, mail, compose, 0));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open", "gui", "inbox" -> Menu.open(p, new MailMenu(plugin, mail, compose, 0));
            case "spam" -> Menu.open(p, new MailMenu(plugin, mail, compose, 0, MailFolder.SPAM));
            case "friends", "amigos" -> Menu.open(p, new MailFriendsMenu(plugin, mail, compose));
            case "friend", "amigo" -> {
                if (args.length < 2) {
                    p.sendMessage(Lang.get(p, "mail.friend-usage"));
                    return true;
                }
                OfflinePlayer target = resolveTarget(args[1]);
                if (target == null) {
                    p.sendMessage(Lang.get(p, "common.unknown-player", "player", args[1]));
                    return true;
                }
                MailManager.FriendRequestResult result =
                        mail.requestFriend(target.getUniqueId(), p.getUniqueId(), p.getName());
                p.sendMessage(Lang.get(p, friendRequestMessage(result), "player", args[1]));
                if (result == MailManager.FriendRequestResult.SENT) {
                    notifier.notifyFriendRequestIfOnline(target.getUniqueId(), p.getName());
                }
            }
            case "accept", "aceptar" -> {
                if (args.length < 2) {
                    p.sendMessage(Lang.get(p, "mail.accept-usage"));
                    return true;
                }
                MailManager.RequestActionResult result = mail.acceptRequestFromResult(
                        p.getUniqueId(), p.getName(), args[1]);
                if (result == MailManager.RequestActionResult.APPLIED) {
                    p.sendMessage(Lang.get(p, "mail.friend-request-accepted", "player", args[1]));
                    OfflinePlayer requester = Bukkit.getOfflinePlayerIfCached(args[1]);
                    Player requesterPlayer = requester != null ? requester.getPlayer() : null;
                    if (requesterPlayer != null) {
                        String accepterName = p.getName();
                        FoliaTasks.entity(plugin, requesterPlayer, current -> current.sendMessage(
                                Lang.get(current, "mail.friend-request-accepted-by",
                                        "player", accepterName)));
                    }
                } else if (result == MailManager.RequestActionResult.ANONYMOUS_BLOCKED) {
                    p.sendMessage(Lang.get(p, "mail.friend-anonymous"));
                } else if (result == MailManager.RequestActionResult.STORAGE_FAILED) {
                    p.sendMessage(Lang.get(p, "mail.storage-error"));
                } else {
                    p.sendMessage(Lang.get(p, "mail.no-request-from", "player", args[1]));
                }
            }
            case "deny", "reject", "rechazar" -> {
                if (args.length < 2) {
                    p.sendMessage(Lang.get(p, "mail.deny-usage"));
                    return true;
                }
                MailManager.RequestActionResult result =
                        mail.denyRequestFromResult(p.getUniqueId(), args[1]);
                if (result == MailManager.RequestActionResult.APPLIED) {
                    p.sendMessage(Lang.get(p, "mail.friend-request-denied", "player", args[1]));
                } else if (result == MailManager.RequestActionResult.STORAGE_FAILED) {
                    p.sendMessage(Lang.get(p, "mail.storage-error"));
                } else {
                    p.sendMessage(Lang.get(p, "mail.no-request-from", "player", args[1]));
                }
            }
            case "replyto" -> {
                // Usado por el boton [Reply] del libro: responde a un jugador por nombre.
                if (args.length < 2) {
                    p.sendMessage(Lang.get(p, "mail.reply-usage"));
                    return true;
                }
                OfflinePlayer target = resolveTarget(args[1]);
                if (target == null) {
                    p.sendMessage(Lang.get(p, "common.unknown-player", "player", args[1]));
                    return true;
                }
                MailBook.beginCompose(p, compose, MailComposeManager.Mode.REPLY,
                        target.getUniqueId(), target.getName());
            }
            case "replyid" -> {
                boolean spam = args.length >= 2 && args[1].equalsIgnoreCase("spam");
                int at = spam ? 2 : 1;
                if (args.length <= at) {
                    p.sendMessage(Lang.get(p, "mail.reply-usage"));
                    return true;
                }
                MailManager.MailEntry entry = mail.find(p.getUniqueId(), args[at], spam);
                java.util.UUID targetId = entry == null ? null : MailMenu.senderId(entry);
                if (targetId == null) {
                    p.sendMessage(Lang.get(p, "mail.reply-unknown", "player",
                            entry == null ? "?" : MailManager.shownFrom(entry)));
                    return true;
                }
                MailBook.beginCompose(p, compose, MailComposeManager.Mode.REPLY,
                        targetId, MM.strip(MailManager.shownFrom(entry)));
            }
            case "delete", "del" -> {
                // Usado por el boton [Delete] del libro: /mail delete [spam] <index>.
                boolean spam = args.length >= 2 && args[1].equalsIgnoreCase("spam");
                int at = spam ? 2 : 1;
                if (args.length <= at) {
                    p.sendMessage(Lang.get(p, "mail.delete-usage"));
                    return true;
                }
                try {
                    int idx = Integer.parseInt(args[at]);
                    boolean deleted = spam ? mail.deleteSpam(p.getUniqueId(), idx)
                            : mail.delete(p.getUniqueId(), idx);
                    p.sendMessage(Lang.get(p,
                            deleted ? "mail.deleted" : "mail.storage-error"));
                } catch (NumberFormatException ex) {
                    p.sendMessage(Lang.get(p, "mail.delete-usage"));
                }
            }
            case "deleteid" -> {
                boolean spam = args.length >= 2 && args[1].equalsIgnoreCase("spam");
                int at = spam ? 2 : 1;
                if (args.length <= at) {
                    p.sendMessage(Lang.get(p, "mail.delete-usage"));
                    return true;
                }
                boolean deleted = mail.deleteById(p.getUniqueId(), args[at], spam);
                p.sendMessage(Lang.get(p,
                        deleted ? "mail.deleted" : "mail.storage-error"));
            }
            case "send" -> {
                if (args.length < 3) {
                    p.sendMessage(Lang.get(p, "mail.send-usage"));
                    return true;
                }
                OfflinePlayer target = resolveTarget(args[1]);
                if (target == null) {
                    p.sendMessage(Lang.get(p, "common.unknown-player", "player", args[1]));
                    return true;
                }
                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                // Anonimo: el correo lleva el ALIAS, no el nombre real (ver MailComposeListener).
                String fromName = dev.chatalyst.hook.Placeholders.isAnonymous(p.getUniqueId())
                        ? dev.chatalyst.nick.NickManager.plainName(p) : p.getName();
                MailManager.SendResult result = mail.send(target.getUniqueId(), p.getUniqueId(), fromName, message);
                if (result == MailManager.SendResult.TOO_LONG) {
                    p.sendMessage(Lang.get(p, "mail.too-long", "max", mail.maxMessageLength()));
                    return true;
                }
                if (result == MailManager.SendResult.MUTED) {
                    dev.chatalyst.ChatalystPlugin.get().getMuteManager().notifyIfMuted(p);
                    return true;
                }
                if (result == MailManager.SendResult.GONE) {
                    p.sendMessage(Lang.get(p, "anonymous.transition-silent"));
                    return true;
                }
                if (result == MailManager.SendResult.COOLDOWN) {
                    p.sendMessage(Lang.get(p, "mail.cooldown", "seconds", mail.sendCooldownRemaining(p.getUniqueId())));
                    return true;
                }
                if (result == MailManager.SendResult.STORAGE_FAILED) {
                    p.sendMessage(Lang.get(p, "mail.storage-error"));
                    return true;
                }
                if (result == MailManager.SendResult.BLOCKED
                        || result == MailManager.SendResult.PRIVACY_DISABLED) {
                    p.sendMessage(Lang.get(p, "mail.cannot-send", "player", args[1]));
                    return true;
                }
                if (result == MailManager.SendResult.IGNORED) {
                    // Le ignoran: misma respuesta que un envio normal y ningun aviso.
                    p.sendMessage(Lang.get(p, "mail.sent", "player", args[1]));
                    return true;
                }
                if (result == MailManager.SendResult.SPAM) {
                    notifier.notifySpamIfOnline(target.getUniqueId(), fromName, message);
                    p.sendMessage(Lang.get(p, "mail.sent-spam", "player", args[1]));
                } else {
                    notifier.notifyIfOnline(target.getUniqueId(), fromName, message);
                    p.sendMessage(Lang.get(p, "mail.sent", "player", args[1]));
                }
            }
            case "read" -> {
                List<MailManager.MailEntry> list = mail.get(p.getUniqueId());
                if (list.isEmpty()) {
                    p.sendMessage(Lang.get(p, "mail.empty"));
                    return true;
                }
                p.sendMessage(Lang.get(p, "mail.header", "count", list.size()));
                for (MailManager.MailEntry e : list) {
                    p.sendMessage(Lang.get(p, "mail.entry",
                            "date", DATE.format(Instant.ofEpochMilli(e.time())),
                            // shownFrom escapa el nombre guardado (texto plano, puede ser
                            // el alias de un anonimo); crudo, un nombre con tags se parseaba.
                            "from", MailManager.shownFrom(e),
                            "message", MM.escape(e.message())));
                }
                if (!mail.markAllRead(p.getUniqueId())) {
                    p.sendMessage(Lang.get(p, "mail.storage-error"));
                }
            }
            case "reply" -> {
                if (args.length < 2) {
                    p.sendMessage(Lang.get(p, "mail.reply-usage"));
                    return true;
                }
                List<MailManager.MailEntry> inbox = mail.get(p.getUniqueId());
                if (inbox.isEmpty()) {
                    p.sendMessage(Lang.get(p, "mail.no-reply"));
                    return true;
                }
                String toName = inbox.get(inbox.size() - 1).from();
                java.util.UUID toId = MailMenu.senderId(inbox.get(inbox.size() - 1));
                if (toId == null) {
                    p.sendMessage(Lang.get(p, "common.unknown-player", "player", toName));
                    return true;
                }
                String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                // Anonimo: el correo lleva el ALIAS, no el nombre real (ver MailComposeListener).
                String fromName = dev.chatalyst.hook.Placeholders.isAnonymous(p.getUniqueId())
                        ? dev.chatalyst.nick.NickManager.plainName(p) : p.getName();
                MailManager.SendResult result = mail.send(toId, p.getUniqueId(), fromName, message);
                if (result == MailManager.SendResult.TOO_LONG) {
                    p.sendMessage(Lang.get(p, "mail.too-long", "max", mail.maxMessageLength()));
                    return true;
                }
                if (result == MailManager.SendResult.MUTED) {
                    dev.chatalyst.ChatalystPlugin.get().getMuteManager().notifyIfMuted(p);
                    return true;
                }
                if (result == MailManager.SendResult.GONE) {
                    p.sendMessage(Lang.get(p, "anonymous.transition-silent"));
                    return true;
                }
                if (result == MailManager.SendResult.COOLDOWN) {
                    p.sendMessage(Lang.get(p, "mail.cooldown", "seconds", mail.sendCooldownRemaining(p.getUniqueId())));
                    return true;
                }
                if (result == MailManager.SendResult.STORAGE_FAILED) {
                    p.sendMessage(Lang.get(p, "mail.storage-error"));
                    return true;
                }
                if (result == MailManager.SendResult.BLOCKED
                        || result == MailManager.SendResult.PRIVACY_DISABLED) {
                    p.sendMessage(Lang.get(p, "mail.cannot-send", "player", toName));
                    return true;
                }
                if (result == MailManager.SendResult.IGNORED) {
                    p.sendMessage(Lang.get(p, "mail.sent", "player", toName));
                    return true;
                }
                if (result == MailManager.SendResult.SPAM) {
                    notifier.notifySpamIfOnline(toId, fromName, message);
                    p.sendMessage(Lang.get(p, "mail.sent-spam", "player", toName));
                } else {
                    notifier.notifyIfOnline(toId, fromName, message);
                    p.sendMessage(Lang.get(p, "mail.sent", "player", toName));
                }
            }
            case "clear" -> {
                boolean cleared = mail.clear(p.getUniqueId());
                p.sendMessage(Lang.get(p, cleared ? "mail.cleared" : "mail.storage-error"));
            }
            default -> p.sendMessage(Lang.get(p, "mail.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("open", "spam", "friends", "friend", "accept", "deny", "send", "read", "reply", "clear")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2
                && (args[0].equalsIgnoreCase("send") || args[0].equalsIgnoreCase("friend"))) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(p) && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) continue;
                String shown = dev.chatalyst.nick.NickManager.suggestedName(p);
                if (shown.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(shown);
            }
        }
        return out;
    }

    /**
     * Resuelve un destinatario por nombre para poder enviarle correo aunque este DESCONECTADO:
     * primero online, luego cacheado, y si no, entre los jugadores que ya han entrado a este
     * server. Devuelve null solo si nunca ha jugado aqui (para no crear destinatarios fantasma).
     */
    private OfflinePlayer resolveTarget(String name) {
        // Nombre real o apodo de alguien conectado.
        Player online = dev.chatalyst.nick.NickManager.online(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(op.getName())) return op;
        }
        return null;
    }

    private String friendRequestMessage(MailManager.FriendRequestResult result) {
        return switch (result) {
            case SENT -> "mail.friend-request-sent";
            case SELF -> "mail.friend-request-self";
            case BLOCKED -> "mail.friend-request-blocked";
            case ALREADY_FRIENDS -> "mail.friend-request-already-friends";
            case ALREADY_PENDING -> "mail.friend-request-pending";
            case TARGET_FULL -> "mail.friend-request-full";
            case ANONYMOUS_BLOCKED -> "mail.friend-anonymous";
            case STORAGE_FAILED -> "mail.storage-error";
        };
    }
}
