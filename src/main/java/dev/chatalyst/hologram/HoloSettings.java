package dev.chatalyst.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ajustes de holograms.yml ({@code Holograms} y {@code Hologram_Presets}), leidos de una vez:
 * un reload cambia la instantanea entera y nunca deja medio ajuste viejo y medio nuevo.
 */
public record HoloSettings(boolean enabled, int updateTicks, int presenceTicks,
                           int activationRange, BedrockMode defaultBedrock,
                           int defaultCooldown, int maxHolograms, Map<String, String> styles,
                           Map<String, Map<String, String>> presets) {

    private static final Pattern STYLE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public HoloSettings {
        styles = Map.copyOf(styles);
        presets = Map.copyOf(presets);
    }

    public static HoloSettings load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("Holograms");
        boolean enabled = root == null || root.getBoolean("Enabled", true);
        int update = root == null ? 2 : root.getInt("Update_Interval_Ticks", 2);
        int presence = root == null ? 20 : root.getInt("Presence_Check_Ticks", 20);
        int range = root == null ? 48 : root.getInt("Activation_Range", 48);
        BedrockMode bedrock = BedrockMode.parse(root == null ? "geyser"
                : root.getString("Default_Bedrock", "geyser"));
        int cooldown = root == null ? 2 : root.getInt("Default_Click_Cooldown", 2);
        int max = root == null ? 500 : root.getInt("Max_Holograms", 500);

        // Estilos con nombre: los de los carteles de madera y, encima, los propios.
        Map<String, String> styles = new LinkedHashMap<>();
        readStyles(config.getConfigurationSection("Fancy_Signs.Styles"), styles);
        readStyles(root == null ? null : root.getConfigurationSection("Styles"), styles);

        Map<String, Map<String, String>> presets = new LinkedHashMap<>();
        ConfigurationSection presetRoot = config.getConfigurationSection("Hologram_Presets");
        if (presetRoot != null) {
            for (String rawId : presetRoot.getKeys(false)) {
                ConfigurationSection section = presetRoot.getConfigurationSection(rawId);
                String id = rawId.toLowerCase(Locale.ROOT);
                if (section == null || !STYLE_ID.matcher(id).matches()) continue;
                Map<String, String> options = new LinkedHashMap<>();
                for (String option : section.getKeys(false)) {
                    Object value = section.get(option);
                    if (value != null && !(value instanceof ConfigurationSection)) {
                        options.put(option, String.valueOf(value));
                    }
                }
                presets.put(id, Map.copyOf(options));
            }
        }
        return new HoloSettings(enabled,
                Math.max(1, Math.min(20, update)),
                Math.max(5, Math.min(200, presence)),
                Math.max(8, Math.min(256, range)),
                bedrock == null ? BedrockMode.GEYSER : bedrock,
                Math.max(0, cooldown), Math.max(1, max), styles, presets);
    }

    private static void readStyles(ConfigurationSection section, Map<String, String> into) {
        if (section == null) return;
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!STYLE_ID.matcher(id).matches()) continue;
            String format = section.isConfigurationSection(rawId)
                    ? section.getString(rawId + ".Format") : section.getString(rawId);
            if (format != null && format.contains("{text}")) into.put(id, format);
        }
    }
}
