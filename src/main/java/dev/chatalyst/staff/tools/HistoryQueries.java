package dev.chatalyst.staff.tools;

import dev.chatalyst.staff.HistoryLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Consultas sobre el historial de castigos, sin Bukkit: paginar, lo que puso un staff,
 * lo que deshace un /staffrollback y lo que borra un /prunehistory. Todo testeable.
 */
public final class HistoryQueries {

    /** Lo que /staffrollback deshace: lo que deja a alguien fuera del servidor. */
    public static final Set<String> ROLLBACK_TYPES = Set.of("ban", "tempban", "banip", "kick");

    /** Tipos que cuentan como "baneo" para marcar el activo en /history. */
    public static final Set<String> BAN_TYPES =
            Set.of("ban", "tempban", "banip", "escalation-ban", "freeze-ban", "ipban-alt");

    /** Una fila del historial y el jugador al que pertenece. */
    public record StaffEntry(UUID target, HistoryLog.Entry entry) {
    }

    /** Una pagina: sus filas, el numero (desde 1), cuantas hay y el total de filas. */
    public record Page<T>(List<T> items, int page, int pages, int total) {
    }

    private HistoryQueries() {
    }

    /** La pagina {@code requested} (desde 1, fuera de rango se ajusta) de {@code size} filas. */
    public static <T> Page<T> page(List<T> all, int requested, int size) {
        int perPage = Math.max(1, size);
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int page = Math.min(Math.max(1, requested), pages);
        int from = (page - 1) * perPage;
        int to = Math.min(all.size(), from + perPage);
        return new Page<>(List.copyOf(all.subList(Math.min(from, all.size()), to)),
                page, pages, all.size());
    }

    /** "3" -> 3; cualquier otra cosa (o nada) -> 1. */
    public static int pageArg(String raw) {
        if (raw == null) return 1;
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /** Mas reciente primero: lo ultimo que paso es lo primero que el staff quiere ver. */
    public static List<HistoryLog.Entry> newestFirst(List<HistoryLog.Entry> entries) {
        List<HistoryLog.Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingLong(HistoryLog.Entry::time).reversed());
        return out;
    }

    /** Todo lo que puso {@code staff} en la red, mas reciente primero. */
    public static List<StaffEntry> byStaff(Map<UUID, List<HistoryLog.Entry>> all, String staff) {
        List<StaffEntry> out = new ArrayList<>();
        if (staff == null || staff.isBlank()) return out;
        for (Map.Entry<UUID, List<HistoryLog.Entry>> row : all.entrySet()) {
            for (HistoryLog.Entry entry : row.getValue()) {
                if (staff.equalsIgnoreCase(entry.staff())) {
                    out.add(new StaffEntry(row.getKey(), entry));
                }
            }
        }
        out.sort(Comparator.comparingLong((StaffEntry e) -> e.entry().time()).reversed());
        return out;
    }

    /**
     * Lo que deshace /staffrollback: bans, banips y kicks de {@code staff} puestos desde
     * {@code since} (incluido). Los levantamientos y el resto de tipos no se tocan.
     */
    public static List<StaffEntry> rollbackPlan(Map<UUID, List<HistoryLog.Entry>> all,
                                                String staff, long since) {
        return byStaff(all, staff).stream()
                .filter(e -> e.entry().time() >= since
                        && ROLLBACK_TYPES.contains(e.entry().type()))
                .toList();
    }

    /** Si una fila es una de las que el rollback decidio quitar (misma hora, tipo y staff). */
    public static Predicate<HistoryLog.Entry> sameAs(List<HistoryLog.Entry> chosen) {
        return candidate -> {
            for (HistoryLog.Entry entry : chosen) {
                if (entry.time() == candidate.time() && entry.type().equals(candidate.type())
                        && entry.staff().equalsIgnoreCase(candidate.staff())
                        && entry.id() == candidate.id()) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Que borra /prunehistory: TODO sin tiempo, o solo lo anterior a {@code olderThan}
     * (las filas de hace mas de ese tiempo).
     *
     * @param olderThan milisegundos; 0 o negativo = todo el historial
     */
    public static Predicate<HistoryLog.Entry> pruneFilter(long now, long olderThan) {
        if (olderThan <= 0) return entry -> true;
        long cutoff = now - olderThan;
        return entry -> entry.time() < cutoff;
    }

    /** Posicion (en una lista mas-reciente-primero) del ultimo baneo, o -1 si no hay. */
    public static int latestBan(List<HistoryLog.Entry> newestFirst) {
        for (int i = 0; i < newestFirst.size(); i++) {
            String type = newestFirst.get(i).type();
            if (BAN_TYPES.contains(type)) return i;
            // Un desbaneo posterior a cualquier baneo: no hay baneo "activo" que marcar.
            if (type.equals("unban")) return -1;
        }
        return -1;
    }
}
