package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paginated player profiles for exactly one selected proxy backend. */
public final class NetworkServerPlayersMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private final ChatalystPlugin plugin;
    private final NetworkPlayerDirectory directory;
    private final String server;
    private int page;
    private final Map<Integer, NetworkPlayerDirectory.Entry> slots = new HashMap<>();
    private boolean hasPrevious;
    private boolean hasNext;

    public NetworkServerPlayersMenu(ChatalystPlugin plugin, NetworkPlayerDirectory directory,
            String server, int page) {
        this.plugin = plugin;
        this.directory = directory;
        this.server = server;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("network-players.players-title", "server", MM.escape(server)));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "network-players.players-title", "server", MM.escape(server));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        slots.clear();
        List<NetworkPlayerDirectory.Entry> players = directory.players(viewer, server);
        int maxPage = players.isEmpty() ? 0 : (players.size() - 1) / PAGE_SIZE;
        int pages = maxPage + 1;
        page = Math.min(page, maxPage);
        hasPrevious = page > 0;
        hasNext = page < maxPage;
        int start = page * PAGE_SIZE;
        for (int index = start; index < Math.min(players.size(), start + PAGE_SIZE); index++) {
            int slot = index - start;
            NetworkPlayerDirectory.Entry entry = players.get(index);
            slots.put(slot, entry);
            inventory.setItem(slot, head(viewer, entry));
            PlayerHeads.completeLater(viewer, inventory, slot, entry.id());
        }
        if (players.isEmpty()) inventory.setItem(22, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "network-players.none", "server", MM.escape(server)), List.of()));
        // Navegacion simetrica: back/refresh en esquinas (45 · 53), prev/next flancos (47 · 51).
        inventory.setItem(45, MenuNavigation.back(viewer));
        if (hasPrevious) inventory.setItem(47,
                MenuNavigation.previous(viewer, page + 1, pages));
        inventory.setItem(49, MenuNavigation.page(viewer, page + 1, pages));
        if (hasNext) inventory.setItem(51,
                MenuNavigation.next(viewer, page + 1, pages));
        inventory.setItem(53, MenuNavigation.refresh(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!directory.canViewServer(player, server)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        NetworkPlayerDirectory.Entry target = slots.get(slot);
        if (target != null) {
            player.closeInventory();
            directory.teleport(player, target);
            return;
        }
        if (slot == 45) Menu.open(player, new NetworkPlayerServerMenu(plugin, directory, 0));
        if (slot == 47 && hasPrevious) Menu.open(player,
                new NetworkServerPlayersMenu(plugin, directory, server, page - 1));
        if (slot == 53) Menu.open(player,
                new NetworkServerPlayersMenu(plugin, directory, server, page));
        if (slot == 51 && hasNext) Menu.open(player,
                new NetworkServerPlayersMenu(plugin, directory, server, page + 1));
    }

    private ItemStack head(Player viewer, NetworkPlayerDirectory.Entry entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            PlayerHeads.apply(meta, entry.id());
            meta.displayName(MM.full("<#FBF2FF><bold>" + MM.escape(entry.name()))
                .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.get(viewer, "network-players.rank", "rank", MM.escape(entry.rank())));
            lore.add(Lang.get(viewer, "network-players.money",
                "money", MM.escape(entry.balance())));
            lore.add(Lang.get(viewer, "network-players.server", "server", MM.escape(entry.server())));
            lore.add(Lang.get(viewer, "network-players.world", "world", MM.escape(entry.world())));
            lore.add(Lang.get(viewer, "network-players.gamemode",
                "gamemode", MM.escape(entry.gameMode())));
            lore.add(Lang.get(viewer, "network-players.ping", "ping", entry.ping()));
            if (entry.afk()) lore.add(Lang.get(viewer, "network-players.afk"));
            lore.add(Component.empty());
            lore.add(Lang.get(viewer, "network-players.click-teleport"));
            meta.lore(lore.stream().map(line ->
                line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }
}
