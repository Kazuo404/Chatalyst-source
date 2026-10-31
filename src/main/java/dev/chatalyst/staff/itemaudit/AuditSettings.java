package dev.chatalyst.staff.itemaudit;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Lo que dice {@code Staff_Item_Audit} en moderation.yml, ya validado y acotado. */
record AuditSettings(boolean enabled, int retentionDays, int pageSize,
                     boolean trackCreative, long creativeMoveWindowMillis,
                     Set<String> creativeIgnoreWorlds,
                     boolean trackCommands, boolean includeConsole, List<GivePattern> patterns,
                     boolean markItems, long markReuseMillis,
                     boolean trackTransfers, long transferRepeatMillis,
                     long burstWindowMillis, boolean alertEverything, boolean alertDiscord,
                     ValuableRules valuable,
                     GamemodeRules gamemode, boolean logGamemode, Set<String> alertGamemodes,
                     int recheckSeconds) {

    static final String ROOT = "Staff_Item_Audit";

    /**
     * Si la config no trae la lista (un moderation.yml de antes de esta funcion que el
     * actualizador aun no completo), se vigila al menos lo que pidio el dueño.
     */
    static final List<String> DEFAULT_COMMANDS = List.of(
            "give <player> <item> [amount] [rest]",
            "egive <player> <item> [amount] [rest]",
            "i <item> [amount] [rest]",
            "item <item> [amount] [rest]",
            "eitem <item> [amount] [rest]",
            "kit <any> <player>",
            "ekit <any> <player>",
            "ak give <player> [rest]",
            "loot give <player> [rest]");

    static AuditSettings disabled() {
        return new AuditSettings(false, 90, 8, false, 30_000L, Set.of(), false, false, List.of(),
                false, 300_000L, false, 600_000L, 5_000L, false, false, ValuableRules.none(),
                GamemodeRules.off(), false, Set.of(), 0);
    }

    /**
     * @param tags resuelve una etiqueta ({@code #minecraft:logs}) en claves de material; en
     *             los tests no hay registro de etiquetas y basta con devolver una lista vacia
     */
    static AuditSettings load(ConfigurationSection section, Consumer<String> warn,
                              Function<String, List<String>> tags) {
        if (section == null) return disabled();
        List<GivePattern> patterns = new ArrayList<>();
        List<String> rawCommands = section.isList("Commands.Watch")
                ? section.getStringList("Commands.Watch") : DEFAULT_COMMANDS;
        for (String raw : rawCommands) {
            GivePattern pattern = GivePattern.parse(raw, problem -> warn.accept("Commands.Watch "
                    + problem));
            if (pattern != null) patterns.add(pattern);
        }

        List<String> materials = new ArrayList<>();
        for (String raw : section.getStringList("Valuable.Materials")) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.startsWith("#")) {
                List<String> expanded = tags.apply(entry.substring(1));
                if (expanded.isEmpty()) warn.accept("Valuable.Materials: unknown tag " + entry);
                materials.addAll(expanded);
            } else if (!entry.isEmpty()) {
                materials.add(entry);
            }
        }
        ValuableRules valuable = new ValuableRules(materials,
                section.getBoolean("Valuable.Over_Enchanted", true),
                section.getBoolean("Valuable.Any_Enchanted", false),
                section.getBoolean("Valuable.Custom_Name", false),
                section.getBoolean("Valuable.Check_Contents", true));

        String defaultMode = GamemodeRules.normalize(
                section.getString("Gamemode.Default_Mode", "SURVIVAL"));
        if (!isGamemode(defaultMode)) {
            warn.accept("Gamemode.Default_Mode '" + defaultMode + "' is not a gamemode;"
                    + " using SURVIVAL");
            defaultMode = "SURVIVAL";
        }
        GamemodeRules gamemode = new GamemodeRules(
                section.getBoolean("Gamemode.Restrict", true), defaultMode,
                Set.copyOf(section.getStringList("Gamemode.Exempt_Worlds")));
        Set<String> alertModes = new LinkedHashSet<>();
        for (String mode : section.getStringList("Gamemode.Alert_Modes")) {
            String normalized = GamemodeRules.normalize(mode);
            if (isGamemode(normalized)) {
                alertModes.add(normalized);
            } else {
                warn.accept("Gamemode.Alert_Modes: '" + mode + "' is not a gamemode");
            }
        }

        return new AuditSettings(
                section.getBoolean("Enabled", false),
                clamp(section.getInt("Retention_Days", 90), 0, 36_500),
                clamp(section.getInt("Page_Size", 8), 3, 20),
                section.getBoolean("Creative.Enabled", true),
                seconds(section.getInt("Creative.Move_Window_Seconds", 30), 1, 600),
                lowerCase(section.getStringList("Creative.Ignore_Worlds")),
                section.getBoolean("Commands.Enabled", true),
                section.getBoolean("Commands.Include_Console", true),
                List.copyOf(patterns),
                section.getBoolean("Marking.Enabled", true),
                seconds(section.getInt("Marking.Reuse_Window_Seconds", 300), 0, 86_400),
                section.getBoolean("Transfers.Enabled", true),
                seconds(section.getInt("Transfers.Repeat_Minutes", 10) * 60, 0, 86_400),
                seconds(section.getInt("Alerts.Burst_Seconds", 5), 1, 120),
                section.getBoolean("Alerts.Everything", false),
                section.getBoolean("Alerts.Discord", true),
                valuable, gamemode,
                section.getBoolean("Gamemode.Log_Changes", true),
                Set.copyOf(alertModes),
                clamp(section.getInt("Gamemode.Recheck_Seconds", 30), 0, 3_600));
    }

    static boolean isGamemode(String mode) {
        return switch (mode == null ? "" : mode.toUpperCase(Locale.ROOT)) {
            case "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR" -> true;
            default -> false;
        };
    }

    private static Set<String> lowerCase(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    /** Un mundo de construccion en creativo no es "sacar de la nada": alli todo sale asi. */
    boolean ignoresCreativeIn(String world) {
        return world != null && creativeIgnoreWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long seconds(int value, int min, int max) {
        return clamp(value, min, max) * 1000L;
    }
}
