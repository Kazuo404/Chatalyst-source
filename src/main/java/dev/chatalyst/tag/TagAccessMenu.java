package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.FoliaTasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/** Administra visualmente concesiones persistentes para jugadores conectados y conocidos. */
public final class TagAccessMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 47;
    private static final int PAGE = 49;
    private static final int OFFLINE = 51;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;
    private int page;
    private final List<UUID> shown = new ArrayList<>();

    public TagAccessMenu(ChatalystPlugin plugin, TagManager tags,
                         String titleId, int returnPage) {
        this(plugin, tags, titleId, returnPage, 0);
    }

    private TagAccessMenu(ChatalystPlugin plugin, TagManager tags,
                          String titleId, int returnPage, int page) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-access-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-access-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.admin-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(
                Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (online.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(online.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            Player target = online.get(index);
            shown.add(target.getUniqueId());
            inventory.setItem(layout[index - start], playerItem(viewer, target, definition));
        }
        if (online.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_DYE,
                    Lang.raw(viewer, "tag.admin-access-no-online"),
                    List.of(Lang.raw(viewer, "tag.admin-access-offline-lore"))));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, MenuNavigation.previous(viewer, page + 1, pages));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inventory.setItem(OFFLINE, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "tag.admin-access-offline"),
                List.of(Lang.raw(viewer, "tag.admin-access-offline-lore"))));
        if (page + 1 < pages) {
            inventory.setItem(NEXT, MenuNavigation.next(viewer, page + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!allowed(player)) return;
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            if (slot == BACK) back(player);
            return;
        }
        if (slot == BACK) {
            back(player);
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            open(player, page - 1);
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1,
                    (Bukkit.getOnlinePlayers().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1);
            return;
        }
        if (slot == OFFLINE) {
            if (definition.requirements().isFree()) {
                player.sendMessage(Lang.get(player, "tag.admin-access-free"));
                return;
            }
            TagAdminPrompt prompt = plugin.getTagAdminPrompt();
            if (prompt == null) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            } else {
                prompt.begin(player, titleId, returnPage,
                        TagAdminPrompt.Field.PLAYER_ACCESS);
            }
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) < 0) return;
        if (definition.requirements().isFree()) {
            player.sendMessage(Lang.get(player, "tag.admin-access-free"));
            return;
        }
        Player target = Bukkit.getPlayer(shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)));
        if (target == null) {
            Menu.open(player, this);
            return;
        }
        boolean revoke = tags.isPersistentlyUnlocked(target.getUniqueId(), titleId);
        Set<String> completedBefore = revoke ? Set.of()
                : TagUnlockProcessor.completedCollectionIds(
                        tags, target.getUniqueId());
        dev.chatalyst.api.title.TitleMutationContext context =
                dev.chatalyst.api.title.TitleMutationContext.admin(
                        player.getUniqueId(), player.getName(),
                        revoke ? "GUI access revoke" : "GUI access grant");
        boolean persisted = revoke
                ? tags.revoke(target.getUniqueId(), titleId, context)
                : tags.grant(target.getUniqueId(), titleId, context);
        if (!persisted) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
        } else {
            player.sendMessage(Lang.get(player, revoke
                            ? "tag.admin-access-revoked" : "tag.admin-access-granted",
                    "player", target.getName(), "title", definition.displayName()));
            FoliaTasks.entity(plugin, target, online -> {
                if (revoke) TagDisplay.refresh(plugin, online);
                else TagUnlockProcessor.manualGrant(
                        plugin, tags, online, definition, completedBefore);
            });
            audit(player, revoke ? "tag title revoke" : "tag title grant",
                    target.getName() + " " + titleId);
        }
        Menu.open(player, this);
    }

    private ItemStack playerItem(Player viewer, Player target, TagDefinition definition) {
        boolean free = definition.requirements().isFree();
        boolean unlocked = tags.isUnlocked(target.getUniqueId(), definition);
        // El nombre visible: el de la cuenta, a un admin de titulos sin /realname, le
        // desenmascaraba a los anonimos.
        String shown = dev.chatalyst.staff.StaffAccess.has(viewer, "chatalyst.command.realname")
                ? MM.escape(target.getName())
                : dev.chatalyst.nick.NickManager.safeName(target);
        ItemStack item = MenuItems.item(Material.PLAYER_HEAD,
                (unlocked ? "<#3DFFA8>" : "<#FBF2FF>") + shown,
                List.of(unlocked
                                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.status-unlocked")
                                : "<#FF4D6D>" + Lang.raw(viewer, "tag.status-locked"),
                        "<#C0B3D9>" + Lang.raw(viewer, free
                                ? "tag.admin-access-free-lore"
                                : unlocked
                                ? "tag.admin-access-click-revoke"
                                : "tag.admin-access-click-grant")));
        item.editMeta(SkullMeta.class,
                meta -> PlayerHeads.apply(meta, target.getUniqueId()));
        return item;
    }

    private void open(Player player, int nextPage) {
        Menu.open(player, new TagAccessMenu(
                plugin, tags, titleId, returnPage, nextPage));
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.tag.admin")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }

    private void back(Player player) {
        Menu.open(player, new TagAdminEditMenu(plugin, tags, titleId, returnPage));
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }
}
