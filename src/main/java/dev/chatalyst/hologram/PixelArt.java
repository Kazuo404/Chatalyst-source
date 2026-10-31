package dev.chatalyst.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel art en un TextDisplay, solo con lo que el cliente vanilla ya sabe pintar: un caracter
 * de bloque ({@code █}) coloreado por pixel. Sin Bukkit (solo Adventure).
 *
 * <ul>
 *   <li>Los pixeles seguidos del mismo color van en UN solo trozo de texto: un logo de 32 px
 *       pasa de mil componentes a unas decenas.</li>
 *   <li>El color se redondea a {@code colorBits} por canal antes de agrupar: a este tamaño no
 *       se distingue y una foto agrupa muchisimo mejor.</li>
 *   <li>Un pixel transparente es hueco del mismo ancho que el bloque. El ancho de un espacio
 *       es fijo (4 px) y uno en negrita mide uno mas (5 px), asi que con espacios normales y en
 *       negrita se consigue cualquier ancho desde 8: un bloque de 9 px son 1 + 1.</li>
 *   <li>Lo transparente al final de cada fila no se escribe (alineado a la izquierda no hace
 *       falta).</li>
 * </ul>
 */
public final class PixelArt {

    /** Pixel por defecto. */
    public static final String DEFAULT_PIXEL = "█";
    static final int SPACE_ADVANCE = 4;
    static final int BOLD_SPACE_ADVANCE = 5;

    /** Un tramo de una fila: un color repetido, o hueco ({@code rgb < 0}). */
    public record Run(int rgb, int length) {

        public boolean gap() {
            return rgb < 0;
        }
    }

    private PixelArt() {
    }

    /** Filas de tramos del fotograma (ya reducido al ancho de pixeles que se quiere). */
    public static List<List<Run>> runs(ImageFrame frame, int colorBits) {
        List<List<Run>> rows = new ArrayList<>(frame.height());
        for (int y = 0; y < frame.height(); y++) {
            List<Run> row = new ArrayList<>();
            int current = Integer.MIN_VALUE;
            int length = 0;
            for (int x = 0; x < frame.width(); x++) {
                int p = frame.pixel(x, y);
                int rgb = ImageFrame.transparent(p) ? -1 : quantize(p & 0xFFFFFF, colorBits);
                if (rgb == current) {
                    length++;
                    continue;
                }
                if (length > 0) row.add(new Run(current, length));
                current = rgb;
                length = 1;
            }
            if (length > 0) row.add(new Run(current, length));
            while (!row.isEmpty() && row.getLast().gap()) row.removeLast();
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    /** Redondea cada canal a {@code bits} bits (8 = sin tocar) y lo vuelve a expandir. */
    static int quantize(int rgb, int bits) {
        if (bits >= 8) return rgb;
        int levels = (1 << Math.max(1, bits)) - 1;
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c = (rgb >> shift) & 0xFF;
            int q = Math.round(c * levels / 255f);
            out |= Math.round(q * 255f / levels) << shift;
        }
        return out;
    }

    /**
     * Cuantos espacios normales (4 px) y en negrita (5 px) suman exactamente {@code width} px,
     * con el menor numero de caracteres. Null si no hay combinacion (anchos 1-3, 6, 7 y 11).
     */
    static int[] gapSpaces(int width) {
        for (int bold = width / BOLD_SPACE_ADVANCE; bold >= 0; bold--) {
            int rest = width - bold * BOLD_SPACE_ADVANCE;
            if (rest % SPACE_ADVANCE == 0) return new int[]{rest / SPACE_ADVANCE, bold};
        }
        return null;
    }

    /**
     * El componente del pixel art: una linea de texto por fila de pixeles.
     *
     * @param pixel        caracter de cada pixel (normalmente {@code █})
     * @param pixelAdvance ancho en px de ese caracter en la fuente del juego (9 para {@code █})
     */
    public static Component component(List<List<Run>> rows, String pixel, int pixelAdvance) {
        TextComponent.Builder root = Component.text();
        for (int y = 0; y < rows.size(); y++) {
            if (y > 0) root.append(Component.newline());
            for (Run run : rows.get(y)) {
                if (run.gap()) {
                    appendGap(root, run.length() * pixelAdvance);
                } else {
                    root.append(Component.text(pixel.repeat(run.length()),
                            TextColor.color(run.rgb())));
                }
            }
        }
        return root.build();
    }

    private static void appendGap(TextComponent.Builder root, int widthPx) {
        int[] spaces = gapSpaces(widthPx);
        if (spaces == null) {
            // Ancho imposible con espacios (fuente con un bloque raro): se aproxima por abajo.
            spaces = new int[]{Math.max(1, widthPx / SPACE_ADVANCE), 0};
        }
        if (spaces[0] > 0) root.append(Component.text(" ".repeat(spaces[0])));
        if (spaces[1] > 0) {
            root.append(Component.text(" ".repeat(spaces[1]), null, TextDecoration.BOLD));
        }
    }

    /** Lineas (filas) y columnas visibles de un fotograma una vez pintado. */
    public static int columns(List<List<Run>> rows) {
        int max = 0;
        for (List<Run> row : rows) {
            int width = 0;
            for (Run run : row) width += run.length();
            max = Math.max(max, width);
        }
        return max;
    }
}
