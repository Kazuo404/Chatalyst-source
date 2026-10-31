package dev.chatalyst.staff.autoclick;

/**
 * Umbrales de deteccion ya validados (Java o Bedrock). Puro: lo usan los tests sin servidor.
 *
 * @param minClicks          clics minimos de la racha antes de juzgar su regularidad
 * @param minDurationMillis  duracion minima de la racha (clic sostenido) para la regularidad
 * @param window             intervalos mas recientes sobre los que se mide la regularidad
 * @param maxDeviation       desviacion tipica / media por debajo de la cual es "robotico"
 * @param maxStdMillis       desviacion tipica absoluta maxima (ms) para ese mismo veredicto
 * @param maxDistinct        intervalos distintos (redondeados a 1 ms) como maximo; 0 = apagado
 * @param maxCps             CPS medio que no se puede sostener a mano
 * @param speedSeconds       segundos seguidos por encima de {@code maxCps} para avisar
 * @param longMillis         duracion de la regularidad "larga" (granja AFK); 0 = apagado
 * @param longMaxDeviation   desviacion relativa maxima de la senal larga (mas laxa)
 * @param longMaxStdMillis   desviacion tipica absoluta maxima de la senal larga
 */
public record Thresholds(int minClicks, long minDurationMillis, int window,
                         double maxDeviation, double maxStdMillis, int maxDistinct,
                         double maxCps, int speedSeconds,
                         long longMillis, double longMaxDeviation, double longMaxStdMillis) {

    /** Valores de fabrica: los mismos que trae moderation.yml. */
    public static Thresholds defaults() {
        return new Thresholds(40, 15_000L, 100, 0.08, 12.0, 5, 16.0, 5,
                10 * 60_000L, 0.12, 25.0);
    }

    /** Clics que debe poder guardar la ventana circular para evaluar todas las senales. */
    public int requiredCapacity() {
        int speed = (int) Math.ceil(maxCps * speedSeconds * 2.0);
        return Math.min(2_000, Math.max(Math.max(window + 1, minClicks), speed));
    }
}
