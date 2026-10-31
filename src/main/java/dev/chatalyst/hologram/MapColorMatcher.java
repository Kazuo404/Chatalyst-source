package dev.chatalyst.hologram;

/**
 * Color RGB → indice de la paleta de los mapas de Minecraft, con dithering Floyd-Steinberg
 * opcional. Sin Bukkit: la paleta se le pasa (en el servidor sale de {@code MapPalette}, que
 * es la autoridad; en los tests, una paleta de prueba).
 *
 * <p>Buscar el color mas cercano entre ~240 es caro para un mural de un millon de pixeles, asi
 * que se precalcula una tabla de 32.768 entradas (5 bits por canal). La distancia es la
 * "redmean", una aproximacion barata a como ve el ojo: el rojo y el azul pesan segun el tono.
 */
public final class MapColorMatcher {

    /** Indice de mapa para "transparente" (no se pinta nada). */
    public static final byte TRANSPARENT = 0;

    private final int[] indices;
    private final int[] rgb;
    private final byte[] table = new byte[1 << 15];

    /**
     * @param palette RGB de cada indice de la paleta (posicion = indice sin signo); un valor
     *                negativo marca un indice que no se usa (los transparentes 0-3)
     */
    public MapColorMatcher(int[] palette) {
        int count = 0;
        for (int color : palette) if (color >= 0) count++;
        if (count == 0) throw new IllegalArgumentException("empty palette");
        indices = new int[count];
        rgb = new int[count];
        int n = 0;
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] < 0) continue;
            indices[n] = i;
            rgb[n] = palette[i] & 0xFFFFFF;
            n++;
        }
        for (int key = 0; key < table.length; key++) {
            int r = ((key >> 10) & 31) * 255 / 31;
            int g = ((key >> 5) & 31) * 255 / 31;
            int b = (key & 31) * 255 / 31;
            table[key] = (byte) indices[nearestSlot(r, g, b)];
        }
    }

    private int nearestSlot(int r, int g, int b) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < rgb.length; i++) {
            long d = distance(r, g, b, rgb[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** Distancia "redmean" al cuadrado (entera). */
    static long distance(int r, int g, int b, int color) {
        int cr = (color >> 16) & 0xFF;
        int cg = (color >> 8) & 0xFF;
        int cb = color & 0xFF;
        long rMean = (r + cr) / 2;
        long dr = r - cr;
        long dg = g - cg;
        long db = b - cb;
        return ((512 + rMean) * dr * dr >> 8) + 4 * dg * dg + ((767 - rMean) * db * db >> 8);
    }

    /** Indice de mapa del color mas parecido. */
    public byte nearest(int r, int g, int b) {
        return table[((clamp(r) >> 3) << 10) | ((clamp(g) >> 3) << 5) | (clamp(b) >> 3)];
    }

    /** RGB del indice de mapa (para medir el error del dithering). */
    int rgbOf(byte index) {
        int unsigned = index & 0xFF;
        for (int i = 0; i < indices.length; i++) if (indices[i] == unsigned) return rgb[i];
        return 0;
    }

    /**
     * Fotograma entero a indices de mapa (por filas). Lo transparente queda transparente y no
     * reparte error: si no, el dithering "manchaba" los bordes de un logo.
     */
    public byte[] convert(ImageFrame frame, boolean dither) {
        int w = frame.width();
        int h = frame.height();
        byte[] out = new byte[w * h];
        int[] lookup = dither ? rgbByIndex() : null;
        // Error acumulado de la fila actual y la siguiente, por canal (con margen a los lados).
        int[][] current = new int[3][w + 2];
        int[][] next = new int[3][w + 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = frame.argb()[y * w + x];
                if (ImageFrame.transparent(p)) {
                    out[y * w + x] = TRANSPARENT;
                    continue;
                }
                int r = ((p >> 16) & 0xFF);
                int g = ((p >> 8) & 0xFF);
                int b = (p & 0xFF);
                if (dither) {
                    r = clamp(r + current[0][x + 1] / 16);
                    g = clamp(g + current[1][x + 1] / 16);
                    b = clamp(b + current[2][x + 1] / 16);
                }
                byte index = nearest(r, g, b);
                out[y * w + x] = index;
                if (!dither) continue;
                int chosen = lookup[index & 0xFF];
                int[] error = {r - ((chosen >> 16) & 0xFF), g - ((chosen >> 8) & 0xFF),
                        b - (chosen & 0xFF)};
                for (int c = 0; c < 3; c++) {
                    current[c][x + 2] += error[c] * 7;
                    next[c][x] += error[c] * 3;
                    next[c][x + 1] += error[c] * 5;
                    next[c][x + 2] += error[c];
                }
            }
            int[][] swap = current;
            current = next;
            next = swap;
            for (int[] row : next) java.util.Arrays.fill(row, 0);
        }
        return out;
    }

    private int[] rgbByIndex() {
        int[] out = new int[256];
        for (int i = 0; i < indices.length; i++) out[indices[i]] = rgb[i];
        return out;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
