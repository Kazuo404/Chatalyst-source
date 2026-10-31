package dev.chatalyst.tag;

import java.util.Locale;

/** Rareza visual y de ordenacion de un titulo. */
public enum TagRarity {
    COMMON("<#C0B3D9>", 5),
    UNCOMMON("<#3DFFA8>", 10),
    RARE("<#54A6FF>", 25),
    EPIC("<#B56CFF>", 50),
    LEGENDARY("<#FFD166>", 100),
    MYTHIC("<#FF7A9E>", 250);

    private final String color;
    private final int defaultPoints;

    TagRarity(String color, int defaultPoints) {
        this.color = color;
        this.defaultPoints = defaultPoints;
    }

    public String color() {
        return color;
    }

    public int defaultPoints() {
        return defaultPoints;
    }

    public TagRarity step(int delta) {
        TagRarity[] values = values();
        return values[Math.floorMod(ordinal() + delta, values.length)];
    }

    public static TagRarity parse(String raw) {
        if (raw == null) return COMMON;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return COMMON;
        }
    }
}
