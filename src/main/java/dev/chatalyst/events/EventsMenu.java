package dev.chatalyst.events;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Panel admin de eventos: lista los programados (clic para editar) y un boton para crear. */
public final class EventsMenu implements Menu {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int HOME = 47;
    private static final int PAGE = 49;
    private static final int CREATE = 51;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final EventsManager events;
    private final EventPrompt prompt;
    private int page;
    // Mapa slot -> id calculado al construir, para no depender del orden en el onClick.
    private final List<String> shown = new ArrayList<>();

    public EventsMenu(ChatalystPlugin plugin, EventsManager events, EventPrompt prompt) {
        this(plugin, events, prompt, 0);
    }

    private EventsMenu(ChatalystPlugin plugin, EventsManager events, EventPrompt prompt, int page) {
        this.plugin = plugin;
        this.events = events;
        this.prompt = prompt;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("events-gui.title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "events-gui.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        boolean es = Lang.isSpanish(viewer);
        long now = System.currentTimeMillis();
        List<ScheduledEvent> all = events.all();
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(all.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int i = start; i < end; i++) {
            ScheduledEvent e = all.get(i);
            shown.add(e.id());
            String when = STAMP.format(Instant.ofEpochMilli(e.whenMillis()).atZone(ZoneId.systemDefault()));
            List<String> lore = new ArrayList<>();
            lore.add("<#C0B3D9>" + when);
            lore.add(e.whenMillis() > now
                    ? "<#B56CFF>" + Lang.raw(viewer, "events-gui.countdown", "in", TimeUtil.formatRemaining(e.whenMillis()))
                    : "<#7B68A0>" + Lang.raw(viewer, "events-gui.passed"));
            if (e.description() != null && !e.description().isBlank()) {
                lore.add("<#7B68A0>" + MM.escape(e.description()));
            }
            lore.add("<#E0AAFF>" + Lang.raw(viewer, "events-gui.click-edit"));
            inv.setItem(layout[i - start], MenuItems.item(e.whenMillis() > now ? Material.CLOCK : Material.GRAY_DYE,
                    "<#FBF2FF>" + MM.escape(e.displayName(es)), lore));
        }
        if (page > 0) inv.setItem(PREVIOUS,
                MenuNavigation.previous(viewer, page + 1, pages));
        inv.setItem(HOME, MenuNavigation.home(viewer));
        inv.setItem(PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inv.setItem(CREATE, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "events-gui.create"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "events-gui.create-lore"))));
        if (page + 1 < pages) inv.setItem(NEXT,
                MenuNavigation.next(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.events.admin")) return;
        if (slot == HOME) {
            Menu.open(player, new dev.chatalyst.command.AdminMenu(plugin));
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new EventsMenu(plugin, events, prompt, page - 1));
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1, (events.all().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) Menu.open(player,
                    new EventsMenu(plugin, events, prompt, page + 1));
            return;
        }
        if (slot == CREATE) {
            ScheduledEvent created = events.create();
            Menu.open(player, new EventEditMenu(plugin, events, prompt, created.id()));
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) >= 0) {
            Menu.open(player, new EventEditMenu(plugin, events, prompt, shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0))));
        }
    }
}
