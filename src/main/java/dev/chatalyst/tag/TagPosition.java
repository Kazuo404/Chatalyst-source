package dev.chatalyst.tag;

import java.util.Locale;

/** Lado del nombre en el que se renderiza un titulo del catalogo. */
public enum TagPosition {
    LEFT,
    RIGHT;

    public static TagPosition parse(String raw) {
        if (raw == null) return LEFT;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LEFT;
        }
    }

    public TagPosition opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }
}
