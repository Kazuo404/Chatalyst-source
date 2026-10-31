package dev.chatalyst.discord;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * How much of a punishment is left, expressed as something a Discord role can say.
 *
 * <p>A Discord role is shared by everyone wearing it: its name is ONE string for all holders,
 * so it cannot read "30 days left" for one member and "12 days" for another. Naming a role per
 * exact remaining day would need one role per day — and a guild is capped at 250 roles, with
 * every banned member needing to be moved between roles every single day.
 *
 * <p>Tiers are the way out: a handful of roles per state ("1+ month", "1+ week", "days",
 * "hours"), and a member moves down them as their time runs out. The member list shows roughly
 * how long is left, which is what makes it useful, and the exact second still goes in the DM
 * where it can be personal.
 */
public record PunishmentTier(String name, int color, long minRemainingMillis) {

    /** Discord's own ceiling on how many roles a guild can hold. */
    public static final int GUILD_ROLE_LIMIT = 250;

    /**
     * Tiers for one punishment, widest first, so the first match is always the right one.
     *
     * @return empty when nothing is configured; the caller then assigns no role at all rather
     *         than inventing one.
     */
    public static List<PunishmentTier> read(ConfigurationSection section) {
        if (section == null) return List.of();
        List<PunishmentTier> tiers = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(key);
            if (tier == null) continue;
            String name = tier.getString("Name", "").trim();
            if (name.isEmpty()) continue;
            tiers.add(new PunishmentTier(name,
                    color(tier.getString("Color", "#FF4D6D")),
                    Math.max(0L, tier.getLong("Min_Remaining_Seconds", 0L)) * 1_000L));
        }
        tiers.sort(Comparator.comparingLong(PunishmentTier::minRemainingMillis).reversed());
        return List.copyOf(tiers);
    }

    /**
     * The tier a punishment with this much time left belongs to.
     *
     * @param remainingMillis milliseconds still to serve, or a negative value for "permanent".
     * @return null when nothing fits, which is not an error: a server that only wants a role
     *         for long bans leaves the short end unconfigured on purpose.
     */
    public static PunishmentTier match(List<PunishmentTier> tiers, long remainingMillis) {
        if (tiers == null || tiers.isEmpty()) return null;
        // Permanent outlasts every threshold, so it belongs to the widest tier there is.
        long remaining = remainingMillis < 0 ? Long.MAX_VALUE : remainingMillis;
        for (PunishmentTier tier : tiers) {
            if (remaining >= tier.minRemainingMillis()) return tier;
        }
        return null;
    }

    /** Every role name these tiers can produce — what the bot is allowed to take away. */
    public static List<String> names(List<PunishmentTier> tiers) {
        return tiers == null ? List.of() : tiers.stream().map(PunishmentTier::name).toList();
    }

    /** {@code #RRGGBB} or a bare hex; anything unreadable falls back to the house red. */
    static int color(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9A-F]{6}")) return 0xFF4D6D;
        return Integer.parseInt(value, 16);
    }
}
