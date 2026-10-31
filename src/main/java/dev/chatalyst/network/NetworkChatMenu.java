package dev.chatalyst.network;

import dev.chatalyst.ChatalystPlugin;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Timeline paginado del chat observado en un backend concreto. */
public final class NetworkChatMenu implements Menu {

    private static final int FIRST_ENTRY_SLOT = 9;
    private static final int ENTRIES_PER_PAGE = 36;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_ANNOUNCE = 53;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ChatalystPlugin plugin;
    private final NetworkMonitor monitor;
    private final String serverId;
    private int page;
    private List<NetworkMonitor.ChatEntry> displayedEntries = List.of();

    public NetworkChatMenu(ChatalystPlugin plugin, NetworkMonitor monitor, String serverId) {
        this.plugin = plugin;
        this.monitor = monitor;
        this.serverId = serverId;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("network-chat.title", "server", serverId));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "network-chat.title", "server", serverId);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        List<NetworkMonitor.ChatEntry> entries = monitor.entries(viewer, serverId);
        int pages = Math.max(1, (entries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        page = Math.min(page, pages - 1);
        displayedEntries = entries;
        inv.setItem(4, item(Material.ENDER_EYE, "<#B56CFF><bold>" + MM.escape(serverId),
                List.of(Lang.raw(viewer, "network-chat.summary",
                        "count", entries.size(), "page", page + 1, "pages", pages))));

        int start = page * ENTRIES_PER_PAGE;
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                Math.max(0, Math.min(entries.size() - start, ENTRIES_PER_PAGE)), 1);
        for (int i = 0; i < ENTRIES_PER_PAGE && start + i < entries.size(); i++) {
            NetworkMonitor.ChatEntry entry = entries.get(start + i);
            List<String> lore = new ArrayList<>();
            lore.add("<#7B68A0>" + TIME.format(Instant.ofEpochMilli(entry.timestamp()))
                    + " <#C0B3D9>· <#B56CFF>#" + MM.escape(entry.channel()));
            lore.addAll(wrap(entry.message(), 42).stream().map(line -> "<#FBF2FF>" + MM.escape(line)).toList());
            lore.add(Lang.raw(viewer, "network-chat.detail-hint"));
            inv.setItem(layout[i], item(Material.PAPER,
                    "<#FBF2FF>" + MM.escape(entry.player()), lore));
        }

        inv.setItem(SLOT_BACK, MenuNavigation.back(viewer));
        inv.setItem(SLOT_REFRESH, MenuNavigation.refresh(viewer));
        inv.setItem(SLOT_PREVIOUS,
                MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(SLOT_NEXT,
                MenuNavigation.nextOrLast(viewer, page + 1, pages));
        inv.setItem(SLOT_ANNOUNCE, item(Material.BEACON,
                Lang.raw(viewer, "network-gui.global-announce"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!monitor.watches().canViewServer(player, serverId)) return;
        if (slot >= FIRST_ENTRY_SLOT && slot < FIRST_ENTRY_SLOT + ENTRIES_PER_PAGE) {
            int local = dev.chatalyst.gui.MenuLayout.indexAt(slot, Math.max(0,
                    Math.min(displayedEntries.size() - page * ENTRIES_PER_PAGE, ENTRIES_PER_PAGE)), 1);
            int index = page * ENTRIES_PER_PAGE + local;
            if (local >= 0 && index < displayedEntries.size()) {
                NetworkMonitor.ChatEntry entry = displayedEntries.get(index);
                player.sendMessage(MM.full("<#7B68A0>[<#B56CFF>" + MM.escape(entry.serverId())
                        + "<#7B68A0>] <#C0B3D9>" + TIME.format(Instant.ofEpochMilli(entry.timestamp()))
                        + " <#7B68A0>#" + MM.escape(entry.channel()) + " <#FBF2FF>"
                        + MM.escape(entry.player()) + "<#C0B3D9>: <#FBF2FF>" + MM.escape(entry.message())));
            }
            return;
        }
        switch (slot) {
            case SLOT_BACK -> Menu.open(player, new NetworkOverviewMenu(plugin, monitor));
            case SLOT_PREVIOUS -> {
                if (page > 0) page--;
                Menu.open(player, this);
            }
            case SLOT_REFRESH -> {
                monitor.requestRefresh();
                Menu.open(player, this);
            }
            case SLOT_NEXT -> {
                int pages = Math.max(1, (monitor.entries(player, serverId).size() + ENTRIES_PER_PAGE - 1)
                        / ENTRIES_PER_PAGE);
                if (page + 1 < pages) page++;
                Menu.open(player, this);
            }
            case SLOT_ANNOUNCE -> {
                if (player.hasPermission("chatalyst.admin.network.announce")
                        && plugin.getAnnounceDraftManager() != null) {
                    plugin.getAnnounceDraftManager().draft(player.getUniqueId()).network = true;
                    Menu.open(player, new dev.chatalyst.announce.AnnounceMenu(
                            plugin.getAnnounceDraftManager()));
                }
            }
            default -> {
            }
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty() && lines.size() < 4) {
            if (remaining.length() <= width) {
                lines.add(remaining);
                remaining = "";
                break;
            }
            int cut = remaining.lastIndexOf(' ', width);
            if (cut < width / 2) cut = width;
            lines.add(remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty() && lines.size() == 4) {
            int last = lines.size() - 1;
            String value = lines.get(last);
            lines.set(last, value.substring(0, Math.min(value.length(), width - 3)) + "...");
        }
        return lines;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return MenuItems.item(material, name, lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }
}
