package dev.chatalyst.toggle;

import java.util.Locale;

/** Preferencias personales que un jugador puede cambiar con /toggle. */
public enum ToggleType {
    PM(true),             // recibir mensajes privados
    ANNOUNCEMENTS(true),  // recibir anuncios automaticos
    MENTIONS(true),       // recibir el sonido al ser mencionado
    JOINQUIT(true),       // ver los mensajes de entrada/salida de otros jugadores
    TRANSLATE(false);     // traducir el chat al idioma del cliente: siempre opt-in

    private final boolean enabledByDefault;

    ToggleType(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    public static ToggleType from(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
