package dev.chatalyst.staff.autoclick;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

/** Lo que dice {@code Autoclick} en moderation.yml, ya validado y acotado. */
public record AutoclickSettings(boolean enabled, Source source, long maxGapMillis,
                                long alertCooldownMillis, boolean notifyDiscord,
                                boolean recordHistory, long historyCooldownMillis,
                                boolean ignoreCreative, int liveSeconds, long recentMillis,
                                double minTps, int maxPing,
                                Thresholds java,
                                boolean analyzeBedrock, int bedrockMaxPing, Thresholds bedrock,
                                int punishAfter, long punishWindowMillis,
                                List<String> punishCommands) {

    public static final String ROOT = "Autoclick";

    /** De donde salen los clics. AUTO = paquetes si PacketEvents esta instalado. */
    public enum Source { AUTO, PACKETS, EVENTS }

    public static AutoclickSettings disabled() {
        Thresholds t = Thresholds.defaults();
        return new AutoclickSettings(false, Source.AUTO, 2_500L, 60_000L, true, true,
                30 * 60_000L, true, 15, 60 * 60_000L, 18.0, 250,
                t, false, 350, bedrockDefaults(t), 3, 30 * 60_000L, List.of());
    }

    private static Thresholds bedrockDefaults(Thresholds java) {
        // Bedrock (tactil, mando) toca mas rapido y llega cuantizado a ticks via Geyser.
        return new Thresholds(java.minClicks(), java.minDurationMillis(), java.window(),
                java.maxDeviation(), java.maxStdMillis(), 0, 20.0, java.speedSeconds(),
                java.longMillis(), java.longMaxDeviation(), java.longMaxStdMillis());
    }

    public static AutoclickSettings load(ConfigurationSection section) {
        if (section == null) return disabled();
        Thresholds java = thresholds(section, Thresholds.defaults());
        ConfigurationSection bedrockSection = section.getConfigurationSection("Bedrock");
        Thresholds bedrock = bedrockSection == null ? bedrockDefaults(java)
                : thresholds(bedrockSection, bedrockDefaults(java));
        // Bedrock siempre es de baja resolucion: el recuento de distintos no significa nada.
        bedrock = new Thresholds(bedrock.minClicks(), bedrock.minDurationMillis(),
                bedrock.window(), bedrock.maxDeviation(), bedrock.maxStdMillis(), 0,
                bedrock.maxCps(), bedrock.speedSeconds(), bedrock.longMillis(),
                bedrock.longMaxDeviation(), bedrock.longMaxStdMillis());
        int maxPing = clamp(section.getInt("Lag.Max_Ping_Millis", 250), 0, 10_000);
        return new AutoclickSettings(
                section.getBoolean("Enabled", false),
                source(section.getString("Source", "auto")),
                clamp(section.getLong("Max_Gap_Millis", 2_500L), 200L, 30_000L),
                clamp(section.getLong("Alert_Cooldown_Seconds", 60L), 5L, 86_400L) * 1_000L,
                section.getBoolean("Notify_Discord", true),
                section.getBoolean("Record_History", true),
                clamp(section.getLong("History_Cooldown_Minutes", 30L), 0L, 10_080L) * 60_000L,
                section.getBoolean("Ignore_Creative", true),
                clamp(section.getInt("Live_Seconds", 15), 3, 120),
                clamp(section.getLong("Recent_Minutes", 60L), 1L, 1_440L) * 60_000L,
                Math.max(0.0, Math.min(20.0, section.getDouble("Lag.Min_Tps", 18.0))),
                maxPing,
                java,
                bedrockSection != null && bedrockSection.getBoolean("Analyze", false),
                bedrockSection == null ? Math.max(maxPing, 350)
                        : clamp(bedrockSection.getInt("Max_Ping_Millis", 350), 0, 10_000),
                bedrock,
                clamp(section.getInt("Punishment.After_Alerts", 3), 1, 100),
                clamp(section.getLong("Punishment.Window_Minutes", 30L), 1L, 10_080L) * 60_000L,
                section.getStringList("Punishment.Commands").stream()
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(s -> s.startsWith("/") ? s.substring(1) : s).toList());
    }

    /** Lee Regularity / Speed / Long_Session de una seccion; lo que falte sale de {@code base}. */
    static Thresholds thresholds(ConfigurationSection s, Thresholds base) {
        int minClicks = clamp(s.getInt("Regularity.Min_Clicks", base.minClicks()), 10, 1_000);
        // La ventana nunca por debajo de los clics minimos: no se puede juzgar lo que no cabe.
        int window = clamp(s.getInt("Regularity.Window", base.window()), minClicks - 1, 1_000);
        return new Thresholds(
                minClicks,
                clamp(s.getLong("Regularity.Min_Duration_Seconds",
                        base.minDurationMillis() / 1_000L), 1L, 3_600L) * 1_000L,
                window,
                clamp(s.getDouble("Regularity.Max_Deviation", base.maxDeviation()), 0.0, 1.0),
                clamp(s.getDouble("Regularity.Max_Std_Millis", base.maxStdMillis()), 0.0, 1_000.0),
                clamp(s.getInt("Regularity.Max_Distinct_Intervals", base.maxDistinct()), 0, 1_000),
                clamp(s.getDouble("Speed.Max_Cps", base.maxCps()), 0.0, 100.0),
                clamp(s.getInt("Speed.Seconds", base.speedSeconds()), 0, 60),
                clamp(s.getLong("Long_Session.Minutes", base.longMillis() / 60_000L), 0L, 1_440L)
                        * 60_000L,
                clamp(s.getDouble("Long_Session.Max_Deviation", base.longMaxDeviation()), 0.0, 1.0),
                clamp(s.getDouble("Long_Session.Max_Std_Millis", base.longMaxStdMillis()),
                        0.0, 1_000.0));
    }

    public Thresholds thresholdsFor(boolean bedrockPlayer) {
        return bedrockPlayer ? bedrock : java;
    }

    public int maxPingFor(boolean bedrockPlayer) {
        return bedrockPlayer ? bedrockMaxPing : maxPing;
    }

    /** Capacidad de ventana que sirve a Java y a Bedrock a la vez. */
    public int capacity() {
        return Math.max(java.requiredCapacity(), bedrock.requiredCapacity());
    }

    private static Source source(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "packets", "packet", "packetevents" -> Source.PACKETS;
            case "events", "event", "bukkit" -> Source.EVENTS;
            default -> Source.AUTO;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
