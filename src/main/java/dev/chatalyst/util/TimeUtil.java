package dev.chatalyst.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parseo y formato de duraciones sencillas: "10s", "5m", "2h", "1d", "1w", "perm".
 */
public final class TimeUtil {

    public static final long PERMANENT = -1L;

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);

    private TimeUtil() {
    }

    /**
     * @return duracion en milisegundos, {@link #PERMANENT} si es permanente,
     *         o 0 si no se reconoce.
     */
    /**
     * Si el texto PARECE una duracion (empieza por cifra: "10m", "10min", "2dias"). Sirve para
     * distinguir un tiempo mal escrito de la primera palabra del motivo: "/mute X 10min spam"
     * no puede convertirse en un mute PERMANENTE con motivo "10min spam".
     */
    public static boolean looksLikeDuration(String input) {
        return input != null && !input.isBlank() && Character.isDigit(input.trim().charAt(0));
    }

    public static long parseDuration(String input) {
        if (input == null) return 0;
        String s = input.trim().toLowerCase();
        if (s.equals("perm") || s.equals("permanent") || s.equals("permanente") || s.equals("forever")) {
            return PERMANENT;
        }
        Matcher m = TOKEN.matcher(s);
        long total = 0;
        boolean found = false;
        int consumed = 0;
        while (m.find()) {
            // Solo se permiten espacios entre tokens. Antes "1hbanana" se aceptaba
            // silenciosamente como una hora, peligroso en comandos de sancion.
            if (!s.substring(consumed, m.start()).isBlank()) return 0;
            found = true;
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException ex) {
                return PERMANENT; // numero absurdamente grande: se trata como permanente
            }
            long unit = switch (m.group(2).toLowerCase()) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 0L;
            };
            try {
                // Evita el desbordamiento silencioso (un mute que saldria con tiempo negativo).
                total = Math.addExact(total, Math.multiplyExact(value, unit));
            } catch (ArithmeticException ex) {
                return PERMANENT;
            }
            consumed = m.end();
        }
        return found && s.substring(consumed).isBlank() ? total : 0;
    }

    /**
     * Convierte una duracion en instante absoluto sin permitir que la suma se vuelva
     * negativa por overflow. Una duracion finita que rebasa el rango se satura a
     * permanente, que es el comportamiento mas seguro para una sancion.
     */
    public static long deadline(long nowMillis, long durationMillis) {
        if (durationMillis == PERMANENT) return PERMANENT;
        if (durationMillis <= 0) return nowMillis;
        try {
            return Math.addExact(nowMillis, durationMillis);
        } catch (ArithmeticException overflow) {
            return PERMANENT;
        }
    }

    public static long deadline(long durationMillis) {
        return deadline(System.currentTimeMillis(), durationMillis);
    }

    /** Distancia temporal inclusiva, segura incluso para Long.MIN_VALUE/MAX_VALUE. */
    public static boolean withinDistance(long left, long right, long maxDistance) {
        if (maxDistance < 0) return false;
        try {
            long distance = left >= right
                    ? Math.subtractExact(left, right)
                    : Math.subtractExact(right, left);
            return distance <= maxDistance;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Formato legible de una duracion restante en milisegundos. */
    public static String formatRemaining(long untilMillis) {
        if (untilMillis == PERMANENT) return "permanente";
        long remaining = untilMillis - System.currentTimeMillis();
        if (remaining <= 0) return "expirado";
        return formatElapsed(remaining);
    }

    /** Formato legible de una duracion ya transcurrida en milisegundos ("2d 4h 3m"). */
    public static String formatElapsed(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (days > 0) b.append(days).append("d ");
        if (hours > 0) b.append(hours).append("h ");
        if (minutes > 0) b.append(minutes).append("m ");
        if ((secs > 0 && days == 0) || b.isEmpty()) b.append(secs).append("s");
        return b.toString().trim();
    }
}
