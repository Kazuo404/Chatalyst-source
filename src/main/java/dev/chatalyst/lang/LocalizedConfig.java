package dev.chatalyst.lang;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Owner-written configuration text, in the reader's language.
 *
 * <p>Strings in {@code lang/} are translated by Chatalyst; strings in a config file are
 * written by the server owner, so they cannot be. The convention across this plugin is a
 * parallel {@code _es} key — {@code Messages}/{@code Messages_es}, {@code Reply}/{@code
 * Reply_es} — read when the viewer's client is Spanish and falling back to the base key when
 * it is missing.
 *
 * <p>That convention was reimplemented by hand in every class that needed it, which is how a
 * new feature ends up single-language by accident: there was nothing to reuse, so the easy
 * path was to read the plain key. This is that something.
 */
public final class LocalizedConfig {

    private LocalizedConfig() {
    }

    /** Suffix for this viewer, empty for the base language. */
    public static String suffix(CommandSender viewer) {
        return suffix(viewer != null && Lang.isSpanish(viewer));
    }

    /** Suffix for a language already decided elsewhere — a kick screen has no viewer left. */
    public static String suffix(boolean spanish) {
        return spanish ? "_es" : "";
    }

    /**
     * @return the value in the viewer's language, the base value, or {@code fallback}. A blank
     *         translation counts as absent: an owner who left {@code Name_es: ""} behind wants
     *         the English name, not an empty line.
     */
    public static String string(ConfigurationSection section, String path,
                                CommandSender viewer, String fallback) {
        return string(section, path, viewer != null && Lang.isSpanish(viewer), fallback);
    }

    public static String string(ConfigurationSection section, String path,
                                boolean spanish, String fallback) {
        if (section == null) return fallback;
        String localized = section.getString(path + suffix(spanish));
        if (localized != null && !localized.isBlank()) return localized;
        String base = section.getString(path);
        return base != null ? base : fallback;
    }

    /**
     * @return the localized list, the base list, or empty — never a mix of the two, so a
     *         half-translated file shows one coherent language rather than both at once.
     */
    public static List<String> list(ConfigurationSection section, String path,
                                    CommandSender viewer) {
        return list(section, path, viewer != null && Lang.isSpanish(viewer));
    }

    public static List<String> list(ConfigurationSection section, String path, boolean spanish) {
        if (section == null) return List.of();
        String localizedPath = path + suffix(spanish);
        if (section.isList(localizedPath)) return List.copyOf(section.getStringList(localizedPath));
        return section.isList(path) ? List.copyOf(section.getStringList(path)) : List.of();
    }
}
