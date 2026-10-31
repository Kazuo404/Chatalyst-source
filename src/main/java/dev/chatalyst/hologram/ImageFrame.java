package dev.chatalyst.hologram;

/**
 * Un fotograma de imagen ya descodificado: pixeles ARGB por filas (de arriba abajo) y cuanto
 * dura en pantalla. Una imagen fija es un solo fotograma con retardo 0. Sin Bukkit.
 *
 * @param delayMs milisegundos que dura este fotograma en un GIF (0 en una imagen fija)
 */
public record ImageFrame(int width, int height, int[] argb, int delayMs) {

    public ImageFrame {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("empty image");
        if (argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("pixel count does not match the size");
        }
        delayMs = Math.max(0, delayMs);
    }

    public int pixel(int x, int y) {
        return argb[y * width + x];
    }

    /** Transparente a efectos de pintar: menos de la mitad de opacidad. */
    public static boolean transparent(int argb) {
        return (argb >>> 24) < 128;
    }
}
