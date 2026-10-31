package dev.chatalyst.staff.tools;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Un lockdown activo: el motivo (ya saneado, MiniMessage visual), quien lo puso y desde
 * cuando. Se guarda como una sola linea de texto IMPRIMIBLE: el Store YAML convierte en
 * !!binary cualquier cadena con caracteres de control y se perdia al releerla.
 */
public record LockdownState(String reason, String staff, long since) {

    private static final String SEP = ":";
    private static final String VERSION = "1";

    public String encode() {
        return String.join(SEP, VERSION, Long.toString(since), text(reason), text(staff));
    }

    /** El estado guardado, o null si no hay lockdown o la fila no se entiende. */
    public static LockdownState decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(SEP, -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) return null;
        try {
            return new LockdownState(untext(parts[2]), untext(parts[3]),
                    Long.parseLong(parts[1]));
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    private static String text(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String untext(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
