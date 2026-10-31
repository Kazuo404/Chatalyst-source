package dev.chatalyst.messages;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Pure rank/group selection rules for Fancy Join. */
final class FancyJoinMatcher {

    private FancyJoinMatcher() {
    }

    static String pickTier(ConfigurationSection tiers, Predicate<String> hasPermission,
            String primaryGroup) {
        if (tiers == null) return null;
        for (String key : tiers.getKeys(false)) {
            ConfigurationSection tier = tiers.getConfigurationSection(key);
            if (matchesEnabledTier(key, tier, hasPermission, primaryGroup)) return key;
        }
        return null;
    }

    private static boolean matchesEnabledTier(String key, ConfigurationSection tier,
            Predicate<String> hasPermission, String primaryGroup) {
        if (tier == null || !tier.getBoolean("Enabled", true)) return false;
        String permission = tier.getString("Permission", "");
        boolean permissionMatch = permission != null && !permission.isBlank()
                && hasPermission.test(permission.trim());
        return permissionMatch || matchesGroup(key, tier, primaryGroup);
    }

    static boolean shouldAutoDetect(ConfigurationSection auto, ConfigurationSection tiers,
            String primaryGroup, boolean luckPermsAvailable) {
        if (!isUsableAutoGroup(auto, primaryGroup, luckPermsAvailable)) return false;
        String group = primaryGroup.trim();
        if (isExcluded(auto, group)) return false;
        return !hasExplicitTier(tiers, group);
    }

    private static boolean isUsableAutoGroup(ConfigurationSection auto, String primaryGroup,
            boolean luckPermsAvailable) {
        return luckPermsAvailable && auto != null && auto.getBoolean("Enabled", true)
                && primaryGroup != null && !primaryGroup.isBlank();
    }

    private static boolean isExcluded(ConfigurationSection auto, String group) {
        return auto.getStringList("Excluded_Groups").stream()
                .anyMatch(excluded -> excluded.trim().equalsIgnoreCase(group));
    }

    private static boolean hasExplicitTier(ConfigurationSection tiers, String group) {
        if (tiers == null) return false;
        for (String key : tiers.getKeys(false)) {
            ConfigurationSection tier = tiers.getConfigurationSection(key);
            if (tier != null && matchesGroup(key, tier, group)) return true;
        }
        return false;
    }

    private static boolean matchesGroup(String tierKey, ConfigurationSection tier,
            String primaryGroup) {
        if (primaryGroup == null || primaryGroup.isBlank()) return false;
        String wanted = primaryGroup.trim().toLowerCase(Locale.ROOT);
        boolean explicit = tier.contains("Groups") || tier.contains("Group");
        List<String> groups = tier.getStringList("Groups");
        if (groups.stream().anyMatch(group -> group.trim().equalsIgnoreCase(wanted))) return true;
        String single = tier.getString("Group", "");
        if (single != null && single.trim().equalsIgnoreCase(wanted)) return true;
        return !explicit && tierKey.equalsIgnoreCase(wanted);
    }
}
