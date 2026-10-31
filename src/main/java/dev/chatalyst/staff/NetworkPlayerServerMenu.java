package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.AdminMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** First step of the network player browser: choose a permitted backend. */
public final class NetworkPlayerServerMenu implements Menu {

    private static final int[] SERVER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private final ChatalystPlugin plugin;
    private final NetworkPlayerDirectory directory;
    private int page;
    private List<NetworkPlayerDirectory.ServerEntry> shown = List.of();
    private boolean hasPrevious;
    private boolean hasNext;

    public NetworkPlayerServerMenu(ChatalystPlugin plugin,
            NetworkPlayerDirectory directory, int page) {
        this.plugin = plugin;
        this.directory = directory;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("network-players.servers-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "network-players.servers-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<NetworkPlayerDirectory.ServerEntry> servers = directory.servers(viewer);
        int pages = Math.max(1, (servers.size() + SERVER_SLOTS.length - 1) / SERVER_SLOTS.length);
        page = Math.min(page, pages - 1);
        hasPrevious = page > 0;
        hasNext = page + 1 < pages;
        int start = page * SERVER_SLOTS.length;
        shown = List.copyOf(servers.subList(start,
                Math.min(start + SERVER_SLOTS.length, servers.size())));
        for (int index = 0; index < shown.size(); index++) {
            NetworkPlayerDirectory.ServerEntry server = shown.get(index);
            Material icon = server.players() > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
            inventory.setItem(SERVER_SLOTS[index], MenuItems.item(icon,
                "<#B56CFF><bold>" + MM.escape(server.id()), List.of(
                Lang.raw(viewer, "network-players.server-count", "count", server.players()),
                Lang.raw(viewer, "network-players.open-server"))));
        }
        if (servers.isEmpty()) inventory.setItem(22, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "network-players.no-servers"), List.of()));
        // Navegacion simetrica: back/refresh en esquinas (45 · 53), prev/next flancos (47 · 51).
        inventory.setItem(45, MenuNavigation.home(viewer));
        if (hasPrevious) inventory.setItem(47,
                MenuNavigation.previous(viewer, page + 1, pages));
        inventory.setItem(49, MenuNavigation.page(viewer, page + 1, pages));
        if (hasNext) inventory.setItem(51,
                MenuNavigation.next(viewer, page + 1, pages));
        inventory.setItem(53, MenuNavigation.refresh(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.admin.players.directory")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        for (int index = 0; index < shown.size(); index++) {
            if (SERVER_SLOTS[index] != slot) continue;
            String server = shown.get(index).id();
            Menu.open(player, new NetworkServerPlayersMenu(plugin, directory, server, 0));
            return;
        }
        if (slot == 45) Menu.open(player, new AdminMenu(plugin));
        if (slot == 47 && hasPrevious) Menu.open(player,
                new NetworkPlayerServerMenu(plugin, directory, page - 1));
        if (slot == 53) Menu.open(player,
                new NetworkPlayerServerMenu(plugin, directory, page));
        if (slot == 51 && hasNext) Menu.open(player,
                new NetworkPlayerServerMenu(plugin, directory, page + 1));
    }
}
