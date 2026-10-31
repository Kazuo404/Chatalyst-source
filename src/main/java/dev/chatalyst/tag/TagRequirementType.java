package dev.chatalyst.tag;

import java.util.Locale;

/** Tipos de regla de obtencion soportados por el catalogo de titulos. */
public enum TagRequirementType {
    NONE,
    MOB_KILLS,
    PLAYER_KILLS,
    PLAYTIME_MINUTES,
    BLOCKS_MINED,
    ITEMS_CRAFTED,
    ITEMS_PICKED_UP,
    ITEMS_OWNED,
    FISH_CAUGHT,
    ANIMALS_BRED,
    ADVANCEMENT,
    PERMISSION,
    TITLE_UNLOCKED,
    /** Primer numero de un placeholder de PlaceholderAPI (Target = %placeholder%). */
    PLACEHOLDER,
    /** Contador propio por jugador (Target = clave, ver {@link TitleCounters}). */
    COUNTER,
    MANUAL;

    public static TagRequirementType parse(String raw) {
        if (raw == null) return NONE;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }

    /** Como {@link #parse}, pero null si el texto no es ningun tipo (en vez de NONE). */
    public static TagRequirementType parseStrict(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public TagRequirementType step(int delta) {
        TagRequirementType[] values = values();
        return values[Math.floorMod(ordinal() + delta, values.length)];
    }
}
