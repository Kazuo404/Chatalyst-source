package dev.chatalyst.tebex;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A section of the storefront.
 *
 * <p>Sections live in their own {@code Store_Sections} block rather than as a loose field on
 * each product, so the order, icon and name of the shop are edited in one place and an owner
 * can reorganise the store without touching what is for sale.
 */
public record StorefrontCategory(String id, String displayName, List<String> description,
                                 String icon, int order) {

    /** Section that collects entries with no category, or with one that does not exist. */
    public static final String DEFAULT_ID = "";

    public StorefrontCategory {
        description = List.copyOf(description);
    }

    public static boolean enabled(FileConfiguration config) {
        return config.getBoolean("Store_Sections.Enabled", false);
    }

    /** Configured sections, in display order. */
    public static List<StorefrontCategory> all(FileConfiguration config,
                                               org.bukkit.command.CommandSender viewer) {
        ConfigurationSection root =
                config.getConfigurationSection("Store_Sections.Categories");
        if (root == null) return List.of();
        List<StorefrontCategory> categories = new ArrayList<>();
        for (String rawId : root.getKeys(false)) {
            String id = rawId.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_\\-]{1,64}")) continue;
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            List<String> description = new ArrayList<>();
            for (String line : dev.chatalyst.lang.LocalizedConfig.list(
                    section, "Description", viewer)) {
                if (description.size() >= 12) break;
                description.add(bounded(line, 256));
            }
            categories.add(new StorefrontCategory(id,
                    bounded(dev.chatalyst.lang.LocalizedConfig.string(
                            section, "Display_Name", viewer, rawId), 160),
                    description,
                    icon(bounded(section.getString("Icon", "CHEST"), 128)),
                    Math.max(-10_000, Math.min(10_000, section.getInt("Order", 0)))));
        }
        categories.sort(Comparator.comparingInt(StorefrontCategory::order)
                .thenComparing(StorefrontCategory::id));
        return List.copyOf(categories);
    }

    public org.bukkit.inventory.ItemStack iconItem() {
        if (icon.indexOf(':') >= 0) {
            var custom = dev.chatalyst.hook.ItemsAdderHook.item(icon);
            if (custom != null && !custom.getType().isAir()) return custom;
        }
        Material parsed = Material.matchMaterial(icon);
        return new org.bukkit.inventory.ItemStack(
                parsed != null && parsed.isItem() && !parsed.isAir() ? parsed : Material.CHEST);
    }

    /**
     * The section an entry belongs to. An entry naming a section that does not exist lands in
     * the default one instead of vanishing: a typo in the config must never quietly remove
     * something the owner is trying to sell.
     */
    public static String resolveFor(StorefrontEntry entry,
                                    List<StorefrontCategory> categories) {
        String wanted = entry.category() == null ? "" : entry.category();
        for (StorefrontCategory category : categories) {
            if (category.id().equals(wanted)) return wanted;
        }
        return DEFAULT_ID;
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String icon(String raw) {
        String namespaced = raw.toLowerCase(Locale.ROOT);
        if (namespaced.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) return namespaced;
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}") || normalized.endsWith("AIR")) {
            return "CHEST";
        }
        return normalized;
    }
}
