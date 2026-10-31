package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Shared MiniMessage decoration options used by chat color and nick color menus. */
public enum TextStyleOption {

    BOLD("bold", "Bold", "<bold>", Material.IRON_BLOCK),
    ITALIC("italic", "Italic", "<italic>", Material.FEATHER),
    UNDERLINED("underlined", "Underlined", "<underlined>", Material.CHAIN),
    STRIKETHROUGH("strikethrough", "Strike", "<strikethrough>", Material.SHEARS),
    OBFUSCATED("obfuscated", "Magic", "<obfuscated>", Material.ENDER_EYE);

    private final String id;
    private final String label;
    private final String tag;
    private final Material material;

    TextStyleOption(String id, String label, String tag, Material material) {
        this.id = id;
        this.label = label;
        this.tag = tag;
        this.material = material;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** Localized UI label; the stable English label remains available for config/CLI lookup. */
    public String displayLabel(CommandSender viewer) {
        String key = "text-style." + id;
        String localized = Lang.raw(viewer, key);
        return key.equals(localized) ? label : localized;
    }

    public String tag() {
        return tag;
    }

    public Material material() {
        return material;
    }

    public boolean canUse(Player player, String permissionPrefix) {
        return player.hasPermission(permissionPrefix + ".*")
                || player.hasPermission(permissionPrefix + "." + id);
    }

    public static String tags(Collection<TextStyleOption> styles) {
        if (styles == null || styles.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TextStyleOption option : values()) {
            if (styles.contains(option)) {
                out.append(option.tag);
            }
        }
        return out.toString();
    }

    public static Set<TextStyleOption> parseIds(String raw) {
        EnumSet<TextStyleOption> out = EnumSet.noneOf(TextStyleOption.class);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            TextStyleOption option = byId(part.trim());
            if (option != null) {
                out.add(option);
            }
        }
        return out;
    }

    public static String serializeIds(Collection<TextStyleOption> styles) {
        if (styles == null || styles.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TextStyleOption option : values()) {
            if (styles.contains(option)) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(option.id);
            }
        }
        return out.toString();
    }

    public static Set<TextStyleOption> detect(String formatted) {
        EnumSet<TextStyleOption> out = EnumSet.noneOf(TextStyleOption.class);
        if (formatted == null || formatted.isBlank()) {
            return out;
        }
        String lower = formatted.toLowerCase(Locale.ROOT);
        for (TextStyleOption option : values()) {
            if (lower.contains(option.tag)) {
                out.add(option);
            }
        }
        return out;
    }

    public static String detectColorTag(String formatted, Collection<ChatColorPalette.Option> options) {
        if (formatted == null || formatted.isBlank() || options == null) {
            return "";
        }
        String direct = findColorPrefix(formatted, options);
        if (!direct.isEmpty()) {
            return direct;
        }
        String unstyled = removeLeadingStyleTags(formatted);
        String palette = findColorPrefix(unstyled, options);
        if (!palette.isEmpty()) {
            return palette;
        }
        // Un color que NO esta en la paleta: el gradiente propio de dos colores, o un color que
        // el dueño quito de la config. Sin esto, cambiar un estilo (negrita...) reconstruia el
        // apodo sin color: quien tenia <gradient:dark_red:red> lo perdia al tocar la negrita.
        java.util.regex.Matcher leading = LEADING_COLOR.matcher(unstyled);
        return leading.lookingAt() ? leading.group() : "";
    }

    // Solo formas de color cerradas: hex, los 16 nombres, gradiente/arcoiris con argumentos de
    // color. Nada con click/hover puede casar aqui.
    private static final java.util.regex.Pattern LEADING_COLOR = java.util.regex.Pattern.compile(
            "(?i)<(?:#[0-9a-f]{6}"
                    + "|(?:gradient|rainbow)(?::[#0-9a-z_.!-]+)*"
                    + "|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey"
                    + "|dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white)>");

    public static String decorate(String colorTag, Collection<TextStyleOption> styles, String plain) {
        return (colorTag == null ? "" : colorTag) + tags(styles) + dev.chatalyst.util.MM.escape(plain);
    }

    private static TextStyleOption byId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        for (TextStyleOption option : values()) {
            if (option.id.equals(id)) {
                return option;
            }
        }
        return null;
    }

    private static String findColorPrefix(String formatted, Collection<ChatColorPalette.Option> options) {
        String best = "";
        for (ChatColorPalette.Option option : options) {
            String tag = option.tag();
            if (formatted.startsWith(tag) && tag.length() > best.length()) {
                best = tag;
            }
        }
        return best;
    }

    private static String removeLeadingStyleTags(String formatted) {
        String out = formatted;
        boolean changed;
        do {
            changed = false;
            for (TextStyleOption option : values()) {
                if (out.startsWith(option.tag)) {
                    out = out.substring(option.tag.length());
                    changed = true;
                }
            }
        } while (changed);
        return out;
    }
}
