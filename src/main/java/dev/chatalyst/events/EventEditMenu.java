package dev.chatalyst.events;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Editor de un evento: fecha/hora con botones +/-, nombre/descripcion por chat, recordatorio. */
public final class EventEditMenu implements Menu {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final int[] REMIND_STEPS = {0, 5, 10, 15, 30, 60, 120};

    private static final int HEAD = 4;
    private static final int ADD_MONTH = 10, ADD_DAY = 12, ADD_HOUR = 14, ADD_MIN = 16;
    private static final int VAL_MONTH = 19, VAL_DAY = 21, VAL_HOUR = 23, VAL_MIN = 25;
    private static final int SUB_MONTH = 28, SUB_DAY = 30, SUB_HOUR = 32, SUB_MIN = 34;
    private static final int NAME = 38, DESC = 40, REMIND = 42;
    private static final int BACK = 45, DELETE = 49, SAVE = 53;

    private final ChatalystPlugin plugin;
    private final EventsManager events;
    private final EventPrompt prompt;
    private final String eventId;

    public EventEditMenu(ChatalystPlugin plugin, EventsManager events, EventPrompt prompt, String eventId) {
        this.plugin = plugin;
        this.events = events;
        this.prompt = prompt;
        this.eventId = eventId;
    }

    @Override
    public Component title() {
        return Lang.get("events-gui.edit-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "events-gui.edit-title");
    }

    @Override
    public int size() {
        return 54;
    }

    private static LocalDateTime dateOf(ScheduledEvent e) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(e.whenMillis()), ZoneId.systemDefault());
    }

    private static long millisOf(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        ScheduledEvent e = events.byId(eventId);
        if (e == null) {
            inv.setItem(HEAD, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "events-gui.gone"), List.of()));
            inv.setItem(BACK, back(viewer));
            return;
        }
        LocalDateTime dt = dateOf(e);
        boolean es = Lang.isSpanish(viewer);
        inv.setItem(HEAD, MenuItems.item(Material.CLOCK,
                "<gradient:#240046:#9D4EDD:#E0AAFF><bold>" + dev.chatalyst.util.MM.escape(e.displayName(es)) + "</bold></gradient>",
                List.of("<#C0B3D9>" + STAMP.format(dt),
                        // Con fecha pasada, formatRemaining soltaria "expirado" fijo en
                        // español; se usa la misma clave localizada que la lista de eventos.
                        "<#C0B3D9>" + (e.whenMillis() > System.currentTimeMillis()
                                ? Lang.raw(viewer, "events-gui.countdown", "in",
                                        dev.chatalyst.util.TimeUtil.formatRemaining(e.whenMillis()))
                                : Lang.raw(viewer, "events-gui.passed")),
                        e.description() == null || e.description().isBlank() ? "<#7B68A0>-"
                                : "<#7B68A0>" + dev.chatalyst.util.MM.escape(e.description()))));

        // Columnas +/- para mes, dia, hora y minuto (minuto en pasos de 5).
        inv.setItem(ADD_MONTH, adj(viewer, Material.LIME_DYE, "events-gui.plus-month"));
        inv.setItem(ADD_DAY, adj(viewer, Material.LIME_DYE, "events-gui.plus-day"));
        inv.setItem(ADD_HOUR, adj(viewer, Material.LIME_DYE, "events-gui.plus-hour"));
        inv.setItem(ADD_MIN, adj(viewer, Material.LIME_DYE, "events-gui.plus-min"));
        inv.setItem(VAL_MONTH, value(viewer, Material.PAPER, "events-gui.month", dt.getMonthValue()));
        inv.setItem(VAL_DAY, value(viewer, Material.PAPER, "events-gui.day", dt.getDayOfMonth()));
        inv.setItem(VAL_HOUR, value(viewer, Material.PAPER, "events-gui.hour", dt.getHour()));
        inv.setItem(VAL_MIN, value(viewer, Material.PAPER, "events-gui.min", dt.getMinute()));
        inv.setItem(SUB_MONTH, adj(viewer, Material.RED_DYE, "events-gui.minus-month"));
        inv.setItem(SUB_DAY, adj(viewer, Material.RED_DYE, "events-gui.minus-day"));
        inv.setItem(SUB_HOUR, adj(viewer, Material.RED_DYE, "events-gui.minus-hour"));
        inv.setItem(SUB_MIN, adj(viewer, Material.RED_DYE, "events-gui.minus-min"));

        inv.setItem(NAME, MenuItems.item(Material.NAME_TAG, Lang.raw(viewer, "events-gui.name"),
                List.of("<#C0B3D9>" + dev.chatalyst.util.MM.escape(e.name()),
                        "<#E0AAFF>" + Lang.raw(viewer, "events-gui.name-edit"))));
        inv.setItem(DESC, MenuItems.item(Material.WRITABLE_BOOK, Lang.raw(viewer, "events-gui.desc"),
                List.of("<#E0AAFF>" + Lang.raw(viewer, "events-gui.desc-edit"))));
        inv.setItem(REMIND, MenuItems.item(Material.BELL, Lang.raw(viewer, "events-gui.remind"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, e.remindMinutes() <= 0 ? "events-gui.remind-off"
                                : "events-gui.remind-value", "min", e.remindMinutes()),
                        "<#E0AAFF>" + Lang.raw(viewer, "admin-gui.click-toggle"))));

        inv.setItem(BACK, back(viewer));
        inv.setItem(DELETE, MenuItems.item(Material.LAVA_BUCKET, Lang.raw(viewer, "events-gui.delete"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "events-gui.delete-lore"))));
        inv.setItem(SAVE, MenuItems.item(Material.LIME_CONCRETE, Lang.raw(viewer, "events-gui.save"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "events-gui.save-lore"))));
    }

    private static org.bukkit.inventory.ItemStack back(Player viewer) {
        return dev.chatalyst.gui.MenuNavigation.back(viewer);
    }

    private static org.bukkit.inventory.ItemStack adj(Player viewer, Material mat, String key) {
        return MenuItems.item(mat, Lang.raw(viewer, key), List.of());
    }

    private static org.bukkit.inventory.ItemStack value(Player viewer, Material mat, String key, int n) {
        return MenuItems.item(mat, "<#FBF2FF>" + Lang.raw(viewer, key)
                + " <#7B68A0>» <#B56CFF>" + n, List.of());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.command.events.admin")) return;
        ScheduledEvent e = events.byId(eventId);
        if (e == null) {
            if (slot == BACK) Menu.open(player, new EventsMenu(plugin, events, prompt));
            return;
        }
        switch (slot) {
            case ADD_MONTH -> shift(player, e, dateOf(e).plusMonths(1));
            case ADD_DAY -> shift(player, e, dateOf(e).plusDays(1));
            case ADD_HOUR -> shift(player, e, dateOf(e).plusHours(1));
            case ADD_MIN -> shift(player, e, dateOf(e).plusMinutes(5));
            case SUB_MONTH -> shift(player, e, dateOf(e).minusMonths(1));
            case SUB_DAY -> shift(player, e, dateOf(e).minusDays(1));
            case SUB_HOUR -> shift(player, e, dateOf(e).minusHours(1));
            case SUB_MIN -> shift(player, e, dateOf(e).minusMinutes(5));
            case NAME -> prompt.begin(player, eventId, false);
            case DESC -> prompt.begin(player, eventId, true);
            case REMIND -> {
                events.update(e.withRemindMinutes(nextRemind(e.remindMinutes())));
                Menu.open(player, this);
            }
            case DELETE -> {
                events.remove(eventId);
                player.sendMessage(Lang.get(player, "events-gui.deleted", "event", e.name()));
                Menu.open(player, new EventsMenu(plugin, events, prompt));
            }
            case SAVE, BACK -> Menu.open(player, new EventsMenu(plugin, events, prompt));
            default -> {
            }
        }
    }

    private void shift(Player player, ScheduledEvent e, LocalDateTime dt) {
        // No permitir dejarlo en el pasado (confunde y nunca recordaria).
        long millis = Math.max(System.currentTimeMillis(), millisOf(dt));
        events.update(e.withWhen(millis));
        Menu.open(player, this);
    }

    static int nextRemind(int current) {
        for (int step : REMIND_STEPS) {
            if (step > current) return step;
        }
        return REMIND_STEPS[0];
    }
}
