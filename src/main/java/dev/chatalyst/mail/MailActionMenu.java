package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Action menu opened with right click on a mail entry. */
public final class MailActionMenu implements Menu {

    // Simetria: correo arriba al centro (4), acciones espejadas alrededor del 13 en la fila
    // central (el hueco 13 se rellena con "amigo" solo si aplica), volver abajo al centro.
    private static final int MESSAGE_SLOT = 4;
    private static final int OPEN_SLOT = 10;
    private static final int REPLY_SLOT = 12;
    private static final int FRIEND_SLOT = 13;
    private static final int BLOCK_SLOT = 14;
    private static final int DELETE_SLOT = 16;
    private static final int BACK_SLOT = 22;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private final String entryId;
    private final int index;
    private final int page;
    private final MailFolder folder;

    public MailActionMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose,
                          MailManager.MailEntry entry, int index, int page, MailFolder folder) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.entryId = entry.id();
        this.index = index;
        this.page = page;
        this.folder = folder != null ? folder : MailFolder.INBOX;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("mail.gui-actions-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-actions-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        MailManager.MailEntry entry = entry(viewer);
        if (entry == null) {
            // Estado vacio: aviso en el centro exacto (13) y volver abajo al centro.
            inv.setItem(13, MailMenu.basic(Material.BARRIER, Lang.raw(viewer, "mail.gui-not-found"),
                    List.of(Lang.raw(viewer, "mail.gui-not-found-lore"))));
            inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
            return;
        }
        inv.setItem(OPEN_SLOT, MailMenu.basic(Material.WRITTEN_BOOK, Lang.raw(viewer, "mail.gui-open-action"),
                List.of(Lang.raw(viewer, "mail.gui-read-mail"))));
        String shown = MailManager.shownFrom(entry);
        inv.setItem(REPLY_SLOT, MailMenu.basic(Material.WRITABLE_BOOK, Lang.raw(viewer, "mail.gui-reply"),
                List.of(Lang.raw(viewer, "mail.gui-write-back", "from", shown))));
        if (!entry.anonymous()
                && !mail.areFriends(viewer.getUniqueId(), MailMenu.senderId(entry), entry.from())) {
            inv.setItem(FRIEND_SLOT, MailMenu.basic(Material.EMERALD, Lang.raw(viewer, "mail.gui-friend-request"),
                    List.of(Lang.raw(viewer, "mail.gui-ask-friend", "from", shown),
                            Lang.raw(viewer, "mail.gui-click-send"))));
        }
        inv.setItem(BLOCK_SLOT, MailMenu.basic(Material.REDSTONE_BLOCK, Lang.raw(viewer, "mail.gui-block"),
                List.of(Lang.raw(viewer, "mail.gui-block-lore", "from", shown))));
        inv.setItem(DELETE_SLOT, MailMenu.basic(Material.BARRIER, Lang.raw(viewer, "mail.gui-delete"),
                List.of(Lang.raw(viewer, "mail.gui-delete-inbox"))));
        inv.setItem(MESSAGE_SLOT, messageItem(viewer, entry));
        inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        MailManager.MailEntry entry = entry(player);
        if (entry == null) {
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        if (slot == OPEN_SLOT || slot == MESSAGE_SLOT) {
            markRead(player);
            Menu.open(player, new MailDetailMenu(plugin, mail, compose,
                    entry, index, page, folder));
            return;
        }
        if (slot == REPLY_SLOT) {
            MailMenu.startReply(player, compose, entry);
            return;
        }
        if (slot == FRIEND_SLOT) {
            if (entry.anonymous()) return;
            sendFriendRequest(player, entry);
            return;
        }
        if (slot == BLOCK_SLOT) {
            UUID senderId = MailMenu.senderId(entry);
            MailManager.BlockResult result =
                    mail.blockResult(player.getUniqueId(), senderId, entry.from());
            String key = switch (result) {
                case ADDED -> "mail.blocked-sender";
                case ALREADY_BLOCKED -> "mail.blocked-already";
                case INVALID, STORAGE_FAILED -> "mail.storage-error";
            };
            player.sendMessage(Lang.get(player, key, "player", MailManager.shownFrom(entry)));
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        if (slot == DELETE_SLOT) {
            boolean deleted = delete(player, entry);
            player.sendMessage(Lang.get(player,
                    deleted ? "mail.deleted" : "mail.storage-error"));
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
        }
    }

    private MailManager.MailEntry entry(Player player) {
        return mail.find(player.getUniqueId(), entryId, folder == MailFolder.SPAM);
    }

    private void markRead(Player player) {
        MailManager.MailEntry entry = entry(player);
        if (entry == null) return;
        boolean marked = folder == MailFolder.SPAM
                ? mail.markSpamRead(player.getUniqueId(), entry)
                : mail.markRead(player.getUniqueId(), entry);
        if (!marked) player.sendMessage(Lang.get(player, "mail.storage-error"));
    }

    private boolean delete(Player player, MailManager.MailEntry entry) {
        return folder == MailFolder.SPAM
                ? mail.deleteSpam(player.getUniqueId(), entry)
                : mail.delete(player.getUniqueId(), entry);
    }

    private void sendFriendRequest(Player player, MailManager.MailEntry entry) {
        UUID targetId = MailMenu.senderId(entry);
        if (targetId == null) {
            player.sendMessage(Lang.get(player, "mail.reply-unknown", "player", MailManager.shownFrom(entry)));
            return;
        }
        MailManager.FriendRequestResult result = mail.requestFriend(targetId, player.getUniqueId(), player.getName());
        player.sendMessage(Lang.get(player, friendRequestMessage(result), "player", MailManager.shownFrom(entry)));
        if (result == MailManager.FriendRequestResult.SENT) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                String senderName = player.getName();
                FoliaTasks.entity(plugin, target, current -> current.sendMessage(
                        Lang.get(current, "mail.friend-request-received", "from", senderName)));
            }
        }
        Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
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

    private org.bukkit.inventory.ItemStack messageItem(Player viewer, MailManager.MailEntry entry) {
        ArrayList<String> lore = new ArrayList<>();
        lore.add("<#7B68A0>" + MailMenu.DATE.format(Instant.ofEpochMilli(entry.time())));
        lore.add(Lang.raw(viewer, "mail.gui-from", "from",
                MailManager.shownFrom(entry)));
        lore.add("");
        lore.addAll(MailMenu.messageLore(viewer, entry.message(), "<#C0B3D9>"));
        return MailMenu.basic(Material.PAPER, Lang.raw(viewer, "mail.gui-selected-mail"), lore);
    }
}
