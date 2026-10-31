package dev.chatalyst.staff.itemaudit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Formato y paginado del registro, sin servidor: lo que ve el staff en /itemaudit. */
final class AuditFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AuditFormat() {
    }

    /** Hace cuanto, con las dos unidades mayores: "45s", "12m", "3h 5m", "2d 4h". */
    static String ago(long elapsedMillis) {
        long seconds = Math.max(0L, elapsedMillis) / 1000L;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
        long days = hours / 24;
        return days + "d" + (hours % 24 == 0 ? "" : " " + hours % 24 + "h");
    }

    static String date(long time, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(time).atZone(zone));
    }

    /** "minecraft:diamond_sword" -&gt; "Diamond Sword". Para la consola, Discord y lo desconocido. */
    static String prettyItem(String key) {
        if (key == null || key.isEmpty()) return "?";
        String path = key.substring(key.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(path.length());
        for (String word : path.split("[_/]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? key : out.toString();
    }

    /** "sharpness 10, unbreaking 3", ordenado para que dos filas iguales se lean igual. */
    static String enchantments(Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        enchants.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> parts.add(e.getKey().substring(e.getKey().indexOf(':') + 1)
                        .replace('_', ' ') + " " + e.getValue()));
        return String.join(", ", parts);
    }

    /**
     * Resumen de una rafaga: cuantos stacks y objetos, y los materiales de mas a menos.
     *
     * @param top los {@code limit} materiales con mas unidades
     */
    record Summary(int stacks, long items, List<Map.Entry<String, Long>> top, int others,
                   boolean valuable) {
    }

    static Summary summarize(List<AuditEntry> entries, int limit) {
        Map<String, Long> byItem = new LinkedHashMap<>();
        long items = 0;
        boolean valuable = false;
        for (AuditEntry entry : entries) {
            byItem.merge(entry.item(), (long) entry.amount(), Long::sum);
            items += entry.amount();
            valuable |= entry.valuable();
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(byItem.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int keep = Math.min(Math.max(0, limit), sorted.size());
        List<Map.Entry<String, Long>> top = sorted.subList(0, keep).stream()
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
        return new Summary(entries.size(), items, top, sorted.size() - keep, valuable);
    }

    /** Una pagina, 1-based. {@code hasNext} sin contar todo: basta con que sobre una fila. */
    record Page<T>(List<T> rows, int page, boolean hasNext) {
    }

    /**
     * @param newestFirst lo leido, como mucho {@code page*size+1} filas (ver {@link #needed})
     */
    static <T> Page<T> page(List<T> newestFirst, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(1, page);
        int from = (safePage - 1) * safeSize;
        if (from >= newestFirst.size()) return new Page<>(List.of(), safePage, false);
        int to = Math.min(newestFirst.size(), from + safeSize);
        return new Page<>(List.copyOf(newestFirst.subList(from, to)), safePage,
                newestFirst.size() > to);
    }

    /** Cuantas filas hay que leer para pintar la pagina {@code page} y saber si hay otra. */
    static int needed(int page, int size) {
        long wanted = (long) Math.max(1, page) * Math.max(1, size) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, wanted);
    }

    /** "3" -&gt; 3; cualquier otra cosa -&gt; 1. Una pagina no es un motivo para un error. */
    static int pageArg(String raw) {
        try {
            return Math.max(1, Math.min(10_000, Integer.parseInt(raw.trim())));
        } catch (RuntimeException notANumber) {
            return 1;
        }
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
