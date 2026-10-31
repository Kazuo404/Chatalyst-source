package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ranking global derivado de desbloqueos persistentes, sin almacenar contadores duplicados. */
public final class TagRankingMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final int collectionsPage;
    private final boolean returnAdmin;
    private final int adminReturnPage;
    private int page;

    public TagRankingMenu(ChatalystPlugin plugin, TagManager tags, int page,
                          int collectionsPage, boolean returnAdmin,
                          int adminReturnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.page = Math.max(0, page);
        this.collectionsPage = Math.max(0, collectionsPage);
        this.returnAdmin = returnAdmin;
        this.adminReturnPage = Math.max(0, adminReturnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.ranking-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.ranking-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<UUID> online = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId).toList();
        List<TagManager.TagRank> ranking = tags.ranking(online);
        int pages = Math.max(1, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(ranking.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            inventory.setItem(layout[index - start],
                    rankItem(viewer, ranking.get(index), index + 1));
        }
        if (ranking.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_DYE,
                    Lang.raw(viewer, "tag.ranking-empty"), List.of()));
        }
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.tag")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            back(player);
        } else if (slot == PREVIOUS && page > 0) {
            open(player, page - 1);
        } else if (slot == NEXT) {
            List<UUID> online = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId).toList();
            int pages = Math.max(1,
                    (tags.ranking(online).size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1);
        }
    }

    private ItemStack rankItem(Player viewer, TagManager.TagRank rank, int position) {
        OfflinePlayer profile = Bukkit.getOfflinePlayer(rank.playerId());
        String realName = profile.getName();
        if (realName == null || realName.isBlank()) {
            realName = rank.playerId().toString().substring(0, 8);
        }
        // El nombre que se ve en el juego (apodo o alias de /anonymous), ya escapado. El de
        // la cuenta delataba a los anonimos a cualquiera con /tag: bastaba cruzar los puntos.
        String name = dev.chatalyst.nick.NickManager.safeName(
                rank.playerId().toString(), realName);
        int percent = rank.totalTitles() == 0 ? 100
                : Math.min(100, rank.earnedTitles() * 100 / rank.totalTitles());
        List<String> lore = new ArrayList<>();
        lore.add("<#FFD166>" + Lang.raw(viewer, "tag.ranking-points",
                "points", rank.points()));
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "tag.ranking-achievements",
                "earned", rank.earnedTitles(), "total", rank.totalTitles(),
                "percent", percent));
        lore.add("<#B56CFF>" + Lang.raw(viewer, "tag.ranking-collections",
                "collections", rank.completedCollections()));
        if (!MM.strip(name).equals(realName)
                && dev.chatalyst.staff.StaffAccess.has(viewer, "chatalyst.command.realname")) {
            lore.add(Lang.raw(viewer, "players.menu.realname", "player", MM.escape(realName)));
        }
        ItemStack item = MenuItems.item(Material.PLAYER_HEAD,
                rankColor(position) + "#" + position + " <#FBF2FF>" + name, lore);
        item.editMeta(SkullMeta.class,
                meta -> PlayerHeads.apply(meta, rank.playerId()));
        return item;
    }

    private static String rankColor(int position) {
        return switch (position) {
            case 1 -> "<#FFD166>";
            case 2 -> "<#C0C0C0>";
            case 3 -> "<#CD7F32>";
            default -> "<#7B68A0>";
        };
    }

    private void open(Player player, int nextPage) {
        Menu.open(player, new TagRankingMenu(plugin, tags, nextPage,
                collectionsPage, returnAdmin, adminReturnPage));
    }

    private void back(Player player) {
        Menu.open(player, new TagCollectionsMenu(plugin, tags, collectionsPage,
                returnAdmin, adminReturnPage));
    }
}
