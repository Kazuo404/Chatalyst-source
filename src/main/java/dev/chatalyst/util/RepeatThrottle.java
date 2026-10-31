package dev.chatalyst.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agrupa repeticiones de la MISMA accion sobre el MISMO objetivo dentro de una ventana:
 * abrir un cofre veinte veces seguidas ordenando el almacen genera una sola linea, no
 * veinte. Al expirar la ventana, la siguiente linea indica cuantas se ocultaron
 * ("+19 repetidas"), asi que no se pierde la informacion, solo el ruido.
 *
 * <p>Vivia dentro de {@code containerlog}. Se movio aqui al aparecer el segundo cliente
 * ({@code combatlog}, que agrupa los golpes de una misma pelea): la regla es la misma y no
 * tiene nada que ver con cofres.
 */
public final class RepeatThrottle {

    /** Estado por clave: inicio de la ventana vigente y repeticiones ocultadas en ella. */
    private record State(long anchor, int suppressed) {
    }

    private static final int CLEAN_THRESHOLD = 4096;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * Registra una ocurrencia.
     *
     * @return {@code -1} si hay que SILENCIARLA (repeticion dentro de la ventana), o el
     *         numero de repeticiones que quedaron ocultas desde la ultima linea mostrada
     *         (0 = nada que anunciar) si toca mostrarla.
     */
    public int register(String key, long windowMillis) {
        long now = System.currentTimeMillis();
        if (states.size() > CLEAN_THRESHOLD) {
            states.entrySet().removeIf(e -> now - e.getValue().anchor() >= windowMillis);
        }
        int[] result = {0};
        states.compute(key, (ignored, state) -> {
            if (state == null || now - state.anchor() >= windowMillis) {
                // Ventana nueva: se muestra, arrastrando el recuento de la anterior.
                result[0] = state == null ? 0 : state.suppressed();
                return new State(now, 0);
            }
            // Dentro de la ventana: se calla y se cuenta. El ancla NO se mueve, para que
            // una repeticion constante no silencie la linea para siempre.
            result[0] = -1;
            return new State(state.anchor(), state.suppressed() + 1);
        });
        return result[0];
    }
}
