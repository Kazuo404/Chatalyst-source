package dev.chatalyst.staff.tools;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;

/**
 * Numeros de castigo de toda la red: #1, #2, #3... como los IDs.
 *
 * <p>El contador vive en el Store compartido (MySQL en produccion), asi que Lobby y Survival
 * reparten numeros de la MISMA secuencia y un "#1532" dicho en el canal de apelaciones
 * identifica un solo castigo en toda la red. Se incrementa con CAS: dos castigos a la vez en
 * dos servidores nunca se llevan el mismo numero.
 *
 * <p>Un fallo de almacenamiento devuelve {@link #NONE}: el castigo se aplica igual, solo que
 * sin numero. Bloquear un ban porque no se pudo numerar seria peor que un ban sin numero.
 */
public final class PunishmentIds {

    /** Sin numero asignado (registros anteriores a los IDs, o fallo de almacenamiento). */
    public static final long NONE = 0L;

    private static final String KEY = "seq";

    private final Store store;

    public PunishmentIds(Store store) {
        this.store = store;
    }

    /** Siguiente numero libre de la red, o {@link #NONE} si el almacen no respondio. */
    public long next() {
        if (store == null) return NONE;
        long[] assigned = {NONE};
        boolean saved = StoreCas.updateDurably(store, KEY, raw -> {
            long next = parse(raw) + 1;
            assigned[0] = next;
            return Long.toString(next);
        });
        return saved ? assigned[0] : NONE;
    }

    static long parse(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException corrupt) {
            // Un contador ilegible no puede reiniciar la secuencia a 1 (repetiria numeros):
            // se salta a un valor alto y reconocible para que se note en el log.
            return 1_000_000L;
        }
    }

    /** "#1532", o "" si no hay numero: los mensajes lo insertan tal cual. */
    public static String label(long id) {
        return id > NONE ? "#" + id : "";
    }
}
