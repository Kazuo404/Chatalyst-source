package dev.chatalyst.itemwatch;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Lo que dice {@code Item_Watch} en moderation.yml, ya validado. */
public record ItemWatchSettings(boolean enabled, int scanIntervalSeconds,
                                long alertCooldownMillis, String alertPermission,
                                String bypassPermission, boolean ignoreCreative,
                                boolean notifyDiscord, boolean scanEnderChest,
                                boolean scanVaults, boolean scanContainers,
                                boolean detectOverstack, List<WatchRule> rules) {

    public static final String ROOT = "Item_Watch";

    public static ItemWatchSettings disabled() {
        return new ItemWatchSettings(false, 60, 600_000L, "chatalyst.itemwatch.alerts",
                "chatalyst.itemwatch.bypass", true, true, true, true, true, true, List.of());
    }

    /** Lee la seccion; las reglas mal escritas se saltan y se cuentan por {@code warn}. */
    public static ItemWatchSettings load(ConfigurationSection section, Consumer<String> warn) {
        if (section == null) return disabled();
        List<WatchRule> rules = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<?, ?> raw : section.getMapList("Rules")) {
            WatchRule rule = rule(raw, warn);
            if (rule == null) continue;
            if (!ids.add(rule.id())) {
                warn.accept("duplicate rule id '" + rule.id() + "'; only the first one counts");
                continue;
            }
            rules.add(rule);
        }
        return new ItemWatchSettings(
                section.getBoolean("Enabled", false),
                Math.max(15, Math.min(3_600, section.getInt("Scan_Interval_Seconds", 60))),
                Math.max(0L, section.getLong("Alert_Cooldown_Minutes", 10L)) * 60_000L,
                section.getString("Alert_Permission", "chatalyst.itemwatch.alerts"),
                section.getString("Bypass_Permission", "chatalyst.itemwatch.bypass"),
                section.getBoolean("Ignore_Creative", true),
                section.getBoolean("Notify_Discord", true),
                section.getBoolean("Scan_Ender_Chest", true),
                section.getBoolean("Scan_Vaults", true),
                section.getBoolean("Scan_Containers", true),
                section.getBoolean("Detect_Overstack", true),
                List.copyOf(rules));
    }

    static WatchRule rule(Map<?, ?> raw, Consumer<String> warn) {
        String id = text(raw.get("Id")).toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            warn.accept("a rule has no valid Id (letters, digits, _ or -); skipped");
            return null;
        }
        Map<Material, Integer> weights = new LinkedHashMap<>();
        Object items = raw.get("Items");
        if (items instanceof List<?> list) {
            for (Object entry : list) {
                String spec = text(entry);
                if (spec.isEmpty()) continue;
                int star = spec.indexOf('*');
                String name = (star < 0 ? spec : spec.substring(0, star)).trim();
                int weight = 1;
                if (star >= 0) {
                    try {
                        weight = Integer.parseInt(spec.substring(star + 1).trim());
                    } catch (NumberFormatException bad) {
                        weight = -1;
                    }
                }
                Material material = Material.matchMaterial(name);
                if (material == null || weight < 1) {
                    warn.accept("rule '" + id + "': '" + spec + "' is not MATERIAL or"
                            + " MATERIAL*weight; ignored");
                    continue;
                }
                weights.put(material, weight);
            }
        }
        long windowMinutes = Math.max(1L, number(raw.get("Gain_Window_Minutes"), 10L));
        WatchRule rule = new WatchRule(id,
                text(raw.get("Name")).isEmpty() ? id : text(raw.get("Name")),
                weights, strings(raw.get("Name_Contains")), strings(raw.get("Lore_Contains")),
                Math.max(0L, number(raw.get("Max"), 0L)),
                Math.max(0L, number(raw.get("Gain"), 0L)),
                windowMinutes * 60_000L);
        if (!rule.matchesSomething()) {
            warn.accept("rule '" + id + "' has no Items, Name_Contains or Lore_Contains;"
                    + " it would count everything, so it was skipped");
            return null;
        }
        if (rule.max() == 0L && rule.gain() == 0L) {
            warn.accept("rule '" + id + "' has neither Max nor Gain; it can never alert");
        }
        return rule;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(ItemWatchSettings::text).filter(s -> !s.isEmpty()).toList();
        }
        String single = text(value);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? fallback : Long.parseLong(text(value));
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
