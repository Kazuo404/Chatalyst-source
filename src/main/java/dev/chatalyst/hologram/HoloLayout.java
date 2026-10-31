package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.List;

/**
 * Donde va cada trozo de un cartel. Las lineas de texto seguidas se agrupan en UN TextDisplay
 * (multilinea nativo: menos entidades y el interlineado del propio juego); cada objeto es su
 * propio ItemDisplay. Se apila de abajo arriba desde la base del cartel, como crece el texto.
 *
 * <p>Medidas del juego a escala 1: una linea de texto ocupa 10 px de fuente y un bloque son
 * 40 px, asi que 0,25 bloques; un objeto con transformacion FIXED (la del marco) mide medio
 * bloque. Sin Bukkit.
 */
public final class HoloLayout {

    public static final double LINE_HEIGHT = 0.25;
    public static final double TEXT_PADDING = 0.05;
    public static final double ITEM_SIZE = 0.5;
    public static final double SEGMENT_GAP = 0.05;
    /** Ancho medio de un caracter: 6 px de 40. */
    public static final double CHAR_WIDTH = 0.15;

    /**
     * Un trozo: lineas {@code [from, to)} del cartel.
     *
     * @param y      altura del punto de anclaje respecto a la base: el borde inferior para un
     *               texto (crece hacia arriba), el centro para un objeto
     * @param height alto total del trozo, en bloques
     */
    public record Segment(HoloLine.Kind kind, int from, int to, double y, double height) {
    }

    /** El trozo y el tamaño de la caja de clic que lo cubre todo. */
    public record Layout(List<Segment> segments, double width, double height) {
    }

    private HoloLayout() {
    }

    /**
     * @param kinds     tipo de cada linea, de arriba abajo
     * @param lengths   caracteres visibles de cada linea (0 para objetos)
     */
    public static Layout of(List<HoloLine.Kind> kinds, List<Integer> lengths, double scale,
                            double itemScale, int lineWidth) {
        return of(kinds, lengths, null, scale, itemScale, lineWidth, 9, 0.9);
    }

    /**
     * Con lineas de imagen (pixel art): cada una es su propio trozo, de {@code rows[i]} filas
     * de pixeles; {@code lengths[i]} son sus columnas. El alto de cada fila se ajusta con
     * {@code squareFactor} para que el pixel quede cuadrado (el TextDisplay se escala igual).
     *
     * @param rows         filas de pixeles de cada linea de imagen (null o 0 para las demas)
     * @param pixelAdvance ancho en px del caracter de pixel
     */
    public static Layout of(List<HoloLine.Kind> kinds, List<Integer> lengths, List<Integer> rows,
                            double scale, double itemScale, int lineWidth, int pixelAdvance,
                            double squareFactor) {
        List<int[]> groups = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            boolean joinsPrevious = i > 0 && kinds.get(i) == HoloLine.Kind.TEXT
                    && kinds.get(i - 1) == HoloLine.Kind.TEXT;
            if (joinsPrevious) groups.getLast()[1] = i + 1;
            else groups.add(new int[]{i, i + 1});
        }
        List<Segment> segments = new ArrayList<>();
        double y = 0;
        // De abajo arriba: el ultimo trozo descansa sobre la base.
        for (int g = groups.size() - 1; g >= 0; g--) {
            int[] group = groups.get(g);
            HoloLine.Kind kind = kinds.get(group[0]);
            double height = switch (kind) {
                case TEXT -> ((group[1] - group[0]) * LINE_HEIGHT + TEXT_PADDING) * scale;
                case IMAGE -> (Math.max(1, rowsOf(rows, group[0])) * LINE_HEIGHT * squareFactor
                        + TEXT_PADDING) * scale;
                case ITEM -> ITEM_SIZE * scale * itemScale;
            };
            // Texto e imagen crecen hacia arriba desde su ancla; un objeto se centra en ella.
            double anchor = kind == HoloLine.Kind.ITEM ? y + height / 2 : y;
            segments.addFirst(new Segment(kind, group[0], group[1], anchor, height));
            y += height + SEGMENT_GAP * scale;
        }
        double totalHeight = Math.max(0, y - SEGMENT_GAP * scale);

        int longest = 0;
        int widestImage = 0;
        for (int i = 0; i < lengths.size(); i++) {
            if (kinds.get(i) == HoloLine.Kind.IMAGE) widestImage = Math.max(widestImage, lengths.get(i));
            else longest = Math.max(longest, lengths.get(i));
        }
        double maxWidth = lineWidth / 40.0 * scale;
        double width = Math.min(maxWidth, longest * CHAR_WIDTH * scale);
        // Una imagen no se parte en lineas: su ancho es el de sus pixeles, sin tope.
        width = Math.max(width, widestImage * pixelAdvance / 40.0 * scale);
        boolean hasItem = kinds.contains(HoloLine.Kind.ITEM);
        if (hasItem) width = Math.max(width, ITEM_SIZE * scale * itemScale);
        return new Layout(List.copyOf(segments), Math.max(0.3, width), Math.max(0.3, totalHeight));
    }

    private static int rowsOf(List<Integer> rows, int index) {
        if (rows == null || index >= rows.size() || rows.get(index) == null) return 1;
        return rows.get(index);
    }
}
