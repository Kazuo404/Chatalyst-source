package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inbox GUI for reading, replying and deleting player mail. */
public final class MailMenu implements Menu {

    static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private static final int PAGE_SIZE = 45;
    private static final int PREV_SLOT = 45;
    private static final int REFRESH_SLOT = 46;
    private static final int FRIENDS_SLOT = 47;
    private static final int COMPOSE_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int FOLDER_SLOT = 50;
    private static final int SETTINGS_SLOT = 51;
    private static final int CLEAR_SLOT = 52;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private final int page;
    private final MailFolder folder;
    private record Selection(MailManager.MailEntry entry, int index) {
    }

    private final Map<Integer, Selection> slots = new HashMap<>();

    public MailMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose, int page) {
        this(plugin, mail, compose, page, MailFolder.INBOX);
    }

    public MailMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose,
                    int page, MailFolder folder) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.page = Math.max(0, page);
        this.folder = folder != null ? folder : MailFolder.INBOX;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(folder == MailFolder.SPAM ? "mail.gui-title-spam" : "mail.gui-title-inbox"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, folder == MailFolder.SPAM ? "mail.gui-title-spam" : "mail.gui-title-inbox");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<MailManager.MailEntry> inbox = entries(viewer);
        int pages = pages(inbox.size());
        int safePage = Math.min(page, pages - 1);
        int start = inbox.size() - 1 - (safePage * PAGE_SIZE);
        int slot = 0;
        for (int index = start; index >= 0 && slot < PAGE_SIZE; index--, slot++) {
            MailManager.MailEntry entry = inbox.get(index);
            inv.setItem(slot, mailItem(viewer, entry, index));
            if (!entry.anonymous()) {
                dev.chatalyst.gui.PlayerHeads.completeLater(viewer, inv, slot, senderId(entry));
            }
            slots.put(slot, new Selection(entry, index));
        }
        if (inbox.isEmpty()) {
            inv.setItem(22, basic(Material.CHEST,
                    Lang.raw(viewer, folder == MailFolder.SPAM ? "mail.gui-empty-spam" : "mail.gui-empty-inbox"),
                    List.of(Lang.raw(viewer, folder == MailFolder.SPAM
                            ? "mail.gui-empty-spam-lore" : "mail.gui-empty-inbox-lore"))));
        }
        if (safePage > 0) {
            inv.setItem(PREV_SLOT, MenuNavigation.previous(viewer, safePage + 1, pages));
        }
        inv.setItem(REFRESH_SLOT, MenuNavigation.refresh(viewer));
        inv.setItem(FRIENDS_SLOT, friendsItem(viewer));
        inv.setItem(COMPOSE_SLOT, basic(Material.WRITABLE_BOOK, Lang.raw(viewer, "mail.gui-compose"),
                List.of(Lang.raw(viewer, "mail.gui-compose-lore1"), Lang.raw(viewer, "mail.gui-compose-lore2"))));
        inv.setItem(INFO_SLOT, basic(Material.PAPER,
                Lang.raw(viewer, folder == MailFolder.SPAM ? "mail.gui-spam" : "mail.gui-inbox"),
                List.of(Lang.raw(viewer, "mail.gui-total", "count", inbox.size()),
                        Lang.raw(viewer, "mail.gui-unread", "count", unread(viewer)),
                        Lang.raw(viewer, "mail.gui-page-position", "page", safePage + 1,
                                "pages", pages),
                        Lang.raw(viewer, folder == MailFolder.SPAM ? "mail.gui-info-spam" : "mail.gui-info-inbox"))));
        inv.setItem(FOLDER_SLOT, folderItem(viewer));
        inv.setItem(SETTINGS_SLOT, settingsItem(viewer));
        inv.setItem(CLEAR_SLOT, basic(Material.BARRIER,
                Lang.raw(viewer, folder == MailFolder.SPAM ? "mail.gui-clear-spam" : "mail.gui-clear-inbox"),
                List.of(Lang.raw(viewer, "mail.gui-clear-lore1"), Lang.raw(viewer, "mail.gui-clear-lore2"))));
        if (safePage + 1 < pages) {
            inv.setItem(NEXT_SLOT, MenuNavigation.next(viewer, safePage + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        List<MailManager.MailEntry> inbox = entries(player);
        int pages = pages(inbox.size());
        int safePage = Math.min(page, pages - 1);
        if (slot == PREV_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, Math.max(0, safePage - 1), folder));
            return;
        }
        if (slot == NEXT_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, Math.min(pages - 1, safePage + 1), folder));
            return;
        }
        if (slot == REFRESH_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, safePage, folder));
            return;
        }
        if (slot == FRIENDS_SLOT) {
            Menu.open(player, new MailFriendsMenu(plugin, mail, compose));
            return;
        }
        if (slot == COMPOSE_SLOT) {
            Menu.open(player, new MailRecipientMenu(plugin, mail, compose, 0));
            return;
        }
        if (slot == CLEAR_SLOT) {
            boolean cleared = folder == MailFolder.SPAM
                    ? mail.clearSpam(player.getUniqueId()) : mail.clear(player.getUniqueId());
            player.sendMessage(Lang.get(player, cleared ? "mail.cleared" : "mail.storage-error"));
            Menu.open(player, new MailMenu(plugin, mail, compose, 0, folder));
            return;
        }
        if (slot == FOLDER_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, 0,
                    folder == MailFolder.SPAM ? MailFolder.INBOX : MailFolder.SPAM));
            return;
        }
        if (slot == SETTINGS_SLOT) {
            Menu.open(player, new MailSettingsMenu(plugin, mail, compose));
            return;
        }
        Selection selected = slots.get(slot);
        if (selected == null) return;
        int index = selected.index();
        MailManager.MailEntry entry = selected.entry();
        if (click.isShiftClick()) {
            boolean deleted = delete(player.getUniqueId(), entry);
            player.sendMessage(Lang.get(player,
                    deleted ? "mail.deleted" : "mail.storage-error"));
            Menu.open(player, new MailMenu(plugin, mail, compose, safePage, folder));
            return;
        }
        if (click.isRightClick()) {
            Menu.open(player, new MailActionMenu(plugin, mail, compose,
                    entry, index, safePage, folder));
            return;
        }
        if (!markRead(player.getUniqueId(), entry)) {
            player.sendMessage(Lang.get(player, "mail.storage-error"));
            return;
        }
        // Se abre como un libro escrito (con botones para responder y borrar).
        MailBook.read(player, entry, index, folder);
    }

    static boolean startReply(Player player, MailComposeManager compose, MailManager.MailEntry entry) {
        UUID targetId = senderId(entry);
        if (targetId == null) {
            player.sendMessage(Lang.get(player, "mail.reply-unknown", "player", MailManager.shownFrom(entry)));
            return false;
        }
        MailBook.beginCompose(player, compose, MailComposeManager.Mode.REPLY, targetId,
                MM.strip(MailManager.shownFrom(entry)));
        return true;
    }

    private void startReply(Player player, MailManager.MailEntry entry) {
        startReply(player, compose, entry);
    }

    static UUID senderId(MailManager.MailEntry entry) {
        if (entry.fromId() != null && !entry.fromId().isBlank()) {
            try {
                return UUID.fromString(entry.fromId());
            } catch (IllegalArgumentException ignored) {
                // Fall back to the legacy name lookup below.
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(entry.from());
        return cached != null ? cached.getUniqueId() : null;
    }

    static ItemStack basic(Material material, String name, List<String> loreLines) {
        return dev.chatalyst.gui.MenuItems.item(material, name, loreLines);
    }

    static List<String> messageLore(Player viewer, String message, String color) {
        String plain = MM.strip(message == null ? "" : message).trim();
        if (plain.isEmpty()) {
            return List.of(color + Lang.raw(viewer, "mail.gui-empty-message"));
        }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        int width = 36;
        for (int i = 0; i < plain.length(); i += width) {
            lines.add(color + MM.escape(plain.substring(i, Math.min(plain.length(), i + width))));
        }
        return lines;
    }

    private ItemStack mailItem(Player viewer, MailManager.MailEntry entry, int index) {
        String state = entry.read() ? "<#C0B3D9>" : "<#E0AAFF><bold>";
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add("<#7B68A0>#" + (index + 1) + " · " + DATE.format(Instant.ofEpochMilli(entry.time())));
        lore.add(Lang.raw(viewer, "mail.gui-from", "from",
                MailManager.shownFrom(entry)));
        lore.add("");
        lore.addAll(messageLore(viewer, entry.message(), "<#C0B3D9>"));
        lore.add("");
        lore.add(Lang.raw(viewer, entry.read() ? "mail.gui-read" : "mail.gui-unread-tag"));
        lore.add(Lang.raw(viewer, "mail.gui-open-mail"));
        lore.add(Lang.raw(viewer, "mail.gui-actions"));
        lore.add(Lang.raw(viewer, "mail.gui-shift-delete"));
        String title = state + Lang.raw(viewer,
                folder == MailFolder.SPAM ? "mail.gui-spam-from" : "mail.gui-mail-from",
                "from", MailManager.shownFrom(entry));
        // Cabeza con la SKIN real del remitente, SOLO si ha entrado a este server (asi no
        // pedimos a Mojang la skin de alguien que nunca jugo aqui). Si no, papel/libro.
        UUID sender = senderId(entry);
        OfflinePlayer op = sender != null ? Bukkit.getOfflinePlayer(sender) : null;
        boolean known = !entry.anonymous() && op != null && (op.isOnline() || op.hasPlayedBefore());
        ItemStack item = new ItemStack(known ? Material.PLAYER_HEAD
                : (entry.read() ? Material.WRITTEN_BOOK : Material.PAPER));
        java.util.List<String> loreLines = lore;
        item.editMeta(meta -> {
            meta.displayName(MM.full(title).decoration(TextDecoration.ITALIC, false));
            meta.lore(loreLines.stream()
                    .map(l -> MM.full(l).decoration(TextDecoration.ITALIC, false)).toList());
            if (known && meta instanceof SkullMeta skull) {
                dev.chatalyst.gui.PlayerHeads.apply(skull, sender);
            }
        });
        return item;
    }

    private List<MailManager.MailEntry> entries(Player viewer) {
        return folder == MailFolder.SPAM ? mail.spam(viewer.getUniqueId()) : mail.get(viewer.getUniqueId());
    }

    private int unread(Player viewer) {
        return folder == MailFolder.SPAM
                ? mail.unreadSpamCount(viewer.getUniqueId())
                : mail.unreadCount(viewer.getUniqueId());
    }

    private boolean markRead(UUID owner, MailManager.MailEntry entry) {
        return folder == MailFolder.SPAM
                ? mail.markSpamRead(owner, entry) : mail.markRead(owner, entry);
    }

    private boolean delete(UUID owner, MailManager.MailEntry entry) {
        return folder == MailFolder.SPAM
                ? mail.deleteSpam(owner, entry) : mail.delete(owner, entry);
    }

    private ItemStack friendsItem(Player viewer) {
        int requests = mail.requests(viewer.getUniqueId()).size();
        return basic(Material.PLAYER_HEAD, Lang.raw(viewer, "mail.gui-friends"),
                List.of(Lang.raw(viewer, "mail.gui-friends-count", "count", mail.friends(viewer.getUniqueId()).size()),
                        Lang.raw(viewer, "mail.gui-requests-count", "count", requests),
                        Lang.raw(viewer, requests > 0 ? "mail.gui-pending" : "mail.gui-manage")));
    }

    private ItemStack folderItem(Player viewer) {
        if (folder == MailFolder.SPAM) {
            return basic(Material.CHEST, Lang.raw(viewer, "mail.gui-main-inbox"),
                    List.of(Lang.raw(viewer, "mail.gui-unread", "count", mail.unreadCount(viewer.getUniqueId())),
                            Lang.raw(viewer, "mail.gui-open")));
        }
        return basic(Material.HOPPER, Lang.raw(viewer, "mail.gui-spam"),
                List.of(Lang.raw(viewer, "mail.gui-unread", "count", mail.unreadSpamCount(viewer.getUniqueId())),
                        Lang.raw(viewer, "mail.gui-open")));
    }

    private ItemStack settingsItem(Player viewer) {
        boolean disabled = mail.isReceivingDisabled(viewer.getUniqueId());
        return basic(Material.COMPARATOR, Lang.raw(viewer, "mail.gui-settings"),
                List.of(Lang.raw(viewer, disabled ? "mail.gui-receiving-off" : "mail.gui-receiving-on"),
                        Lang.raw(viewer, "mail.gui-configure")));
    }

    private int pages(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }
}
