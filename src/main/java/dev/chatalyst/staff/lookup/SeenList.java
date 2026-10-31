package dev.chatalyst.staff.lookup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lista "visto por primera / ultima vez" serializada en un solo valor del Store.
 *
 * <p>La usan las tres tablas de identidad de {@code AltTracker}: las conexiones de una cuenta
 * ({@code acc:<uuid>}, clave = hash), las cuentas de una conexion ({@code conn:<hash>}, clave =
 * uuid, etiqueta = nombre) y los nombres de una cuenta ({@code names:<uuid>}, clave = nombre).
 * Formato: {@code clave|etiqueta|primera|ultima} separadas por {@code ;}.
 *
 * <p>Sin Bukkit a proposito: aqui vive la regla que mas importa en un join, que es NO escribir
 * cuando no cambia nada. La ultima vez solo se adelanta si paso al menos {@code granularity}:
 * un jugador que entra y sale veinte veces en una tarde deja una sola escritura.
 */
public final class SeenList {

    private static final String ROW = ";";
    private static final String FIELD = "|";

    /** Una fila: que se vio, como se llamaba entonces y entre que fechas. */
    public record Seen(String key, String label, long first, long last) {
    }

    private SeenList() {
    }

    /** Filas guardadas, mas reciente primero. Una fila rota se salta sin tirar el resto. */
    public static List<Seen> decode(String raw) {
        List<Seen> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String row : raw.split(ROW)) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 4 || fields[0].isEmpty()) continue;
            try {
                out.add(new Seen(fields[0], fields[1], Long.parseLong(fields[2]),
                        Long.parseLong(fields[3])));
            } catch (NumberFormatException corrupt) {
                // fila corrupta: se descarta, el resto sigue valiendo
            }
        }
        out.sort(Comparator.comparingLong(Seen::last).reversed());
        return out;
    }

    public static String encode(List<Seen> rows) {
        StringBuilder out = new StringBuilder();
        for (Seen row : rows) {
            if (!out.isEmpty()) out.append(ROW);
            out.append(clean(row.key())).append(FIELD).append(clean(row.label()))
                    .append(FIELD).append(row.first()).append(FIELD).append(row.last());
        }
        return out.toString();
    }

    /**
     * El valor tras ver {@code key} ahora, o null si no hay nada que escribir.
     *
     * @param granularity cuanto tiene que haber pasado para adelantar la ultima vez
     * @param cap         filas maximas; sobran las vistas hace mas tiempo
     */
    public static String touch(String raw, String key, String label, long now,
                               long granularity, int cap) {
        if (key == null || key.isEmpty()) return null;
        String cleanKey = clean(key);
        String cleanLabel = clean(label == null ? "" : label);
        List<Seen> rows = decode(raw);
        boolean changed = false;
        boolean found = false;
        for (int i = 0; i < rows.size(); i++) {
            Seen row = rows.get(i);
            if (!row.key().equals(cleanKey)) continue;
            found = true;
            boolean relabel = !row.label().equals(cleanLabel);
            boolean advance = now - row.last() >= granularity;
            if (relabel || advance) {
                rows.set(i, new Seen(cleanKey, cleanLabel, Math.min(row.first(), now),
                        advance ? now : row.last()));
                changed = true;
            }
            break;
        }
        if (!found) {
            rows.add(new Seen(cleanKey, cleanLabel, now, now));
            changed = true;
        }
        if (!changed) return null;
        rows.sort(Comparator.comparingLong(Seen::last).reversed());
        while (rows.size() > Math.max(1, cap)) rows.remove(rows.size() - 1);
        String next = encode(rows);
        return Objects.equals(next, raw) ? null : next;
    }

    /**
     * Mezcla un tramo ya ocurrido ({@code first}..{@code last}), p. ej. sacado de un log viejo.
     * A diferencia de {@link #touch}, nunca adelanta nada por "ahora": la primera vez solo
     * retrocede, la ultima solo avanza, y la etiqueta es la del tramo mas reciente. Un 0
     * guardado (fila legacy sin fechas) cuenta como desconocido. Null si no cambia nada.
     */
    public static String merge(String raw, String key, String label, long first, long last,
                               int cap) {
        if (key == null || key.isEmpty() || last <= 0) return null;
        long from = first <= 0 ? last : Math.min(first, last);
        String cleanKey = clean(key);
        String cleanLabel = clean(label == null ? "" : label);
        List<Seen> rows = decode(raw);
        boolean found = false;
        for (int i = 0; i < rows.size(); i++) {
            Seen row = rows.get(i);
            if (!row.key().equals(cleanKey)) continue;
            found = true;
            long newFirst = row.first() <= 0 ? from : Math.min(row.first(), from);
            long newLast = Math.max(row.last(), last);
            String newLabel = last >= row.last() && !cleanLabel.isEmpty() ? cleanLabel : row.label();
            rows.set(i, new Seen(cleanKey, newLabel, newFirst, newLast));
            break;
        }
        if (!found) rows.add(new Seen(cleanKey, cleanLabel, from, last));
        rows.sort(Comparator.comparingLong(Seen::last).reversed());
        while (rows.size() > Math.max(1, cap)) rows.remove(rows.size() - 1);
        String next = encode(rows);
        return Objects.equals(next, raw) ? null : next;
    }

    /** Los separadores no pueden colarse en un nombre (Bedrock admite cosas raras). */
    static String clean(String value) {
        return value == null ? "" : value.replace(ROW, "_").replace(FIELD, "_");
    }
}
