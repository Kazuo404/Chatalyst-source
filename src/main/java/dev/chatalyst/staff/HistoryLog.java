package dev.chatalyst.staff;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.staff.tools.PunishmentIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Historial durable de castigos por jugador (ban, tempban, kick, freeze, mute y el
 * permaban del FreezeGuard): quien, cuando y por que, con tope de {@value #MAX} entradas
 * por persona. Lo consultan /check, /history y el menu de moderacion offline para responder
 * "¿este ya habia caido antes?" sin ir a leer logs.
 *
 * <p>Cada fila lleva ademas su NUMERO de castigo ({@link PunishmentIds}, el "#1532" de la
 * pantalla de ban) y si se puso en silencio ({@code -s}). Las filas antiguas, de antes de los
 * numeros, se leen igual: sin numero y sin silencio.
 */
public final class HistoryLog {

    // 50, no 20: los levantamientos (unban/unmute/unfreeze) tambien ocupan fila, y con 20 un
    // historial movido perdia los bans viejos justo cuando mas importa verlos.
    private static final int MAX = 50;
    private static final String ROW = "\u001e";
    private static final String FIELD = "\u001f";
    private static final String SILENT = "s";

    /**
     * @param id     numero de castigo de la red, o {@link PunishmentIds#NONE}
     * @param silent si se aplico con {@code -s} (sin anuncio publico)
     */
    public record Entry(long time, String type, String staff, String detail, long id,
                        boolean silent) {

        /** Fila sin numero ni silencio: la forma de antes, la que siguen usando los tests. */
        public Entry(long time, String type, String staff, String detail) {
            this(time, type, staff, detail, PunishmentIds.NONE, false);
        }
    }

    private final Store store;
    private final PunishmentIds ids;

    HistoryLog(StorageManager storage) {
        this(storage.store("staff_history"),
                new PunishmentIds(storage.store("staff_punishment_ids")));
    }

    /** Para las herramientas y los tests: mismo comportamiento con Stores directos. */
    public HistoryLog(Store store, PunishmentIds ids) {
        this.store = store;
        this.ids = ids;
    }

    /** Registra el castigo con un numero nuevo de la red y lo devuelve (0 si no hubo). */
    public long record(UUID target, String type, String staff, String detail) {
        return record(target, type, staff, detail, PunishmentIds.NONE, false);
    }

    /**
     * Registra el castigo. {@code id} es el numero que ya se le dio (el de la pantalla de ban,
     * para que el jugador y /history hablen del MISMO castigo); NONE pide uno nuevo.
     *
     * @return el numero con el que quedo registrado, o NONE
     */
    public long record(UUID target, String type, String staff, String detail, long id,
                       boolean silent) {
        if (target == null) return PunishmentIds.NONE;
        long number = id > PunishmentIds.NONE ? id
                : ids == null ? PunishmentIds.NONE : ids.next();
        Entry added = new Entry(System.currentTimeMillis(),
                sanitize(type), sanitize(staff), sanitize(detail), number, silent);
        // CAS read-modify-write: dos sanciones al MISMO objetivo a la vez (regiones Folia o
        // varios servers de la red) ya no se pisan la fila; el perdedor reintenta sobre el
        // valor real. Antes, un list-then-put plano perdia una de las dos entradas.
        boolean saved = dev.chatalyst.db.StoreCas.updateDurably(store, target.toString(), raw -> {
            List<Entry> entries = decode(raw);
            entries.add(added);
            while (entries.size() > MAX) entries.remove(0);
            return encode(entries);
        });
        if (!saved) {
            // El castigo en si ya quedo aplicado y auditado; perder una fila del historial
            // no debe bloquear la moderacion, solo avisarse.
            dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
            if (plugin != null) {
                plugin.getLogger().warning("Could not persist a punishment-history row for "
                        + target);
            }
        }
        return number;
    }

    public List<Entry> list(UUID target) {
        return List.copyOf(decode(store.get(target.toString())));
    }

    /**
     * Borra del historial de {@code target} las filas que cumplan {@code filter} (/prunehistory,
     * /staffrollback). Con CAS, como el alta: una sancion que entre a la vez no se pierde.
     *
     * @return cuantas filas se borraron, o -1 si el almacen no acepto la escritura
     */
    public int removeIf(UUID target, Predicate<Entry> filter) {
        if (target == null) return 0;
        int[] removed = {0};
        boolean saved = dev.chatalyst.db.StoreCas.updateDurably(store, target.toString(), raw -> {
            List<Entry> entries = decode(raw);
            int before = entries.size();
            entries.removeIf(filter);
            removed[0] = before - entries.size();
            // Nada que borrar: se aborta sin escribir (updateDurably devuelve false).
            return removed[0] == 0 ? null : encode(entries);
        });
        if (saved) return removed[0];
        return removed[0] == 0 ? 0 : -1;
    }

    /**
     * El historial de TODOS los jugadores de la red, por UUID. Recorre la tabla entera: solo
     * para consultas puntuales del staff (/staffhistory, /staffrollback) y siempre fuera del
     * hilo del servidor.
     */
    public Map<UUID, List<Entry>> all() {
        Map<UUID, List<Entry>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : store.all().entrySet()) {
            UUID id;
            try {
                id = UUID.fromString(row.getKey());
            } catch (IllegalArgumentException notAPlayer) {
                continue;
            }
            List<Entry> entries = decode(row.getValue());
            if (!entries.isEmpty()) out.put(id, List.copyOf(entries));
        }
        return out;
    }

    static List<Entry> decode(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String row : raw.split(ROW, -1)) {
            String[] fields = row.split(FIELD, -1);
            if (fields.length < 4) continue;
            try {
                long id = fields.length > 4 && !fields[4].isBlank()
                        ? Long.parseLong(fields[4]) : PunishmentIds.NONE;
                boolean silent = fields.length > 5 && SILENT.equals(fields[5]);
                out.add(new Entry(Long.parseLong(fields[0]), fields[1], fields[2], fields[3],
                        id, silent));
            } catch (NumberFormatException ignored) {
                // fila corrupta: se descarta sin romper el resto del historial
            }
        }
        return out;
    }

    static String encode(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(ROW);
            Entry entry = entries.get(i);
            out.append(entry.time()).append(FIELD).append(entry.type()).append(FIELD)
                    .append(entry.staff()).append(FIELD).append(entry.detail())
                    .append(FIELD).append(entry.id())
                    .append(FIELD).append(entry.silent() ? SILENT : "");
        }
        return out.toString();
    }

    void shutdown() {
        store.flushNow();
    }

    private static String sanitize(String value) {
        String safe = value == null ? "" : value;
        return safe.replace(ROW, " ").replace(FIELD, " ");
    }
}
