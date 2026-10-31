package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sorteos que se lanzan solos ({@code Giveaway.Schedules} en giveaway.yml):
 *
 * <pre>
 *   Schedules:
 *     noche-finde:
 *       Time: "21:00"            # hora del servidor (o de Giveaway.Time_Zone)
 *       Days: [SAT, SUN]         # vacio = todos los dias
 *       Prize: diamantes         # id de Giveaway_Prizes o de un premio personalizado
 *       Winners: 2               # opcional
 *       Warmup_Seconds: 60       # opcional
 *       Amount: "15000-35000"    # solo premios de dinero
 *       Min_Players: 5           # no se lanza con menos gente conectada
 * </pre>
 *
 * <p>Cada uno se dispara como mucho una vez por dia y hora, aunque el servidor reinicie dentro
 * de ese minuto (se recuerda en memoria el ultimo disparo; un reinicio a las 21:00:30 podria
 * relanzarlo, y por eso solo se considera el minuto exacto). Solo lo configura el servidor que
 * deba lanzarlo: Lobby y Survival tienen cada uno su giveaway.yml.
 */
final class GiveawaySchedule {

    private record Entry(String id, LocalTime time, Set<DayOfWeek> days, String prize,
                         int winners, int warmup, String amount, int minPlayers) {
    }

    private final ChatalystPlugin plugin;
    private final GiveawayManager manager;
    private final Set<String> fired = new HashSet<>();
    private volatile List<Entry> entries = List.of();
    private volatile ZoneId zone = ZoneId.systemDefault();
    private ScheduledTask task;

    GiveawaySchedule(ChatalystPlugin plugin, GiveawayManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    synchronized void start() {
        stop();
        load();
        if (entries.isEmpty()) return;
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, t -> tick(),
                5, 20, TimeUnit.SECONDS);
        plugin.getLogger().info("Giveaway schedules: " + entries.size() + " loaded");
    }

    synchronized void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void load() {
        var config = plugin.getConfig();
        String zoneName = config.getString("Giveaway.Time_Zone", "");
        try {
            zone = zoneName == null || zoneName.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zoneName);
        } catch (RuntimeException bad) {
            plugin.getLogger().warning("Giveaway.Time_Zone '" + zoneName + "' is not valid; using "
                    + ZoneId.systemDefault());
            zone = ZoneId.systemDefault();
        }
        ConfigurationSection section = config.getConfigurationSection("Giveaway.Schedules");
        if (section == null) {
            entries = List.of();
            return;
        }
        List<Entry> out = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null || !s.getBoolean("Enabled", true)) continue;
            LocalTime time = time(s.get("Time"));
            if (time == null) {
                plugin.getLogger().warning("Giveaway schedule '" + id + "': Time must be HH:mm"
                        + " in quotes (\"21:00\")");
                continue;
            }
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String raw : s.getStringList("Days")) {
                DayOfWeek day = day(raw);
                if (day == null) {
                    plugin.getLogger().warning("Giveaway schedule '" + id + "': unknown day " + raw);
                } else {
                    days.add(day);
                }
            }
            String prize = s.getString("Prize");
            if (prize == null || prize.isBlank()) {
                plugin.getLogger().warning("Giveaway schedule '" + id + "' has no Prize");
                continue;
            }
            out.add(new Entry(id, time.withSecond(0).withNano(0), days, prize,
                    s.getInt("Winners", 0), s.getInt("Warmup_Seconds", -1),
                    s.getString("Amount"), Math.max(0, s.getInt("Min_Players", 0))));
        }
        entries = List.copyOf(out);
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("H:mm[:ss]");

    /**
     * La hora tal como llega del YAML. "21:00" y "9:05" valen los dos (antes "9:05" se
     * rechazaba por faltarle el cero). Y sin comillas YAML 1.1 lee {@code 21:00} como el numero
     * sexagesimal 1260 — minutos desde medianoche —, que tambien se entiende en vez de dejar
     * el sorteo programado sin cargar por un detalle que nadie ve en el fichero.
     */
    static LocalTime time(Object raw) {
        if (raw instanceof Number number) {
            long minutes = number.longValue();
            return minutes < 0 || minutes >= 24 * 60 ? null
                    : LocalTime.of((int) (minutes / 60), (int) (minutes % 60));
        }
        if (raw == null) return null;
        try {
            return LocalTime.parse(String.valueOf(raw).trim(), TIME).withSecond(0).withNano(0);
        } catch (DateTimeParseException bad) {
            return null;
        }
    }

    /**
     * Apunta un disparo y dice si es el primero de ese minuto. Solo se recuerdan los de HOY:
     * los de dias pasados no pueden repetirse, y antes la lista se vaciaba entera al llegar a
     * 500 — borrando tambien el disparo recien apuntado, que se repetia 20 segundos despues.
     */
    static boolean markFired(Set<String> fired, String entryId, LocalDate today, LocalTime minute) {
        String day = "@" + today + "T";
        fired.removeIf(key -> !key.contains(day));
        return fired.add(entryId + day + minute);
    }

    /** Acepta MON..SUN, nombres completos en ingles y en espanol (LUN, LUNES, MIE...). */
    static DayOfWeek day(String raw) {
        if (raw == null) return null;
        String s = java.text.Normalizer.normalize(raw.trim().toUpperCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        Map<String, DayOfWeek> es = Map.of("LUN", DayOfWeek.MONDAY, "MAR", DayOfWeek.TUESDAY,
                "MIE", DayOfWeek.WEDNESDAY, "JUE", DayOfWeek.THURSDAY, "VIE", DayOfWeek.FRIDAY,
                "SAB", DayOfWeek.SATURDAY, "DOM", DayOfWeek.SUNDAY);
        if (s.length() >= 3 && es.containsKey(s.substring(0, 3))) return es.get(s.substring(0, 3));
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().startsWith(s) && s.length() >= 3) return d;
        }
        return null;
    }

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalTime minute = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate today = now.toLocalDate();
        for (Entry entry : entries) {
            if (!entry.time().equals(minute)) continue;
            if (!entry.days().isEmpty() && !entry.days().contains(today.getDayOfWeek())) continue;
            synchronized (fired) {
                if (!markFired(fired, entry.id(), today, minute)) continue;
            }
            try {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> fire(entry));
            } catch (RuntimeException disabling) {
                return; // el plugin se esta apagando: no hay sorteo que lanzar
            }
        }
    }

    private void fire(Entry entry) {
        if (plugin.getServer().getOnlinePlayers().size() < entry.minPlayers()) {
            plugin.getLogger().info("Giveaway schedule '" + entry.id() + "' skipped: fewer than "
                    + entry.minPlayers() + " players online");
            return;
        }
        GiveawayPrize prize = manager.prize(entry.prize()).orElse(null);
        if (prize == null) {
            plugin.getLogger().warning("Giveaway schedule '" + entry.id() + "': prize '"
                    + entry.prize() + "' does not exist");
            return;
        }
        if (entry.amount() != null && prize.kind() == GiveawayPrize.Kind.MONEY) {
            GiveawayPrize.MoneyBand band = GiveawayAmountPrompt.parse(entry.amount());
            if (band != null) prize = prize.withMoney(band);
        }
        String problem = manager.start(plugin.getServer().getConsoleSender(), prize,
                entry.warmup(), entry.winners());
        if (problem != null) {
            plugin.getLogger().info("Giveaway schedule '" + entry.id() + "' not started: " + problem);
        }
    }
}
