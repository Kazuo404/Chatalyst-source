package dev.chatalyst.events;

/**
 * Un evento programado del servidor: nombre (bilingue opcional), descripcion, cuando ocurre
 * (epoch millis) y cuantos minutos antes se recuerda. {@code reminded} marca si ya se envio el
 * aviso de "empieza pronto" para no repetirlo.
 */
public record ScheduledEvent(String id, String name, String nameEs, String description,
                             long whenMillis, int remindMinutes, boolean reminded) {

    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_DESCRIPTION_LENGTH = 512;

    public ScheduledEvent {
        name = bounded(name, MAX_NAME_LENGTH);
        nameEs = bounded(nameEs, MAX_NAME_LENGTH);
        description = bounded(description, MAX_DESCRIPTION_LENGTH);
        remindMinutes = Math.max(0, remindMinutes);
    }

    public ScheduledEvent withReminded(boolean value) {
        return new ScheduledEvent(id, name, nameEs, description, whenMillis, remindMinutes, value);
    }

    public ScheduledEvent withWhen(long millis) {
        return new ScheduledEvent(id, name, nameEs, description, millis, remindMinutes, false);
    }

    public ScheduledEvent withName(String newName, String newNameEs) {
        return new ScheduledEvent(id, newName, newNameEs, description, whenMillis, remindMinutes, reminded);
    }

    public ScheduledEvent withDescription(String desc) {
        return new ScheduledEvent(id, name, nameEs, desc, whenMillis, remindMinutes, reminded);
    }

    public ScheduledEvent withRemindMinutes(int minutes) {
        return new ScheduledEvent(id, name, nameEs, description, whenMillis, minutes, reminded);
    }

    public boolean upcoming(long now) {
        return whenMillis > now;
    }

    /** Nombre en el idioma del espectador (cae al nombre base si no hay traduccion). */
    public String displayName(boolean spanish) {
        return spanish && nameEs != null && !nameEs.isBlank() ? nameEs : name;
    }

    private static String bounded(String text, int max) {
        if (text == null) return "";
        return dev.chatalyst.util.Sanitize.limit(
                dev.chatalyst.util.Sanitize.stripInvisible(text).trim(), max);
    }
}
