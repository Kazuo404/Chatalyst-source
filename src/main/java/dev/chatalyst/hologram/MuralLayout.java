package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo puro de un mural de mapas: el tamaño escrito ({@code 4x3}), el troceado de la imagen en
 * mapas de 128 x 128 y donde va cada marco segun la cara del bloque. Sin Bukkit.
 */
public final class MuralLayout {

    /** Pixeles por mapa (lado). */
    public static final int TILE = 128;

    private static final Pattern SIZE = Pattern.compile("(\\d{1,2})\\s*[x×*]\\s*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);

    /** Ancho y alto en bloques (= mapas). */
    public record Size(int width, int height) {

        public int tiles() {
            return width * height;
        }
    }

    /**
     * Hacia donde mira un marco (la cara del bloque en que se apoya). Las horizontales son
     * paredes; UP es el suelo y DOWN el techo.
     */
    public enum Facing {
        NORTH(0, 0, -1), SOUTH(0, 0, 1), EAST(1, 0, 0), WEST(-1, 0, 0), UP(0, 1, 0), DOWN(0, -1, 0);

        public final int dx;
        public final int dy;
        public final int dz;

        Facing(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public boolean horizontal() {
            return dy == 0;
        }

        /** Girado 90 grados en el sentido del reloj, visto desde arriba. */
        public Facing clockwise() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
        }

        public Facing counterClockwise() {
            return clockwise().clockwise().clockwise();
        }

        public static Facing parse(String raw) {
            try {
                return raw == null ? null : valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    /** Un marco: su desplazamiento desde el ancla (bloque del marco abajo a la izquierda). */
    public record Placement(int tile, int dx, int dy, int dz, int quarterTurns) {
    }

    private MuralLayout() {
    }

    /** "4x3", "4 x 3", "4×3": el tamaño, o null si no es eso o se sale de 1..{@code max}. */
    public static Size parseSize(String raw, int maxSide) {
        if (raw == null) return null;
        Matcher m = SIZE.matcher(raw.strip());
        if (!m.matches()) return null;
        int w = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        if (w < 1 || h < 1 || w > maxSide || h > maxSide) return null;
        return new Size(w, h);
    }

    /**
     * Trozos de un mural ya escalado a {@code width*128 x height*128}, por filas desde arriba a
     * la izquierda. Se convierte la imagen entera de una vez (el dithering no hace costuras
     * entre mapas) y luego se corta.
     */
    public static List<byte[]> tiles(byte[] colors, Size size) {
        int fullW = size.width() * TILE;
        if (colors.length != fullW * size.height() * TILE) {
            throw new IllegalArgumentException("image does not match the mural size");
        }
        List<byte[]> out = new ArrayList<>(size.tiles());
        for (int row = 0; row < size.height(); row++) {
            for (int col = 0; col < size.width(); col++) {
                byte[] tile = new byte[TILE * TILE];
                for (int y = 0; y < TILE; y++) {
                    int from = (row * TILE + y) * fullW + col * TILE;
                    System.arraycopy(colors, from, tile, y * TILE, TILE);
                }
                out.add(tile);
            }
        }
        return out;
    }

    /**
     * Donde va cada mapa. El ancla es el marco de abajo a la izquierda (el bloque al que se
     * apunta, del lado de la cara pulsada) y el mural crece hacia la derecha y hacia arriba
     * — en el suelo y el techo, hacia delante, hacia donde miraba quien lo puso.
     *
     * <ul>
     *   <li>Pared: arriba es arriba; la derecha de quien la mira es la cara girada a la
     *       izquierda (un marco que mira al norte se ve desde el norte: su derecha es el oeste).
     *       Sin giro del mapa.</li>
     *   <li>Suelo y techo: arriba es {@code up} (hacia donde miraba quien lo coloco), la derecha
     *       es {@code up} girado a la derecha. El mapa se gira en cuartos de vuelta: en el suelo
     *       un marco sin girar tiene el norte arriba, en el techo el sur.</li>
     * </ul>
     */
    public static List<Placement> place(Size size, Facing face, Facing up) {
        List<Placement> out = new ArrayList<>(size.tiles());
        Facing right;
        Facing vertical; // hacia donde crecen las filas (de abajo arriba)
        int turns;
        if (face.horizontal()) {
            right = face.counterClockwise();
            vertical = Facing.UP;
            turns = 0;
        } else {
            Facing top = up == null || !up.horizontal() ? Facing.NORTH : up;
            right = top.clockwise();
            vertical = top;
            turns = face == Facing.UP ? floorTurns(top) : ceilingTurns(top);
        }
        int tile = 0;
        for (int row = 0; row < size.height(); row++) {
            int fromBottom = size.height() - 1 - row;
            for (int col = 0; col < size.width(); col++) {
                out.add(new Placement(tile++,
                        right.dx * col + vertical.dx * fromBottom,
                        right.dy * col + vertical.dy * fromBottom,
                        right.dz * col + vertical.dz * fromBottom, turns));
            }
        }
        return out;
    }

    /** Cuartos de vuelta (sentido del reloj, como gira el jugador un marco) en el suelo. */
    static int floorTurns(Facing top) {
        return switch (top) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /** En el techo se ve desde abajo: sin girar tiene el sur arriba y el giro va al reves. */
    static int ceilingTurns(Facing top) {
        return switch (top) {
            case EAST -> 1;
            case NORTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
