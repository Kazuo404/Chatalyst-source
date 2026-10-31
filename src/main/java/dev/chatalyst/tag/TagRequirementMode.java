package dev.chatalyst.tag;

import java.util.Locale;

/** Decide si deben cumplirse todas las reglas de un titulo o basta con una. */
public enum TagRequirementMode {
    ALL,
    ANY;

    public TagRequirementMode opposite() {
        return this == ALL ? ANY : ALL;
    }

    public static TagRequirementMode parse(String raw) {
        if (raw == null) return ALL;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
