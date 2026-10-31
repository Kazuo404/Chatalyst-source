package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Friend and friend-request management for mail. */
public final class MailFriendsMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int FIND_SLOT = 46;
    private static final int REQUESTS_SLOT = 47;
    private static final int BACK_SLOT = 49;
    // Parejas espejo: buscar(46)|bloqueados(52), solicitudes(47)|amigos(51).
    private static final int FRIENDS_SLOT = 51;
    private static final int BLOCKED_SLOT = 52;
    private static final int NEXT_SLOT = 53;

    private enum Section { REQUESTS, FRIENDS }

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private int page;
    private Section section;
    private final Map<Integer, MailManager.FriendRequest> requestSlots = new HashMap<>();
    private final Map<Integer, MailManager.FriendEntry> friendSlots = new HashMap<>();

    public MailFriendsMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose) {
        this(plugin, mail, compose, Section.REQUESTS, 0);
    }

    private MailFriendsMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose,
                            Section section, int page) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.section = section;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("mail.gui-friends-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-friends-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        requestSlots.clear();
        friendSlots.clear();
        List<MailManager.FriendRequest> requests = mail.requests(viewer.getUniqueId());
        List<MailManager.FriendEntry> friends = mail.friends(viewer.getUniqueId());
        // Entrada inteligente: si no hay solicitudes pendientes, abre directamente amistades.
        if (section == Section.REQUESTS && requests.isEmpty() && !friends.isEmpty()) {
            section = Section.FRIENDS;
        }
        int total = section == Section.REQUESTS ? requests.size() : friends.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, Math.min(total - start, PAGE_SIZE)), 0);
        if (section == Section.REQUESTS) {
            for (int index = start; index < Math.min(requests.size(), start + PAGE_SIZE); index++) {
                int slot = layout[index - start];
                MailManager.FriendRequest request = requests.get(index);
                inv.setItem(slot, MailMenu.basic(Material.EMERALD,
                        Lang.raw(viewer, "mail.gui-request", "from",
                                MailManager.shownSender(null, request.fromName())),
                        List.of("<#7B68A0>" + MailMenu.DATE.format(Instant.ofEpochMilli(request.time())),
                                Lang.raw(viewer, "mail.gui-accept"),
                                Lang.raw(viewer, "mail.gui-deny"))));
                requestSlots.put(slot, request);
            }
        } else {
            for (int index = start; index < Math.min(friends.size(), start + PAGE_SIZE); index++) {
                int slot = layout[index - start];
                MailManager.FriendEntry friend = friends.get(index);
                UUID friendId = uuid(friend.playerId());
                boolean anonymous = friendId != null
                        && dev.chatalyst.hook.Placeholders.isAnonymous(friendId);
                // Una amistad existente no debe funcionar como oraculo "nombre real -> alias".
                if (!anonymous) {
                    dev.chatalyst.gui.PlayerHeads.completeLater(viewer, inv, slot, friendId);
                }
                inv.setItem(slot, head(anonymous ? null : friendId,
                        "<#B56CFF>" + displayName(friend, anonymous),
                        List.of(Lang.raw(viewer, "mail.gui-mail-friend"),
                                Lang.raw(viewer, "mail.gui-write"),
                                Lang.raw(viewer, "mail.gui-remove"))));
                friendSlots.put(slot, friend);
            }
        }
        if (requests.isEmpty() && friends.isEmpty()) {
            inv.setItem(22, MailMenu.basic(Material.BOOK, Lang.raw(viewer, "mail.gui-no-friends"),
                    List.of(Lang.raw(viewer, "mail.gui-no-friends-lore"))));
        } else if (total == 0) {
            String suffix = section == Section.REQUESTS ? "requests" : "friends-section";
            inv.setItem(22, MailMenu.basic(Material.BOOK,
                    Lang.raw(viewer, "mail.gui-no-" + suffix),
                    List.of(Lang.raw(viewer, "mail.gui-no-" + suffix + "-lore"))));
        }
        inv.setItem(FIND_SLOT, MailMenu.basic(Material.COMPASS, Lang.raw(viewer, "mail.gui-find"),
                List.of(Lang.raw(viewer, "mail.gui-find-lore1"), Lang.raw(viewer, "mail.gui-find-lore2"))));
        inv.setItem(PREVIOUS_SLOT,
                MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(REQUESTS_SLOT, sectionItem(viewer, Section.REQUESTS, requests.size()));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(FRIENDS_SLOT, sectionItem(viewer, Section.FRIENDS, friends.size()));
        inv.setItem(BLOCKED_SLOT, MailMenu.basic(Material.IRON_BARS, Lang.raw(viewer, "mail.gui-blocked-senders"),
                List.of(Lang.raw(viewer, "mail.gui-blocked-count", "count",
                                mail.blocked(viewer.getUniqueId()).size()),
                        Lang.raw(viewer, "mail.gui-manage"))));
        inv.setItem(NEXT_SLOT,
                MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, 0));
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new MailFriendsMenu(plugin, mail, compose, section, page - 1));
            return;
        }
        if (slot == NEXT_SLOT) {
            int total = section == Section.REQUESTS
                    ? mail.requests(player.getUniqueId()).size()
                    : mail.friends(player.getUniqueId()).size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) {
                Menu.open(player, new MailFriendsMenu(plugin, mail, compose, section, page + 1));
            }
            return;
        }
        if (slot == REQUESTS_SLOT) {
            Menu.open(player, new MailFriendsMenu(plugin, mail, compose, Section.REQUESTS, 0));
            return;
        }
        if (slot == FRIENDS_SLOT) {
            Menu.open(player, new MailFriendsMenu(plugin, mail, compose, Section.FRIENDS, 0));
            return;
        }
        if (slot == FIND_SLOT) {
            Menu.open(player, new MailRecipientMenu(plugin, mail, compose, 0));
            return;
        }
        if (slot == BLOCKED_SLOT) {
            Menu.open(player, new MailBlockedMenu(plugin, mail, compose));
            return;
        }
        MailManager.FriendRequest request = requestSlots.get(slot);
        if (request != null) {
            if (click.isRightClick() || click.isShiftClick()) {
                MailManager.RequestActionResult result =
                        mail.denyRequestResult(player.getUniqueId(), request);
                if (result != MailManager.RequestActionResult.APPLIED) {
                    player.sendMessage(Lang.get(player, result
                            == MailManager.RequestActionResult.STORAGE_FAILED
                            ? "mail.storage-error"
                            : result == MailManager.RequestActionResult.ANONYMOUS_BLOCKED
                            ? "mail.friend-anonymous" : "mail.no-request-from",
                            "player", request.fromName()));
                    reopen(player);
                    return;
                }
                player.sendMessage(Lang.get(player, "mail.friend-request-denied", "player", request.fromName()));
            } else {
                MailManager.RequestActionResult result = mail.acceptRequestResult(
                        player.getUniqueId(), player.getName(), request);
                if (result != MailManager.RequestActionResult.APPLIED) {
                    player.sendMessage(Lang.get(player, result
                            == MailManager.RequestActionResult.STORAGE_FAILED
                            ? "mail.storage-error"
                            : result == MailManager.RequestActionResult.ANONYMOUS_BLOCKED
                            ? "mail.friend-anonymous" : "mail.no-request-from",
                            "player", request.fromName()));
                    reopen(player);
                    return;
                }
                player.sendMessage(Lang.get(player, "mail.friend-request-accepted", "player", request.fromName()));
                UUID requesterId = uuid(request.fromId());
                if (requesterId != null) {
                    Player requester = Bukkit.getPlayer(requesterId);
                    if (requester != null) {
                        String accepterName = player.getName();
                        FoliaTasks.entity(plugin, requester, current -> current.sendMessage(
                                Lang.get(current, "mail.friend-request-accepted-by",
                                        "player", accepterName)));
                    }
                }
            }
            reopen(player);
            return;
        }
        MailManager.FriendEntry friend = friendSlots.get(slot);
        if (friend == null) {
            return;
        }
        UUID friendId = uuid(friend.playerId());
        if (click.isShiftClick()) {
            if (!mail.removeFriend(player.getUniqueId(), friendId, friend.playerName())) {
                player.sendMessage(Lang.get(player, "mail.storage-error"));
                reopen(player);
                return;
            }
            player.sendMessage(Lang.get(player, "mail.friend-removed", "player",
                    MM.escape(MM.strip(displayName(friend, false)))));
            reopen(player);
            return;
        }
        if (friendId == null) {
            player.sendMessage(Lang.get(player, "mail.reply-unknown", "player",
                    MM.escape(MM.strip(displayName(friend, false)))));
            return;
        }
        boolean anonymous = dev.chatalyst.hook.Placeholders.isAnonymous(friendId);
        MailBook.beginCompose(player, compose, MailComposeManager.Mode.SEND, friendId,
                MM.strip(displayName(friend, anonymous)));
    }

    // Cabeza de jugador con su skin real, SOLO si ha entrado a este server (no pedimos a Mojang
    // la skin de quien nunca jugo aqui). Si no, queda una cabeza generica.
    private static ItemStack head(UUID id, String name, List<String> lore) {
        org.bukkit.OfflinePlayer op = id != null ? Bukkit.getOfflinePlayer(id) : null;
        boolean known = op != null && (op.isOnline() || op.hasPlayedBefore());
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> {
            meta.displayName(MM.full(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(l -> MM.full(l).decoration(TextDecoration.ITALIC, false)).toList());
            if (known && meta instanceof SkullMeta skull) {
                dev.chatalyst.gui.PlayerHeads.apply(skull, id);
            }
        });
        return item;
    }

    private ItemStack sectionItem(Player viewer, Section candidate, int count) {
        boolean active = section == candidate;
        String key = candidate == Section.REQUESTS ? "requests" : "friends";
        Material material = candidate == Section.REQUESTS ? Material.WRITABLE_BOOK : Material.PLAYER_HEAD;
        return MenuItems.item(material,
                Lang.raw(viewer, "mail.gui-section-" + key + (active ? "-active" : "")),
                List.of(Lang.raw(viewer, "mail.gui-section-count", "count", count),
                        Lang.raw(viewer, active
                                ? "mail.gui-section-current" : "mail.gui-section-open")));
    }

    private void reopen(Player player) {
        Menu.open(player, new MailFriendsMenu(plugin, mail, compose, section, page));
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String displayName(MailManager.FriendEntry friend, boolean anonymous) {
        String base = friend.playerName() != null && !friend.playerName().isBlank()
                ? friend.playerName()
                : friend.playerId();
        if (anonymous) return MM.escape(base);
        // Apodo actual del amigo si tiene uno (sigue al /nick igual que el resto del inbox).
        return MailManager.shownSender(friend.playerId(), base);
    }
}
