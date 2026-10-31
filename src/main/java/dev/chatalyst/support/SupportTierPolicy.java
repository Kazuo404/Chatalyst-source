package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable priority hierarchy used by detection, escalation and staff presence. */
public final class SupportTierPolicy {

    public record Tier(String name, Set<String> groups, String permission) {
    }

    private final ChatalystPlugin plugin;
    private volatile List<Tier> tiers = defaults();

    public SupportTierPolicy(ChatalystPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection(
                "Staff_Mentions.Priority.Tiers");
        List<Tier> loaded = new ArrayList<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                String name = normalize(key);
                Set<String> groups = normalize(section.getStringList("Groups"));
                String permission = section.getString("Permission",
                "chatalyst.support.tier." + name).trim();
                if (!name.isEmpty() && !groups.isEmpty()) {
                loaded.add(new Tier(name, groups, permission));
                }
            }
        }
        tiers = loaded.isEmpty() ? defaults() : List.copyOf(loaded);
    }

    public List<String> names() {
        return tiers.stream().map(Tier::name).toList();
    }

    public String first() {
        return tiers.getFirst().name();
    }

    public String requestedTier(String mentionedGroup) {
        return first();
    }

    public String next(String current) {
        List<Tier> snapshot = tiers;
        for (int i = 0; i < snapshot.size() - 1; i++) {
            if (snapshot.get(i).name().equalsIgnoreCase(current)) return snapshot.get(i + 1).name();
        }
        return "";
    }

    public String tierFor(Player player) {
        String primary = normalize(LuckPermsHook.group(player.getUniqueId()));
        for (Tier tier : tiers) {
            if (tier.groups().contains(primary)) return tier.name();
        }
        List<Tier> snapshot = tiers;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Tier tier = snapshot.get(i);
            if (!tier.permission().isEmpty() && player.hasPermission(tier.permission())) {
                return tier.name();
            }
        }
        return snapshot.getFirst().name();
    }

    public boolean receives(Player player, String stage) {
        return tierFor(player).equalsIgnoreCase(stage);
    }

    static String resolveRequested(List<Tier> tiers, String mentionedGroup) {
        return tiers.getFirst().name();
    }

    private static Set<String> normalize(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<Tier> defaults() {
        return List.of(
                new Tier("helper", Set.of("helper", "ayudante"), "chatalyst.support.tier.helper"),
                new Tier("mod", Set.of("mod", "moderador"), "chatalyst.support.tier.mod"),
                new Tier("admin", Set.of("admin", "administrador"), "chatalyst.support.tier.admin"),
                new Tier("owner", Set.of("owner"), "chatalyst.support.tier.owner"));
    }
}
