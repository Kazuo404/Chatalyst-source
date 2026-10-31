package dev.chatalyst.staff;

import dev.chatalyst.staff.lookup.SeenList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Que datos de conexion han pasado su plazo de conservacion (logica pura).
 *
 * <p>Las direcciones IP son datos personales y este almacen crece con cada jugador nuevo: sin un
 * plazo, ni el servidor adelgaza ni el dueño puede decir cuanto las guarda. Una fila sin fecha viene
 * del esquema antiguo y no se puede juzgar, asi que se conserva. El indice de nombres no lleva
 * direcciones y se queda: sirve para sancionar por nombre a quien lleva tiempo sin entrar.
 */
final class AltRetention {

    private static final String ACCOUNT = "acc:";
    private static final String CONNECTION = "conn:";
    private static final String NAMES = "names:";

    /** Filas que se reescriben y claves que desaparecen. */
    record Plan(Map<String, String> updates, Set<String> removals) {
        boolean isEmpty() {
            return updates.isEmpty() && removals.isEmpty();
        }
    }

    private AltRetention() {
    }

    static Plan plan(Map<String, String> all, long cutoffMillis) {
        Map<String, String> updates = new HashMap<>();
        Set<String> removals = new HashSet<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(ACCOUNT) && !key.startsWith(CONNECTION) && !key.startsWith(NAMES)) continue;
            List<SeenList.Seen> rows = SeenList.decode(entry.getValue());
            List<SeenList.Seen> kept = new ArrayList<>(rows.size());
            for (SeenList.Seen row : rows) {
                if (row.last() <= 0 || row.last() >= cutoffMillis) kept.add(row);
            }
            if (kept.size() == rows.size()) continue;
            if (!kept.isEmpty()) {
                updates.put(key, SeenList.encode(kept));
                continue;
            }
            removals.add(key);
            if (key.startsWith(CONNECTION)) {
                String hash = key.substring(CONNECTION.length());
                removals.add("ip:" + hash);
                removals.add("addr:" + hash);
            } else if (key.startsWith(ACCOUNT)) {
                removals.add("last:" + key.substring(ACCOUNT.length()));
            }
        }
        removals.retainAll(all.keySet());
        return new Plan(updates, removals);
    }
}
