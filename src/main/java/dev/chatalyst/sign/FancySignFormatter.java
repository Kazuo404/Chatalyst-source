package dev.chatalyst.sign;

import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable renderer for player-authored sign lines. It accepts Minecraft legacy codes,
 * safe MiniMessage colors and configurable named macros such as {@code <galaxy>}.
 * Interaction tags are never installed in the parser, so a sign cannot inject clicks,
 * hovers, selectors or commands.
 */
public final class FancySignFormatter {

    public enum Status {
        UNCHANGED,
        FORMATTED,
        DENIED,
        INVALID
    }

    public record Result(Component component, Status status) {
        public boolean changesLine() {
            return status != Status.UNCHANGED;
        }
    }

    private static final String UMBRELLA_PERMISSION = "chatalyst.sign.*";
    private static final Pattern LEADING_STYLE = Pattern.compile(
            "^<([a-z][a-z0-9_-]{0,31})>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_FORMAT = Pattern.compile(
            "(?i)[&§](?:#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final String BASIC_TAGS = "#[0-9a-f]{6}|black|dark_blue|dark_green|"
            + "dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey|blue|"
            + "green|aqua|red|light_purple|yellow|white|bold|b|italic|em|i|underlined|u|"
            + "strikethrough|st|obfuscated|obf|reset";
    private static final Pattern BASIC_MINI = Pattern.compile(
            "(?i)</?(?:" + BASIC_TAGS + ")>");
    private static final Pattern GRADIENT = Pattern.compile("(?i)</?gradient(?::[^>]*)?>");
    private static final Pattern RAINBOW = Pattern.compile("(?i)</?rainbow(?::[^>]*)?>");
    private static final Pattern MINI_TAG = Pattern.compile("(?i)</?[a-z#][^<>]*>");
    private static final Pattern RAINBOW_ALIAS = Pattern.compile(
            "(?i)<(/?)(?:raimbow|arcoiris|arcoíris|multicolor|multi-color|multi_color)(?=[:>])");
    private static final Pattern GRADIENT_ALIAS = Pattern.compile(
            "(?i)<(/?)(?:gradiente|degradado)(?=[:>])");

    private final boolean enabled;
    private final String permission;
    private final Map<String, FancySignStyle> styles;

    private FancySignFormatter(FancySignSettings settings) {
        this.enabled = settings.enabled();
        this.permission = settings.permission();
        this.styles = settings.styles();
    }

    public static FancySignFormatter load(ConfigurationSection section) {
        return new FancySignFormatter(FancySignSettings.load(section));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Renders one line. A rejected format is replaced with its plain visible text so players
     * do not end up with raw {@code &4} or {@code <gradient>} syntax on the physical sign.
     */
    public Result render(String input, Predicate<String> hasPermission) {
        String raw = normalizeAliases(Sanitize.stripInvisible(input == null ? "" : input));
        NamedStyleMatch named = namedStyle(raw);
        boolean gradient = GRADIENT.matcher(raw).find();
        boolean rainbow = RAINBOW.matcher(raw).find();
        boolean basic = LEGACY_FORMAT.matcher(raw).find() || BASIC_MINI.matcher(raw).find();
        boolean miniTag = MINI_TAG.matcher(raw).find();
        if (named == null && !gradient && !rainbow && !basic && !miniTag) {
            return new Result(Component.text(raw), Status.UNCHANGED);
        }

        Component plain = Component.text(plainFallback(raw, named));
        if (!hasFullAccess(hasPermission)) return new Result(plain, Status.DENIED);
        String expanded = named == null ? raw : named.style.format().replace("{text}", named.text);
        try {
            Component formatted = MM.safe(Legacy.toMini(stripUnsupportedTags(expanded)));
            return new Result(formatted, Status.FORMATTED);
        } catch (RuntimeException ex) {
            return new Result(plain, Status.INVALID);
        }
    }

    private NamedStyleMatch namedStyle(String raw) {
        Matcher match = LEADING_STYLE.matcher(raw);
        if (!match.find()) return null;
        FancySignStyle style = styles.get(match.group(1).toLowerCase(Locale.ROOT));
        if (style == null) return null;
        String text = raw.substring(match.end());
        String closing = "</" + style.id() + ">";
        if (text.toLowerCase(Locale.ROOT).endsWith(closing)) {
            text = text.substring(0, text.length() - closing.length());
        }
        return new NamedStyleMatch(style, text);
    }

    private static String plainFallback(String raw, NamedStyleMatch named) {
        String visible = named == null ? raw : named.text;
        return MM.strip(Legacy.toMini(visible));
    }

    /** Removes action/dynamic tags instead of leaving their raw syntax visible on the sign. */
    private static String stripUnsupportedTags(String input) {
        Matcher matcher = MINI_TAG.matcher(input);
        StringBuilder clean = new StringBuilder(input.length());
        while (matcher.find()) {
            String tag = matcher.group();
            if (isSupportedTag(tag)) {
                matcher.appendReplacement(clean, Matcher.quoteReplacement(tag));
            } else {
                matcher.appendReplacement(clean, "");
            }
        }
        matcher.appendTail(clean);
        return clean.toString();
    }

    private static boolean isSupportedTag(String tag) {
        return BASIC_MINI.matcher(tag).matches() || GRADIENT.matcher(tag).matches()
                || RAINBOW.matcher(tag).matches();
    }

    /** Accepts common Spanish names and the frequent "raimbow" typo used by players. */
    private static String normalizeAliases(String input) {
        String normalized = RAINBOW_ALIAS.matcher(input).replaceAll("<$1rainbow");
        return GRADIENT_ALIAS.matcher(normalized).replaceAll("<$1gradient");
    }

    private boolean hasFullAccess(Predicate<String> hasPermission) {
        return permission.isEmpty() || hasPermission.test(UMBRELLA_PERMISSION)
                || hasPermission.test(permission);
    }

    private record NamedStyleMatch(FancySignStyle style, String text) {
    }
}
