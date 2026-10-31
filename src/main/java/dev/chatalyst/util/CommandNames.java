package dev.chatalyst.util;

import java.util.Collection;
import java.util.Locale;

/** Helpers for comparing Bukkit command labels, including namespaced labels. */
public final class CommandNames {

    private CommandNames() {
    }

    public static String label(String message) {
        if (message == null) return "";
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return "";
        String first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return first.startsWith("/") ? first : "/" + first;
    }

    public static String baseLabel(String message) {
        String label = label(message);
        int namespace = label.indexOf(':');
        return namespace >= 0 ? "/" + label.substring(namespace + 1) : label;
    }

    public static boolean matches(String message, Collection<String> configuredLabels) {
        String label = label(message);
        if (label.isEmpty()) return false;
        String base = baseLabel(label);

        for (String configured : configuredLabels) {
            String configuredLabel = label(configured);
            if (configuredLabel.isEmpty()) continue;
            if (label.equals(configuredLabel)) return true;
            if (base.equals(baseLabel(configuredLabel))) return true;
        }
        return false;
    }
}
