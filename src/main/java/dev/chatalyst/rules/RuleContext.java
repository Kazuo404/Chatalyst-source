package dev.chatalyst.rules;

import java.util.Locale;

/** Execution context for a rule. */
public enum RuleContext {
    CHAT,
    COMMAND,
    SIGN,
    BOOK,
    ANVIL,
    NICK,
    DISCORD;

    public static RuleContext fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RuleContext.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
