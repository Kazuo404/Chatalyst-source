package dev.chatalyst.util;

/**
 * Strips dangerous invisible characters used to evade filters or for spoofing:
 * zero-width joiners/spaces, bidirectional controls (RTL override), BOM, and C0/C1
 * control characters. Normal spaces and printable text are kept.
 */
public final class Sanitize {

    // Ademas de zero-width/bidi clasicos: soft hyphen (00AD), combining grapheme joiner
    // (034F), variation selectors (FE00-FE0F), rellenos Hangul "en blanco" (115F, 1160,
    // 3164, FFA0) y los Unicode TAG characters (E0000-E007F), todos invisibles y usados
    // para evadir filtros, camuflar nombres o contrabandear texto oculto.
    //
    // Y dos que faltaban, de la misma familia que los rellenos Hangul que ya estaban:
    //   - 2800 BRAILLE PATTERN BLANK: el patron braille SIN puntos, que se pinta como un
    //     espacio en practicamente cualquier fuente. Es el relleno invisible mas conocido.
    //     Solo el 2800; del 2801 en adelante los puntos SI se ven y son texto legitimo.
    //   - 2028/2029 LINE y PARAGRAPH SEPARATOR, que caian justo en el hueco entre los
    //     zero-width (termina en 200F) y los controles bidi (empieza en 202A).
    private static final java.util.regex.Pattern FORMATTING_CONTROLS =
            java.util.regex.Pattern.compile(
                    "[\\u00AD\\u034F\\u115F\\u1160\\u180E\\u200B-\\u200F\\u2028\\u2029"
                            + "\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\u2800\\u3164"
                            + "\\uFE00-\\uFE0F\\uFEFF\\uFFA0\\x{E0000}-\\x{E007F}]");
    private static final java.util.regex.Pattern CONTROL_CHARACTERS =
            java.util.regex.Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]");

    private Sanitize() {
    }

    public static String stripInvisible(String s) {
        if (s == null || s.isEmpty()) return s;
        return stripControlCharacters(stripFormattingControls(s));
    }

    /** Removes zero-width and bidirectional formatting controls, but keeps C0/C1 controls. */
    public static String stripFormattingControls(String s) {
        if (s == null || s.isEmpty()) return s;
        return FORMATTING_CONTROLS.matcher(s).replaceAll("");
    }

    /** Removes C0/C1 control characters, but keeps zero-width/bidirectional formatting. */
    public static String stripControlCharacters(String s) {
        if (s == null || s.isEmpty()) return s;
        return CONTROL_CHARACTERS.matcher(s).replaceAll("");
    }

    /** Trunca por code points para no partir un emoji/par sustituto UTF-16 a la mitad. */
    public static String limit(String value, int maxCodePoints) {
        if (value == null || maxCodePoints < 0) return value;
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
