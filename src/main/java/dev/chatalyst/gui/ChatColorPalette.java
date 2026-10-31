package dev.chatalyst.gui;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import dev.chatalyst.lang.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared /chatcolor palette used by both the GUI and direct command arguments.
 */
public final class ChatColorPalette {

    public enum Kind {
        COLOR,
        GRADIENT
    }

    /** Categoria visual para el hub del GUI de nick: fuertes, pastel o gradientes. */
    public enum Category {
        STRONG,
        PASTEL,
        GRADIENT
    }

    public record Option(String id, String label, String tag, Kind kind, Material material,
                         Color leatherColor, String permission, String alternatePermission) {
        public String displayLabel(CommandSender viewer) {
            String key = "color.palette." + id;
            String localized = Lang.raw(viewer, key);
            return key.equals(localized) ? label : localized;
        }

        public boolean canUse(Player player) {
            if (permission == null || permission.isBlank()) {
                return true;
            }
            if (player.hasPermission(permission) || player.hasPermission(wildcardPermission())) {
                return true;
            }
            return alternatePermission != null && !alternatePermission.equals(permission)
                    && player.hasPermission(alternatePermission);
        }

        public String permissionHint() {
            return permission == null ? "" : permission;
        }

        private String wildcardPermission() {
            return kind == Kind.GRADIENT ? "chatalyst.gradient.*" : "chatalyst.color.*";
        }
    }

    private static final Pattern HEX_TAG = Pattern.compile("<#([0-9a-fA-F]{6})>");

    private static final Object[][] VANILLA = {
            {"white", "White", "<white>", Material.WHITE_WOOL},
            {"gray", "Gray", "<gray>", Material.LIGHT_GRAY_WOOL},
            {"dark_gray", "Dark Gray", "<dark_gray>", Material.GRAY_WOOL},
            {"black", "Black", "<black>", Material.BLACK_WOOL},
            {"red", "Red", "<red>", Material.RED_WOOL},
            {"dark_red", "Dark Red", "<dark_red>", Material.RED_TERRACOTTA},
            {"gold", "Gold", "<gold>", Material.ORANGE_WOOL},
            {"yellow", "Yellow", "<yellow>", Material.YELLOW_WOOL},
            {"green", "Green", "<green>", Material.LIME_WOOL},
            {"dark_green", "Dark Green", "<dark_green>", Material.GREEN_WOOL},
            {"aqua", "Aqua", "<aqua>", Material.LIGHT_BLUE_WOOL},
            {"dark_aqua", "Dark Aqua", "<dark_aqua>", Material.CYAN_WOOL},
            {"blue", "Blue", "<blue>", Material.BLUE_WOOL},
            {"dark_blue", "Dark Blue", "<dark_blue>", Material.BLUE_TERRACOTTA},
            {"light_purple", "Light Purple", "<light_purple>", Material.MAGENTA_WOOL},
            {"dark_purple", "Dark Purple", "<dark_purple>", Material.PURPLE_WOOL},
    };

    private ChatColorPalette() {
    }

    public static List<Option> options(JavaPlugin plugin) {
        List<Option> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object[] data : VANILLA) {
            add(out, seen, new Option((String) data[0], (String) data[1], (String) data[2],
                    Kind.COLOR, (Material) data[3], null, null, null));
        }
        addConfigured(out, seen, plugin.getConfig().getConfigurationSection("Chat_Color.Colors"), Kind.COLOR);
        addConfigured(out, seen, plugin.getConfig().getConfigurationSection("Chat_Color.Gradients"), Kind.GRADIENT);
        return out;
    }

    /** Opciones filtradas por categoria, para cada seccion del hub de nick. */
    public static List<Option> options(JavaPlugin plugin, Category category) {
        return filter(options(plugin), category,
                plugin.getConfig().getStringList("Chat_Color.Categories.pastel"),
                plugin.getConfig().getStringList("Chat_Color.Categories.strong"));
    }

    /** Filtrado puro (sin config): lo comparten el apodo y el chat, y se prueba sin servidor. */
    static List<Option> filter(List<Option> all, Category category,
                               List<String> pastel, List<String> strong) {
        List<Option> out = new ArrayList<>();
        for (Option option : all) {
            if (categoryOf(option, pastel, strong) == category) out.add(option);
        }
        return out;
    }

    /**
     * Categoria de un color: los gradientes son su propia categoria; los colores planos se
     * parten en fuertes/pastel por LUMINANCIA (pastel = claro), salvo que la config los ubique
     * a mano en {@code Chat_Color.Categories.strong} / {@code .pastel} (asi el dueño manda).
     */
    public static Category categoryOf(JavaPlugin plugin, Option option) {
        return categoryOf(option,
                plugin.getConfig().getStringList("Chat_Color.Categories.pastel"),
                plugin.getConfig().getStringList("Chat_Color.Categories.strong"));
    }

    static Category categoryOf(Option option, List<String> pastel, List<String> strong) {
        if (option.kind() == Kind.GRADIENT) return Category.GRADIENT;
        if (listedIn(pastel, option.id())) return Category.PASTEL;
        if (listedIn(strong, option.id())) return Category.STRONG;
        // firstColor resuelve tanto hex (<#FF6B9B>) como nombrados (<red>); la letra extra
        // asegura que el color se aplique a algo que MM pueda leer.
        var color = dev.chatalyst.util.MM.firstColor(option.tag() + "x");
        if (color == null) return Category.STRONG;
        double luminance = 0.299 * color.red() + 0.587 * color.green() + 0.114 * color.blue();
        return luminance >= 170 ? Category.PASTEL : Category.STRONG;
    }

    private static boolean listedIn(List<String> ids, String id) {
        if (ids == null) return false;
        for (String raw : ids) {
            if (normalize(raw).equals(id)) return true;
        }
        return false;
    }

    /** La opcion de la paleta cuyo tag es exactamente {@code tag}, o null. */
    static Option byTag(List<Option> options, String tag) {
        if (tag == null || tag.isEmpty()) return null;
        for (Option option : options) {
            if (option.tag().equals(tag)) return option;
        }
        return null;
    }

    /** True si {@code tag} es un degradado o color de la paleta con permiso propio del jugador. */
    public static boolean ownsPreset(Player player, String tag) {
        JavaPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin == null || !player.hasPermission("chatalyst.chat.color")) return false;
        Option option = byTag(options(plugin), tag);
        return option != null && option.canUse(player);
    }

    public static Option find(JavaPlugin plugin, String input) {
        String wanted = normalize(input);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Option option : options(plugin)) {
            if (option.id().equals(wanted) || normalize(option.label()).equals(wanted)) {
                return option;
            }
        }
        return null;
    }

    private static void addConfigured(List<Option> out, Set<String> seen, ConfigurationSection section, Kind kind) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String tag = section.getString(key, "");
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String id = normalize(key);
            String rawId = rawId(key);
            Material material = kind == Kind.GRADIENT ? Material.GLOW_INK_SAC : Material.LEATHER_CHESTPLATE;
            String prefix = kind == Kind.GRADIENT ? "chatalyst.gradient." : "chatalyst.color.";
            add(out, seen, new Option(id, title(id), tag, kind, material, leatherColor(tag),
                    prefix + id, rawId.equals(id) ? null : prefix + rawId));
        }
    }

    private static void add(List<Option> out, Set<String> seen, Option option) {
        if (seen.add(option.id())) {
            out.add(option);
        }
    }

    private static Color leatherColor(String tag) {
        Matcher matcher = HEX_TAG.matcher(tag);
        if (!matcher.find()) {
            return null;
        }
        return Color.fromRGB(Integer.parseInt(matcher.group(1), 16));
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String rawId(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String title(String id) {
        String[] words = id.replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
