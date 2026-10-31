package dev.chatalyst.staff.autoclick;

import java.util.Arrays;

/**
 * La matematica del detector, sin Bukkit: estadisticas de los intervalos entre clics y las
 * tres senales. Todo es O(ventana) y solo se calcula cuando toca evaluar (una vez por segundo),
 * nunca por clic.
 */
public final class ClickAnalysis {

    /** Fraccion recortada por cada extremo: un paquete perdido no debe salvar a un robot. */
    static final double TRIM = 0.05;
    private static final long SECOND = 1_000_000_000L;

    private ClickAnalysis() {
    }

    public enum Signal {
        /** Muchos clics seguidos, durante mucho rato, con intervalos casi identicos. */
        REGULARITY,
        /** CPS medio por encima del maximo durante varios segundos seguidos. */
        SPEED,
        /** Regularidad algo mas laxa pero ininterrumpida durante muchos minutos. */
        LONG_REGULARITY
    }

    /**
     * Estadisticas de una serie de intervalos (media y desviacion recortadas).
     *
     * @param deviation desviacion tipica / media (coeficiente de variacion)
     * @param distinct  intervalos distintos redondeados a 1 ms (sin recortar)
     */
    public record Stats(int intervals, double meanMillis, double stdMillis, double deviation,
                        int distinct) {

        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);

        public double cps() {
            return meanMillis <= 0 ? 0 : 1000.0 / meanMillis;
        }
    }

    /** Lo que salto: la senal, el CPS que la explica, la regularidad y cuanto dura la racha. */
    public record Verdict(Signal signal, double cps, Stats stats, long streakMillis) {
    }

    /** Foto para la vista en vivo de /autoclick: CPS del ultimo segundo y regularidad actual. */
    public record Live(int lastSecond, Stats stats, long streakMillis, int streakClicks) {
    }

    public static Stats stats(double[] intervals) {
        int n = intervals.length;
        if (n == 0) return Stats.EMPTY;
        double[] sorted = intervals.clone();
        Arrays.sort(sorted);
        int distinct = 1;
        long previous = Math.round(sorted[0]);
        for (int i = 1; i < n; i++) {
            long rounded = Math.round(sorted[i]);
            if (rounded != previous) {
                distinct++;
                previous = rounded;
            }
        }
        // Con pocas muestras no se recorta nada: cada una pesa demasiado para tirarla.
        int cut = n >= 20 ? (int) Math.floor(n * TRIM) : 0;
        int from = cut;
        int to = n - cut;
        double sum = 0;
        for (int i = from; i < to; i++) sum += sorted[i];
        int kept = to - from;
        double mean = sum / kept;
        double squares = 0;
        for (int i = from; i < to; i++) {
            double d = sorted[i] - mean;
            squares += d * d;
        }
        double std = kept > 1 ? Math.sqrt(squares / (kept - 1)) : 0;
        return new Stats(n, mean, std, mean > 0 ? std / mean : 0, distinct);
    }

    /**
     * Evalua la serie y devuelve la senal mas fuerte que salte, o null. Actualiza el estado de
     * la senal larga dentro de {@code series}, asi que debe llamarse con su cerrojo tomado.
     *
     * @param highResolution las marcas son de llegada del paquete (resolucion ~1 ms); con eventos
     *                       de Bukkit o jugadores de Bedrock el recuento de distintos no vale
     */
    public static Verdict evaluate(ClickSeries series, Thresholds t, long now,
                                   boolean highResolution) {
        if (!series.streakAlive(now)) {
            series.regularSince(ClickSeries.NONE);
            return null;
        }
        long streak = series.last() - series.streakStart();
        Stats stats = null;
        if (series.streakClicks() >= t.minClicks()) {
            stats = stats(series.streakIntervalsMillis(t.window()));
            if (streak >= t.minDurationMillis() * 1_000_000L && robotic(stats, t, highResolution)) {
                return new Verdict(Signal.REGULARITY, stats.cps(), stats, streak / 1_000_000L);
            }
        }
        double cps = sustainedCps(series, t, now);
        if (cps > 0) {
            Stats shown = stats != null ? stats : stats(series.streakIntervalsMillis(t.window()));
            return new Verdict(Signal.SPEED, cps, shown, streak / 1_000_000L);
        }
        if (t.longMillis() > 0 && stats != null) {
            if (stats.deviation() <= t.longMaxDeviation() && stats.stdMillis() <= t.longMaxStdMillis()) {
                if (series.regularSince() == ClickSeries.NONE) {
                    series.regularSince(series.windowStart(t.window()));
                }
                long regular = series.last() - series.regularSince();
                if (regular >= t.longMillis() * 1_000_000L) {
                    return new Verdict(Signal.LONG_REGULARITY, stats.cps(), stats,
                            regular / 1_000_000L);
                }
            } else {
                series.regularSince(ClickSeries.NONE);
            }
        }
        return null;
    }

    static boolean robotic(Stats stats, Thresholds t, boolean highResolution) {
        if (stats.intervals() < 2) return false;
        if (stats.deviation() <= t.maxDeviation() && stats.stdMillis() <= t.maxStdMillis()) {
            return true;
        }
        // Un reloj fijo detras de una buena conexion repite casi el mismo intervalo al ms.
        return highResolution && t.maxDistinct() > 0 && stats.intervals() >= t.minClicks() - 1
                && stats.distinct() <= t.maxDistinct();
    }

    /**
     * CPS medio de los ultimos {@code speedSeconds} segundos si supera el maximo y ademas
     * ninguno de esos segundos baja de la mitad del maximo (sostenido, no una rafaga). 0 si no.
     */
    static double sustainedCps(ClickSeries series, Thresholds t, long now) {
        int seconds = t.speedSeconds();
        if (seconds <= 0 || t.maxCps() <= 0) return 0;
        if (now - series.streakStart() < seconds * SECOND) return 0;
        int total = 0;
        for (int k = 0; k < seconds; k++) {
            int inSecond = series.countBetween(now - (k + 1) * SECOND, now - k * SECOND);
            if (inSecond < t.maxCps() / 2.0) return 0;
            total += inSecond;
        }
        double cps = (double) total / seconds;
        return cps >= t.maxCps() ? cps : 0;
    }

    public static Live live(ClickSeries series, Thresholds t, long now) {
        int lastSecond = series.countBetween(now - SECOND, now + 1);
        if (!series.streakAlive(now)) return new Live(lastSecond, Stats.EMPTY, 0, 0);
        return new Live(lastSecond, stats(series.streakIntervalsMillis(t.window())),
                (series.last() - series.streakStart()) / 1_000_000L, series.streakClicks());
    }
}
