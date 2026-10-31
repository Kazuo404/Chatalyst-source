package dev.chatalyst.itemwatch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuanto ha ganado cada jugador de cada regla en una ventana de tiempo.
 *
 * <p>Se guarda una muestra por escaneo y la ganancia es lo que tiene AHORA menos lo minimo que
 * tuvo dentro de la ventana. Asi gastar y volver a juntar no suma dos veces, y sacar del cofre
 * lo que ya era suyo si cuenta: el aviso lo enseña tal cual y es el staff quien decide.
 */
public final class GainTracker {

    private record Sample(long time, long amount) {
    }

    /** Una muestra cada minuto durante una hora es de sobra para ventanas de minutos. */
    private static final int MAX_SAMPLES = 120;

    private final Map<UUID, Map<String, Deque<Sample>>> samples = new ConcurrentHashMap<>();

    /**
     * Apunta la cantidad actual y devuelve cuanto ha subido dentro de {@code windowMillis}
     * (0 si ha bajado o no hay historial).
     */
    public long record(UUID player, String ruleId, long amount, long now, long windowMillis) {
        Deque<Sample> history = samples
                .computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(ruleId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst().time() > windowMillis) {
                history.pollFirst();
            }
            long lowest = amount;
            for (Sample sample : history) lowest = Math.min(lowest, sample.amount());
            history.addLast(new Sample(now, amount));
            while (history.size() > MAX_SAMPLES) history.pollFirst();
            return Math.max(0L, amount - lowest);
        }
    }

    /** Al salir: su historial no vale para la proxima sesion (puede haber vaciado el inventario). */
    public void forget(UUID player) {
        samples.remove(player);
    }

    public void clear() {
        samples.clear();
    }
}
