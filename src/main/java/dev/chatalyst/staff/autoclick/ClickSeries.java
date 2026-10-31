package dev.chatalyst.staff.autoclick;

/**
 * Ultimos clics de un jugador en una ventana circular de tamano fijo, mas la "racha" actual:
 * clics seguidos sin un hueco mayor que {@code maxGap}. Puro y sin hilos.
 *
 * <p>La memoria no crece con el tiempo: diez minutos de granja AFK ocupan lo mismo que diez
 * segundos. Lo que necesita mas historia que la ventana (cuanto dura la racha, desde cuando es
 * regular) se guarda como un par de numeros, no como clics.
 */
public final class ClickSeries {

    static final long NONE = Long.MIN_VALUE;

    private final long[] times;
    private final long maxGapNanos;
    private int head;
    private int size;
    private long streakStart = NONE;
    private int streakClicks;
    /** Desde cuando la racha es regular sin interrupcion (senal larga), o NONE. */
    private long regularSince = NONE;

    public ClickSeries(int capacity, long maxGapNanos) {
        if (capacity < 2) throw new IllegalArgumentException("capacity < 2");
        this.times = new long[capacity];
        this.maxGapNanos = Math.max(1L, maxGapNanos);
    }

    public void add(long nanos) {
        long t = nanos;
        if (size > 0) {
            long last = last();
            if (t < last) t = last; // relojes de hilos distintos: nunca un intervalo negativo
            if (t - last > maxGapNanos) breakStreak(t);
            else streakClicks++;
        } else {
            breakStreak(t);
        }
        times[head] = t;
        head = (head + 1) % times.length;
        if (size < times.length) size++;
    }

    private void breakStreak(long start) {
        streakStart = start;
        streakClicks = 1;
        regularSince = NONE;
    }

    public int capacity() {
        return times.length;
    }

    public int size() {
        return size;
    }

    public long last() {
        return size == 0 ? NONE : times[(head - 1 + times.length) % times.length];
    }

    /** i = 0 es el clic mas reciente. */
    private long fromNewest(int i) {
        return times[(head - 1 - i + times.length * 2) % times.length];
    }

    /** La racha sigue viva si el ultimo clic no queda mas lejos que el hueco maximo. */
    public boolean streakAlive(long now) {
        return size > 0 && now - last() <= maxGapNanos;
    }

    public long streakStart() {
        return streakStart;
    }

    public int streakClicks() {
        return streakClicks;
    }

    /** Duracion de la racha viva hasta su ultimo clic, o 0. */
    public long streakNanos(long now) {
        return streakAlive(now) ? last() - streakStart : 0L;
    }

    long regularSince() {
        return regularSince;
    }

    void regularSince(long value) {
        regularSince = value;
    }

    /** Clics con marca en [from, to). */
    public int countBetween(long from, long to) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long t = fromNewest(i);
            if (t < from) break;
            if (t < to) count++;
        }
        return count;
    }

    /**
     * Hasta {@code max} intervalos (ms) de la racha actual, del mas viejo al mas nuevo. Solo
     * los que caben en la ventana: la racha puede ser mas larga que lo que se recuerda.
     */
    public double[] streakIntervalsMillis(int max) {
        int clicks = Math.min(Math.min(size, streakClicks), max + 1);
        if (clicks < 2) return new double[0];
        double[] out = new double[clicks - 1];
        for (int i = 0; i < clicks - 1; i++) {
            long newer = fromNewest(i);
            long older = fromNewest(i + 1);
            out[clicks - 2 - i] = (newer - older) / 1_000_000.0;
        }
        return out;
    }

    /** Marca del clic mas viejo que entra en {@link #streakIntervalsMillis(int)} con ese max. */
    public long windowStart(int max) {
        int clicks = Math.min(Math.min(size, streakClicks), max + 1);
        return clicks == 0 ? NONE : fromNewest(clicks - 1);
    }
}
