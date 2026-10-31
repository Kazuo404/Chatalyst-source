package dev.chatalyst.staff.autoclick;

/**
 * Decide que balanceos de brazo son clics de verdad. Puro y sin hilos: lo protege quien lo use.
 *
 * <p>El cliente balancea el brazo en mas sitios que el clic izquierdo, y dos de ellos tienen
 * un ritmo perfecto sin que haya trampa:
 * <ul>
 *   <li><b>Picar manteniendo pulsado.</b> Mientras rompe un bloque (y durante los 5 ticks de
 *       espera entre bloque y bloque) el cliente manda un balanceo POR TICK, cada 50 ms
 *       clavados. Solo el balanceo que abre una pulsacion nueva cuenta; los que llegan
 *       encadenados a menos de {@link #HOLD_GAP_NANOS} del anterior son el boton sostenido.
 *       Un bloque nuevo que empieza dentro de esa cadena (minar en tira, haste + eficiencia)
 *       tampoco abre pulsacion: el dedo no se ha levantado.</li>
 *   <li><b>Clic derecho y soltar objetos.</b> Colocar bloques manteniendo el derecho o tirar
 *       con Q tambien balancea el brazo cada 4 ticks. El balanceo que sigue a un uso o a un
 *       soltar se descarta.</li>
 * </ul>
 * Cancelar el picado (soltar el boton a medias) corta la cadena: un autoclick apuntando a un
 * bloque manda inicio + cancelacion en cada clic, y cada uno cuenta.
 */
public final class ClickFilter {

    /** Un tick (50 ms) mas margen de sobra para el jitter de red entre balanceos sostenidos. */
    static final long HOLD_GAP_NANOS = 150_000_000L;
    /** El balanceo de un uso llega en el mismo paquete/tick que el uso: con 60 ms sobra. */
    static final long USE_GRACE_NANOS = 60_000_000L;

    private static final long NONE = Long.MIN_VALUE;

    private boolean pressPending;
    private long holdUntil = NONE;
    private long useUntil = NONE;

    /** Empieza a picar un bloque (paquete START_DIGGING o BlockDamageEvent). */
    public void digStart(long nanos) {
        boolean chained = holdUntil != NONE && nanos <= holdUntil;
        pressPending = !chained;
        holdUntil = nanos + HOLD_GAP_NANOS;
    }

    /** Deja de picar: {@code finished} si rompio el bloque, si no lo solto a medias. */
    public void digStop(long nanos, boolean finished) {
        pressPending = false;
        // Tras romperlo, el cliente sigue balanceando cada tick mientras se mantenga el boton.
        holdUntil = finished ? nanos + HOLD_GAP_NANOS : NONE;
    }

    /** Clic derecho, interaccion con entidad o soltar un objeto. */
    public void use(long nanos) {
        useUntil = nanos + USE_GRACE_NANOS;
    }

    /** @return true si el balanceo es un clic izquierdo que debe contarse */
    public boolean swing(long nanos) {
        if (useUntil != NONE && nanos <= useUntil) {
            useUntil = NONE;
            return false;
        }
        if (pressPending) {
            pressPending = false;
            return true;
        }
        if (holdUntil != NONE && nanos <= holdUntil) {
            holdUntil = nanos + HOLD_GAP_NANOS;
            return false;
        }
        return true;
    }
}
