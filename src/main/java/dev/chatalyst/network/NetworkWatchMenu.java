package dev.chatalyst.network;

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
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** GUI for selecting the server streams shown live in a staff member's chat. */
public final class NetworkWatchMenu implements Menu {

    private static final int[] SERVER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    // Fila de navegacion simetrica: back y refresh en las esquinas (45 · 53), las flechas
    // prev/next flanqueando el centro (47 · 51). Antes back quedaba solo con la esquina
    // derecha vacia.
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 47;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_REFRESH = 53;

    private final ChatalystPlugin plugin;
    private final NetworkMonitor monitor;
    private int page;
    private List<NetworkMonitor.ServerView> displayed = List.of();

    public NetworkWatchMenu(ChatalystPlugin plugin, NetworkMonitor monitor) {
        this.plugin = plugin;
        this.monitor = monitor;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("network-watch.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "network-watch.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        List<NetworkMonitor.ServerView> servers = monitor.servers(viewer);
        int pages = Math.max(1, (servers.size() + SERVER_SLOTS.length - 1) / SERVER_SLOTS.length);
        page = Math.min(page, pages - 1);
        int start = page * SERVER_SLOTS.length;
        displayed = List.copyOf(servers.subList(start,
                Math.min(start + SERVER_SLOTS.length, servers.size())));
        for (int i = 0; i < displayed.size(); i++) {
            NetworkMonitor.ServerView server = displayed.get(i);
            boolean watching = monitor.watches().isWatching(viewer, server.serverId());
            Material material = watching ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
            String name = "<#B56CFF><bold>" + MM.escape(server.serverId());
            String stateKey = watching ? "network-watch.enabled" : "network-watch.disabled";
            String state = Lang.raw(viewer, stateKey);
            String players = Lang.raw(viewer, "network-watch.players", "count", server.players());
            String toggle = Lang.raw(viewer, "network-watch.toggle");
            inv.setItem(SERVER_SLOTS[i], item(material, name, List.of(state, players, toggle)));
        }
        String usage = Lang.raw(viewer, "network-watch.usage",
                "count", monitor.watches().count(viewer), "max", monitor.watches().maxSubscriptions());
        inv.setItem(4, item(Material.SPYGLASS,
                Lang.raw(viewer, "network-watch.summary"), List.of(usage)));
        inv.setItem(SLOT_BACK, MenuNavigation.home(viewer));
        if (page > 0) inv.setItem(SLOT_PREVIOUS,
                MenuNavigation.previous(viewer, page + 1, pages));
        inv.setItem(SLOT_PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inv.setItem(SLOT_REFRESH, MenuNavigation.refresh(viewer));
        if (page + 1 < pages) inv.setItem(SLOT_NEXT,
                MenuNavigation.next(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        for (int i = 0; i < displayed.size(); i++) {
            if (SERVER_SLOTS[i] != slot) continue;
            var result = monitor.watches().toggle(player, displayed.get(i).serverId());
            player.sendMessage(Lang.get(player, "network-watch.result-" + result.name().toLowerCase()));
            Menu.open(player, this);
            return;
        }
        switch (slot) {
            case SLOT_BACK -> Menu.open(player, new AdminMenu(plugin));
            case SLOT_PREVIOUS -> {
                if (page > 0) page--;
                Menu.open(player, this);
            }
            case SLOT_REFRESH -> {
                monitor.requestRefresh();
                Menu.open(player, this);
            }
            case SLOT_NEXT -> {
                int total = monitor.servers(player).size() + SERVER_SLOTS.length - 1;
                int pages = Math.max(1, total / SERVER_SLOTS.length);
                if (page + 1 < pages) page++;
                Menu.open(player, this);
            }
            default -> {
            }
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return MenuItems.item(material, name, lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }
}
