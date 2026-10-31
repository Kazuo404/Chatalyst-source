package dev.chatalyst.report;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * Reportes persistentes con ciclo de vida: OPEN -> CLAIMED -> RESOLVED. Se guardan en el
 * {@link Store} "reports" (YAML o base de datos; con MySQL son cross-server, asi que el
 * staff los ve y atiende desde cualquier server de la red). Cada reporte tiene un id
 * incremental para referirse a el desde el juego o desde Discord.
 */
public final class ReportManager {

    public static final int MAX_REASON_LENGTH = 1_024;
    public static final int MAX_NOTE_LENGTH = 1_024;

    public enum Status {
        OPEN, CLAIMED, PENDING, RESOLVED, REJECTED;

        /** Cerrado: ya no se gestiona (resuelto o rechazado). */
        public boolean isClosed() {
            return this == RESOLVED || this == REJECTED;
        }
    }

    public record Report(int id, String reporter, String reporterUuid, String target, String reason,
                         long time, Status status, String handler, String note, boolean priority,
                         String world, double x, double y, double z,
                         String targetUuid, String targetDisplay) {
        /** Constructor del formato anterior: conserva compatibilidad para callers/tests. */
        public Report(int id, String reporter, String reporterUuid, String target, String reason,
                      long time, Status status, String handler, String note, boolean priority,
                      String world, double x, double y, double z) {
            this(id, reporter, reporterUuid, target, reason, time, status, handler, note,
                    priority, world, x, y, z, "", target);
        }

        public Report(int id, String reporter, String reporterUuid, String target, String reason,
                      long time, Status status, String handler, String note, boolean priority) {
            this(id, reporter, reporterUuid, target, reason, time, status, handler, note, priority,
                    "", 0.0D, 0.0D, 0.0D, "", target);
        }

        public boolean hasLocation() {
            return world != null && !world.isBlank();
        }
    }

    private static final char US = '\u001F';
    private static final String SEQ_KEY = "$seq";

    private final StorageManager storage;
    private final Store store;
    private final AtomicInteger seq;

    public ReportManager(StorageManager storage) {
        this.storage = storage;
        this.store = storage.store("reports");
        this.seq = new AtomicInteger(initialSequence(store));
    }

    ReportManager(Store store) {
        this.storage = null;
        this.store = store;
        this.seq = new AtomicInteger(initialSequence(store));
    }

    private static int initialSequence(Store store) {
        String s = store.get(SEQ_KEY);
        int start = 0;
        if (s != null) {
            try {
                start = Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                // valor corrupto: reconstruimos desde el mayor id existente
            }
        }
        // El hint puede quedar atras si el proceso cae tras guardar el reporte o si dos
        // creaciones concurrentes terminan sus hints fuera de orden. Las filas reales mandan.
        for (String key : store.all().keySet()) {
            if (SEQ_KEY.equals(key)) continue;
            try {
                start = Math.max(start, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // claves corruptas/no numericas no forman parte de la secuencia
            }
        }
        return Math.max(0, start);
    }

    public Report create(String reporter, String reporterUuid, String target, String reason) {
        return create(reporter, reporterUuid, target, reason, null);
    }

    public Report create(String reporter, String reporterUuid, String target, String reason, Location location) {
        return create(reporter, reporterUuid, target, reason, location, "", target);
    }

    public Report create(String reporter, String reporterUuid, String target, String reason,
                         Location location, String targetUuid, String targetDisplay) {
        // Con base de datos reservamos el id de forma atomica en la DB (unico aunque varios
        // servers compartan la MySQL). Sin ella, contador local (single-server, sin colision).
        int dbId = storage != null ? storage.nextId("reports", seq.get()) : 0;
        int id = dbId > 0 ? dbId : seq.updateAndGet(current -> {
            if (current == Integer.MAX_VALUE) {
                throw new IllegalStateException("report id sequence exhausted");
            }
            return current + 1;
        });
        // No separar get/set: una creacion lenta podria escribir un maximo obsoleto despues
        // de que otro hilo ya avanzara el contador y permitir que se reutilizara un ID.
        seq.accumulateAndGet(id, Math::max);
        Report r = new Report(id, reporter, reporterUuid, target,
                dev.chatalyst.util.Sanitize.limit(reason == null ? "" : reason,
                        MAX_REASON_LENGTH),
                System.currentTimeMillis(), Status.OPEN, "", "", false,
                location != null && location.getWorld() != null ? location.getWorld().getName() : "",
                location != null ? location.getX() : 0.0D,
                location != null ? location.getY() : 0.0D,
                location != null ? location.getZ() : 0.0D,
                targetUuid == null ? "" : targetUuid,
                targetDisplay == null || targetDisplay.isBlank() ? target : targetDisplay);
        if (!store.compareAndPutDurably(String.valueOf(id), null, serialize(r))) {
            throw new IllegalStateException("could not durably persist report #" + id);
        }
        // Hint monotono: una creacion lenta de #41 nunca puede sobrescribir el #42. El
        // reporte ya contiene el ID y el arranque reconstruye el maximo, por lo que un
        // fallo aislado de este hint tampoco invalida el reporte confirmado.
        dev.chatalyst.db.StoreCas.updateDurably(store, SEQ_KEY, raw -> {
            int current = 0;
            try {
                if (raw != null) current = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                // dato corrupto: el id confirmado actual lo reemplaza
            }
            return String.valueOf(Math.max(current, id));
        });
        return r;
    }

    public Report get(int id) {
        String raw = store.get(String.valueOf(id));
        return raw == null ? null : deserialize(id, raw);
    }

    /** Reportes sin cerrar (OPEN + CLAIMED + PENDING), del mas nuevo al mas viejo. */
    public List<Report> listOpen() {
        List<Report> out = new ArrayList<>();
        for (Report r : all()) {
            if (!r.status().isClosed()) out.add(r);
        }
        out.sort(Comparator.comparingInt(Report::id).reversed());
        return out;
    }

    public List<Report> all() {
        List<Report> out = new ArrayList<>();
        for (var e : store.all().entrySet()) {
            if (e.getKey().equals(SEQ_KEY)) continue;
            try {
                out.add(deserialize(Integer.parseInt(e.getKey()), e.getValue()));
            } catch (NumberFormatException ignore) {
                // clave no numerica: la saltamos
            }
        }
        return out;
    }

    /**
     * Cambia el estado de un reporte. Devuelve el reporte actualizado, o null si no existe
     * o ya estaba cerrado (resuelto/rechazado no se reabren aqui).
     *
     * <p>La transicion se aplica con CAS contra la base de datos: si dos staff (incluso en
     * servers distintos de la red) intentan reclamar el mismo reporte a la vez, solo uno
     * gana; el otro ve el estado real y recibe null.
     */
    public Report setStatus(int id, Status status, String handler, String note) {
        return mutate(id, r -> {
            if (r.status().isClosed()) return null;
            return new Report(r.id(), r.reporter(), r.reporterUuid(), r.target(), r.reason(),
                    r.time(), status, handler,
                    note != null ? dev.chatalyst.util.Sanitize.limit(note, MAX_NOTE_LENGTH)
                            : r.note(), r.priority(),
                    r.world(), r.x(), r.y(), r.z(), r.targetUuid(), r.targetDisplay());
        });
    }

    /**
     * Read-modify-write con CAS y reintentos. La funcion recibe el reporte actual y devuelve
     * el actualizado (o null si la transicion ya no aplica). Con un CAS fallido, el store
     * refresca el valor real y se reintenta sobre datos frescos.
     */
    private Report mutate(int id, java.util.function.UnaryOperator<Report> fn) {
        String key = String.valueOf(id);
        for (int attempt = 0; attempt < 5; attempt++) {
            String raw = store.get(key);
            if (raw == null) return null;
            Report updated = fn.apply(deserialize(id, raw));
            if (updated == null) return null;
            if (store.compareAndPutDurably(key, raw, serialize(updated))) return updated;
        }
        return null;
    }

    public boolean claim(int id, String handler) {
        return setStatus(id, Status.CLAIMED, handler, null) != null;
    }

    public boolean resolve(int id, String handler, String note) {
        return setStatus(id, Status.RESOLVED, handler, note == null ? "" : note) != null;
    }

    /** Reabre un reporte cerrado (lo "regresa"). Devuelve el reporte, o null si no aplica. */
    public Report reopen(int id, String handler) {
        return mutate(id, r -> {
            if (!r.status().isClosed()) return null;
            return new Report(r.id(), r.reporter(), r.reporterUuid(), r.target(), r.reason(),
                    r.time(), Status.OPEN, handler, r.note(), r.priority(),
                    r.world(), r.x(), r.y(), r.z(), r.targetUuid(), r.targetDisplay());
        });
    }

    /** Marca o quita la prioridad (seccion de tickets criticos). Devuelve el reporte o null. */
    public Report setPriority(int id, boolean priority) {
        return mutate(id, r -> new Report(r.id(), r.reporter(), r.reporterUuid(), r.target(),
                r.reason(), r.time(), r.status(), r.handler(), r.note(), priority,
                r.world(), r.x(), r.y(), r.z(), r.targetUuid(), r.targetDisplay()));
    }

    /** Borra un reporte por completo. True si existia. */
    public boolean delete(int id) {
        String key = String.valueOf(id);
        String observed = store.get(key);
        return observed != null && store.compareAndRemoveDurably(key, observed);
    }

    public void shutdown() {
        store.flushNow();
    }

    /** UUID autoritativo; nombre solo para reportes legacy que nunca guardaron UUID. */
    public static boolean ownedBy(Report report, UUID playerId, String playerName) {
        if (report == null || playerId == null) return false;
        if (report.reporterUuid() != null && !report.reporterUuid().isBlank()) {
            try {
                return playerId.equals(UUID.fromString(report.reporterUuid()));
            } catch (IllegalArgumentException corrupt) {
                return false;
            }
        }
        return playerName != null && report.reporter().equalsIgnoreCase(playerName);
    }

    // ---- Serializacion --------------------------------------------------------

    private static String serialize(Report r) {
        return clean(r.reporter()) + US + clean(r.reporterUuid()) + US + clean(r.target()) + US
                + clean(r.reason()) + US + r.time() + US + r.status().name() + US
                + clean(r.handler()) + US + clean(r.note()) + US + r.priority() + US
                + clean(r.world()) + US + r.x() + US + r.y() + US + r.z() + US
                + clean(r.targetUuid()) + US + clean(r.targetDisplay());
    }

    private static Report deserialize(int id, String raw) {
        String[] f = raw.split(String.valueOf(US), -1);
        long time = 0;
        Status status = Status.OPEN;
        if (f.length > 4) {
            try {
                time = Long.parseLong(f[4]);
            } catch (NumberFormatException ignore) {
            }
        }
        if (f.length > 5) {
            try {
                status = Status.valueOf(f[5]);
            } catch (IllegalArgumentException ignore) {
            }
        }
        return new Report(id,
                f.length > 0 ? f[0] : "?",   // reporter
                f.length > 1 ? f[1] : "",    // reporterUuid
                f.length > 2 ? f[2] : "?",   // target
                f.length > 3 ? f[3] : "",    // reason
                time, status,
                f.length > 6 ? f[6] : "",    // handler
                f.length > 7 ? f[7] : "",    // note
                f.length > 8 && Boolean.parseBoolean(f[8]), // priority (reportes viejos: false)
                f.length > 9 ? f[9] : "",
                parseDouble(f, 10),
                parseDouble(f, 11),
                parseDouble(f, 12),
                f.length > 13 ? f[13] : "",
                f.length > 14 && !f[14].isBlank() ? f[14] : (f.length > 2 ? f[2] : "?"));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(US, ' ');
    }

    private static double parseDouble(String[] fields, int index) {
        if (fields.length <= index) return 0.0D;
        try {
            double value = Double.parseDouble(fields[index]);
            return Double.isFinite(value) ? value : 0.0D;
        } catch (NumberFormatException ex) {
            return 0.0D;
        }
    }
}
