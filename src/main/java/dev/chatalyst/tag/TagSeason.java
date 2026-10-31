package dev.chatalyst.tag;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Ventana temporal opcional de un titulo. El identificador forma parte de la clave
 * de desbloqueo, por lo que cambiarlo inicia una temporada nueva sin borrar historial.
 */
public record TagSeason(String id, long startsAtMillis, long endsAtMillis) {

    public static final int MAX_ID_LENGTH = 32;

    public TagSeason {
        id = normalizeId(id);
        startsAtMillis = Math.max(0L, startsAtMillis);
        endsAtMillis = Math.max(0L, endsAtMillis);
        if (id.isEmpty()) {
            startsAtMillis = 0L;
            endsAtMillis = 0L;
        }
    }

    public static TagSeason none() {
        return new TagSeason("", 0L, 0L);
    }

    public boolean enabled() {
        return !id.isEmpty();
    }

    public boolean validWindow() {
        return !enabled() || startsAtMillis == 0L || endsAtMillis == 0L
                || startsAtMillis < endsAtMillis;
    }

    public boolean active(long nowMillis) {
        if (!enabled()) return true;
        if (!validWindow()) return false;
        return (startsAtMillis == 0L || nowMillis >= startsAtMillis)
                && (endsAtMillis == 0L || nowMillis < endsAtMillis);
    }

    public boolean upcoming(long nowMillis) {
        return enabled() && validWindow() && startsAtMillis > 0L
                && nowMillis < startsAtMillis;
    }

    public boolean ended(long nowMillis) {
        return enabled() && validWindow() && endsAtMillis > 0L
                && nowMillis >= endsAtMillis;
    }

    public String unlockToken(String titleId) {
        String normalizedTitle = TagDefinition.normalizeId(titleId);
        return enabled() ? normalizedTitle + "~" + id : normalizedTitle;
    }

    public TagSeason withId(String value) {
        return new TagSeason(value, startsAtMillis, endsAtMillis);
    }

    public TagSeason withStartsAt(long value) {
        return new TagSeason(id, value, endsAtMillis);
    }

    public TagSeason withEndsAt(long value) {
        return new TagSeason(id, startsAtMillis, value);
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.length() <= MAX_ID_LENGTH) return normalized;
        // Igual que en TagDefinition: sin esto el id recortado no seria un punto fijo y un
        // update() por API cambiaria el unlockToken, huerfanando los desbloqueos ya guardados.
        return normalized.substring(0, MAX_ID_LENGTH).replaceAll("-+$", "");
    }

    public static long parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        String value = raw.trim();
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0L, Instant.parse(value).toEpochMilli());
            } catch (DateTimeParseException invalid) {
                return 0L;
            }
        }
    }

    public static String formatInstant(long value) {
        return value <= 0L ? "" : Instant.ofEpochMilli(value).toString();
    }
}
