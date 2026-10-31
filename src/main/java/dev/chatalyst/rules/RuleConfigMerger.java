package dev.chatalyst.rules;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.function.Consumer;

/** Builds one effective rule by overlaying reusable groups and then the rule itself. */
final class RuleConfigMerger {

    private RuleConfigMerger() {
    }

    static ConfigurationSection merge(ConfigurationSection rule, ConfigurationSection groups,
            Consumer<String> warning) {
        YamlConfiguration merged = new YamlConfiguration();
        if (groups != null) {
            for (String name : rule.getStringList("Use_Groups")) {
                applyGroup(groups, name, merged, warning);
            }
        }
        overlay(rule, merged);
        return merged;
    }

    private static void applyGroup(ConfigurationSection groups, String name,
            YamlConfiguration merged, Consumer<String> warning) {
        ConfigurationSection group = groups.getConfigurationSection(name);
        if (group == null) {
            warning.accept("Unknown rule group '" + name + "'.");
            return;
        }
        overlay(group, merged);
    }

    private static void overlay(ConfigurationSection source, YamlConfiguration target) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key) || key.equals("Use_Groups")) continue;
            target.set(key, source.get(key));
        }
    }
}
