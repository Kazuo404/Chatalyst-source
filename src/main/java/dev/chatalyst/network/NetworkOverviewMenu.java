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

import java.time.Duration;
import java.util.List;

/** Vista general de servidores y actividad reciente dentro del panel administrativo. */
public final class NetworkOverviewMenu implements Menu {

    private int[] shownSlots = new int[0];
    // Hasta 21 servidores por pagina en una rejilla enmarcada (filas 1-3); la ultima fila centrada.
    private static final int[] SERVER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    // Fila de navegacion simetrica: back/announce en las esquinas (45 · 53), prev/next
    // flanqueando el refresh central (47 · 49 · 51).
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 47;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_ANNOUNCE = 53;

    private final ChatalystPlugin plugin;
    private final NetworkMonitor monitor;
    private int page;
    private List<NetworkMonitor.ServerView> displayedServers = List.of();

    public NetworkOverviewMenu(ChatalystPlugin plugin, NetworkMonitor monitor) {
        this.plugin = plugin;
        this.monitor = monitor;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("network-gui.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "network-gui.title");
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
        displayedServers = List.copyOf(servers.subList(start,
                Math.min(start + SERVER_SLOTS.length, servers.size())));
        shownSlots = dev.chatalyst.gui.MenuLayout.insetSlots(displayedServers.size(), 1);
        for (int i = 0; i < displayedServers.size(); i++) {
            NetworkMonitor.ServerView server = displayedServers.get(i);
            Material icon = server.players() > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
            String activity = server.lastActivity() == 0
                    ? Lang.raw(viewer, "network-gui.no-activity")
                    : Lang.raw(viewer, "network-gui.activity-ago",
                    "time", age(server.lastActivity()));
            inv.setItem(shownSlots[i], item(icon,
                    "<#B56CFF><bold>" + MM.escape(server.serverId()),
                    List.of(
                            Lang.raw(viewer, "network-gui.players", "count", server.players()),
                            Lang.raw(viewer, "network-gui.buffered", "count", server.bufferedMessages()),
                            activity,
                            Lang.raw(viewer, "network-gui.open-server"),
                            Lang.raw(viewer, monitor.watches().isWatching(viewer, server.serverId())
                                    ? "network-gui.live-on" : "network-gui.live-off"))));
        }
        inv.setItem(SLOT_BACK, MenuNavigation.home(viewer));
        inv.setItem(SLOT_PREVIOUS,
                MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(SLOT_REFRESH, MenuNavigation.refresh(viewer));
        inv.setItem(SLOT_ANNOUNCE, item(Material.BEACON,
                Lang.raw(viewer, "network-gui.global-announce"),
                List.of(Lang.raw(viewer, "network-gui.global-announce-hint"))));
        inv.setItem(SLOT_NEXT,
                MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.admin.network.monitor")) return;
        for (int i = 0; i < displayedServers.size(); i++) {
            if (i < shownSlots.length && shownSlots[i] == slot) {
                if (click.isRightClick() || click.isShiftClick()) {
                    var result = monitor.watches().toggle(player, displayedServers.get(i).serverId());
                    player.sendMessage(Lang.get(player,
                            "network-watch.result-" + result.name().toLowerCase(java.util.Locale.ROOT)));
                    Menu.open(player, this);
                    return;
                }
                Menu.open(player, new NetworkChatMenu(
                        plugin, monitor, displayedServers.get(i).serverId()));
                return;
            }
        }
        switch (slot) {
            case SLOT_BACK -> Menu.open(player, new AdminMenu(plugin));
            case SLOT_PREVIOUS -> {
                if (page > 0) page--;
                Menu.open(player, this);
            }
            case SLOT_REFRESH -> {
                monitor.requestRefresh();
                player.getScheduler().runDelayed(plugin,
                        task -> Menu.open(player, new NetworkOverviewMenu(plugin, monitor)),
                        null, 10L);
            }
            case SLOT_NEXT -> {
                int pages = Math.max(1, (monitor.servers(player).size() + SERVER_SLOTS.length - 1)
                        / SERVER_SLOTS.length);
                if (page + 1 < pages) page++;
                Menu.open(player, this);
            }
            case SLOT_ANNOUNCE -> openGlobalAnnouncement(player);
            default -> {
            }
        }
    }

    private void openGlobalAnnouncement(Player player) {
        if (!player.hasPermission("chatalyst.admin.network.announce")
                || plugin.getAnnounceDraftManager() == null) return;
        plugin.getAnnounceDraftManager().draft(player.getUniqueId()).network = true;
        Menu.open(player, new dev.chatalyst.announce.AnnounceMenu(plugin.getAnnounceDraftManager()));
    }

    private static String age(long timestamp) {
        long seconds = Math.max(0, Duration.ofMillis(System.currentTimeMillis() - timestamp).toSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m";
        return seconds / 3600 + "h";
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return MenuItems.item(material, name, lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }
}
