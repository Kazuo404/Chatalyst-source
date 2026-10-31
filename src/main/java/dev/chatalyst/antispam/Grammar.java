package dev.chatalyst.antispam;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Correcciones gramaticales suaves del mensaje: capitalizar la primera letra y anadir
 * un punto final. Solo se aplica a mensajes de cierta longitud.
 */
public final class Grammar {

    private final JavaPlugin plugin;
    // Volatile: se leen desde el chat async mientras /reload los reescribe.
    private volatile boolean enabled;
    private volatile boolean capitalize;
    private volatile boolean punctuation;
    private volatile int minLength;

    public Grammar(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Grammar.Enabled", false);
        capitalize = plugin.getConfig().getBoolean("Grammar.Capitalize", true);
        punctuation = plugin.getConfig().getBoolean("Grammar.Punctuation", true);
        minLength = plugin.getConfig().getInt("Grammar.Min_Length", 4);
    }

    public String apply(String message) {
        if (!enabled || message == null) return message;
        String text = message;
        if (text.trim().length() < minLength) return text;

        if (capitalize) {
            int i = 0;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i < text.length() && Character.isLetter(text.charAt(i))) {
                text = text.substring(0, i) + Character.toUpperCase(text.charAt(i)) + text.substring(i + 1);
            }
        }
        if (punctuation) {
            String trimmed = text.stripTrailing();
            if (!trimmed.isEmpty()) {
                char lastChar = trimmed.charAt(trimmed.length() - 1);
                if (Character.isLetterOrDigit(lastChar)) {
                    text = trimmed + ".";
                }
            }
        }
        return text;
    }
}
