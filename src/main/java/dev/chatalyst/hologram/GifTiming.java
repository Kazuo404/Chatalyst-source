package dev.chatalyst.hologram;

/**
 * Temporizacion de un GIF: que fotograma toca en cada momento. Sin Bukkit.
 *
 * <p>Los retardos de 0 o 10 ms se tratan como 100 ms, que es lo que hacen los navegadores:
 * muchos GIF los traen asi esperando ese comportamiento, y tomarlos al pie de la letra seria
 * una animacion a velocidad imposible.
 */
public final class GifTiming {

    /** Por debajo de esto el retardo se considera "sin especificar". */
    static final int MIN_REAL_DELAY_MS = 20;
    static final int DEFAULT_DELAY_MS = 100;

    private GifTiming() {
    }

    public static int[] normalize(int[] delays) {
        int[] out = new int[delays.length];
        for (int i = 0; i < delays.length; i++) {
            out[i] = delays[i] < MIN_REAL_DELAY_MS ? DEFAULT_DELAY_MS : delays[i];
        }
        return out;
    }

    public static long total(int[] delays) {
        long sum = 0;
        for (int delay : delays) sum += Math.max(1, delay);
        return sum;
    }

    /** Fotograma que se ve {@code elapsedMs} despues de empezar (la animacion va en bucle). */
    public static int frameAt(long elapsedMs, int[] delays) {
        if (delays == null || delays.length <= 1) return 0;
        long total = total(delays);
        long t = Math.floorMod(elapsedMs, total);
        for (int i = 0; i < delays.length; i++) {
            t -= Math.max(1, delays[i]);
            if (t < 0) return i;
        }
        return delays.length - 1;
    }

    /**
     * Como {@link #frameAt}, pero sin pasar de {@code maxFps} cambios por segundo: el tiempo se
     * redondea hacia abajo al paso minimo. Un GIF de 50 fps en un mural seria reenviar mapas
     * enteros cincuenta veces por segundo a cada jugador.
     */
    public static int frameAt(long elapsedMs, int[] delays, int maxFps) {
        if (maxFps <= 0) return frameAt(elapsedMs, delays);
        long step = Math.max(1, 1000L / maxFps);
        return frameAt(Math.floorDiv(elapsedMs, step) * step, delays);
    }
}
