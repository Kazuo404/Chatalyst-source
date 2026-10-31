package dev.chatalyst.sign;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Immutable, validated Fancy_Signs configuration snapshot. */
record FancySignSettings(boolean enabled, String permission, Map<String, FancySignStyle> styles) {

    private static final Pattern VALID_STYLE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    FancySignSettings {
        styles = Map.copyOf(styles);
    }

    static FancySignSettings load(ConfigurationSection section) {
        if (section == null) return disabled();
        return new FancySignSettings(section.getBoolean("Enabled", true),
                permission(section, "Permission", "chatalyst.sign.colors"),
                loadStyles(section.getConfigurationSection("Styles")));
    }

    private static FancySignSettings disabled() {
        return new FancySignSettings(false, "chatalyst.sign.colors", Map.of());
    }

    private static Map<String, FancySignStyle> loadStyles(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, FancySignStyle> styles = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            ConfigurationSection style = section.getConfigurationSection(rawId);
            if (!VALID_STYLE_ID.matcher(id).matches() || style == null) continue;
            String format = style.getString("Format", "");
            if (format == null || !format.contains("{text}")) continue;
            styles.put(id, new FancySignStyle(id, format));
        }
        return styles;
    }

    private static String permission(ConfigurationSection section, String path, String fallback) {
        return clean(section.getString(path, fallback));
    }

    private static String clean(String permission) {
        return permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
    }
}
