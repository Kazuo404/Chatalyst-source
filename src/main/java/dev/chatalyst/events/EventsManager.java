package dev.chatalyst.events;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.TimeUtil;
import dev.chatalyst.util.YamlFiles;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Eventos programados del servidor (events.yml): los administra el staff desde una GUI y los
 * jugadores los ven con {@code /events} y reciben un aviso cuando se acercan. Un tick periodico
 * (async, como el Announcer) manda el recordatorio "empieza pronto" una sola vez por evento y
 * poda los que ya pasaron. El trabajo por-jugador se despacha a los hilos de region (Folia-safe).
 */
public final class EventsManager {

    private static final long PURGE_AFTER_MS = 3_600_000L; // 1 h despues de la hora

    private final ChatalystPlugin plugin;
    private final File file;
    // Snapshot inmutable ordenado por fecha; se reemplaza entero (swap volatile).
    private volatile List<ScheduledEvent> events = List.of();
    private volatile boolean reminderEnabled = true;
    private volatile int joinNoticeHours = 24;
    private volatile boolean announce = true;
    private ScheduledTask task;

    public EventsManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "events.yml");
    }

    // Sincronizado con tick()/save(): sin esto, un /cht reload a mitad de tick dejaria que
    // save() escribiera el snapshot VIEJO encima del events.yml recien cargado.
    public synchronized void load() {
        if (!file.exists()) plugin.saveResource("events.yml", false);
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception broken) {
            // Carga estricta: un events.yml roto NO se trata como vacio — save() reescribe
            // el archivo completo y borraria todos los eventos programados.
            plugin.getLogger().severe("events.yml has a syntax error; keeping the events"
                    + " already in memory. Fix the file and reload: " + broken.getMessage());
            return;
        }
        reminderEnabled = yaml.getBoolean("Settings.Reminder_Enabled", true);
        joinNoticeHours = Math.max(0, Math.min(8_760,
                yaml.getInt("Settings.Join_Notice_Hours", 24)));
        announce = !"none".equalsIgnoreCase(yaml.getString("Settings.Announce_To", "everyone"));
        List<ScheduledEvent> list = new ArrayList<>();
        ConfigurationSection sec = yaml.getConfigurationSection("Events");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection e = sec.getConfigurationSection(id);
                if (e == null) continue;
                long when = e.getLong("When", 0L);
                if (when <= 0) continue;
                list.add(new ScheduledEvent(id, e.getString("Name", id), e.getString("Name_es", ""),
                        e.getString("Description", ""), when,
                        Math.max(0, e.getInt("Remind_Minutes", 30)), e.getBoolean("Reminded", false)));
            }
        }
        list.sort(Comparator.comparingLong(ScheduledEvent::whenMillis));
        events = List.copyOf(list);
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("Settings.Reminder_Enabled", reminderEnabled);
        yaml.set("Settings.Join_Notice_Hours", joinNoticeHours);
        yaml.set("Settings.Announce_To", announce ? "everyone" : "none");
        for (ScheduledEvent e : events) {
            String base = "Events." + e.id() + ".";
            yaml.set(base + "Name", e.name());
            if (e.nameEs() != null && !e.nameEs().isBlank()) yaml.set(base + "Name_es", e.nameEs());
            if (e.description() != null && !e.description().isBlank()) {
                yaml.set(base + "Description", e.description());
            }
            yaml.set(base + "When", e.whenMillis());
            yaml.set(base + "Remind_Minutes", e.remindMinutes());
            yaml.set(base + "Reminded", e.reminded());
        }
        try {
            YamlFiles.saveAtomic(yaml, file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save events.yml: " + ex.getMessage());
        }
    }

    public void start() {
        stop();
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, t -> tick(),
                20L, 20L, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<ScheduledEvent> next = new ArrayList<>(events.size());
        for (ScheduledEvent e : events) {
            if (now > TimeUtil.deadline(e.whenMillis(), PURGE_AFTER_MS)) { // ya paso: se poda
                changed = true;
                continue;
            }
            if (reminderEnabled && !e.reminded() && e.remindMinutes() > 0) {
                long remindAt = e.whenMillis() - e.remindMinutes() * 60_000L;
                if (now >= remindAt && now < e.whenMillis()) {
                    broadcastReminder(e);
                    e = e.withReminded(true);
                    changed = true;
                }
            }
            next.add(e);
        }
        if (changed) {
            events = List.copyOf(next);
            save();
        }
    }

    private void broadcastReminder(ScheduledEvent e) {
        if (!announce) return;
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(Lang.get(viewer,
                "events.reminder", "event", e.displayName(Lang.isSpanish(viewer)),
                "in", TimeUtil.formatRemaining(e.whenMillis()))));
        // tick() corre en el AsyncScheduler; la consola forma parte de la API de Bukkit y
        // se notifica desde el global scheduler igual que el resto de trabajo no-async.
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(
                Lang.get("events.reminder", "event", e.displayName(false),
                        "in", TimeUtil.formatRemaining(e.whenMillis()))));
    }

    /** Aviso al entrar de los eventos que caen dentro de la ventana configurada. */
    public void notifyOnJoin(Player player) {
        if (joinNoticeHours <= 0) return;
        long now = System.currentTimeMillis();
        long within = now + joinNoticeHours * 3_600_000L;
        for (ScheduledEvent e : events) {
            if (e.whenMillis() > now && e.whenMillis() <= within) {
                player.sendMessage(Lang.get(player, "events.join-notice",
                        "event", e.displayName(Lang.isSpanish(player)),
                        "in", TimeUtil.formatRemaining(e.whenMillis())));
            }
        }
    }

    public List<ScheduledEvent> upcoming() {
        long now = System.currentTimeMillis();
        return events.stream().filter(e -> e.upcoming(now)).toList();
    }

    public List<ScheduledEvent> all() {
        return events;
    }

    public ScheduledEvent byId(String id) {
        for (ScheduledEvent e : events) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }

    public synchronized ScheduledEvent create() {
        // El timestamp solo no basta: dos creaciones en el mismo milisegundo (dos staff en
        // regiones distintas) chocarian y update/remove por id afectaria al equivocado.
        String id = "evt-" + Long.toHexString(System.currentTimeMillis());
        for (int n = 2; byId(id) != null; n++) {
            id = "evt-" + Long.toHexString(System.currentTimeMillis()) + "-" + n;
        }
        ScheduledEvent e = new ScheduledEvent(id, "New event", "Evento nuevo", "",
                System.currentTimeMillis() + 86_400_000L, 30, false);
        List<ScheduledEvent> next = new ArrayList<>(events);
        next.add(e);
        next.sort(Comparator.comparingLong(ScheduledEvent::whenMillis));
        events = List.copyOf(next);
        save();
        return e;
    }

    public synchronized void update(ScheduledEvent updated) {
        List<ScheduledEvent> next = new ArrayList<>();
        for (ScheduledEvent e : events) {
            next.add(e.id().equals(updated.id()) ? updated : e);
        }
        next.sort(Comparator.comparingLong(ScheduledEvent::whenMillis));
        events = List.copyOf(next);
        save();
    }

    public synchronized void remove(String id) {
        List<ScheduledEvent> next = new ArrayList<>();
        for (ScheduledEvent e : events) {
            if (!e.id().equals(id)) next.add(e);
        }
        events = List.copyOf(next);
        save();
    }
}
