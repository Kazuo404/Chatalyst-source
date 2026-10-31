package dev.chatalyst.hologram;

/**
 * Aspecto de un cartel. Sin Bukkit: los enums propios se traducen a los de Paper al pintar.
 *
 * @param scale       tamaño del texto (1 = normal; un CASINO de fachada ronda 6-8)
 * @param background  "default" (gris del juego), "none" (transparente) o "#RRGGBB" / "#AARRGGBB"
 * @param opacity     opacidad del texto en %, 100 = opaco
 * @param brightness  luz fija 0-15, o -1 para usar la del sitio
 * @param lineWidth   ancho maximo de linea en pixeles antes de partirla
 * @param doubleSided copia girada 180 grados para leerse por detras (billboard fijo)
 * @param sideGap     separacion entre las dos caras, en bloques
 * @param viewRange   distancia a la que se ve, en bloques
 * @param seeThrough  se ve a traves de los bloques
 * @param itemScale   tamaño de las lineas de objeto respecto al texto
 */
public record HoloLook(double scale, String background, boolean shadow, int opacity,
                       int brightness, Alignment alignment, int lineWidth,
                       Billboard billboard, boolean doubleSided, double sideGap,
                       int viewRange, boolean seeThrough, double itemScale) {

    public enum Alignment { CENTER, LEFT, RIGHT }

    /** CENTER mira siempre al jugador; FIXED queda plano como un cartel de verdad. */
    public enum Billboard { CENTER, FIXED, VERTICAL, HORIZONTAL }

    public static final double MIN_SCALE = 0.1;
    public static final double MAX_SCALE = 20;

    public static final HoloLook DEFAULT = new HoloLook(1.0, "default", true, 100, -1,
            Alignment.CENTER, 200, Billboard.CENTER, false, 0.02, 48, false, 1.0);

    public HoloLook {
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        background = background == null || background.isBlank() ? "default" : background;
        opacity = (int) clamp(opacity, 0, 100);
        brightness = brightness < 0 ? -1 : (int) clamp(brightness, 0, 15);
        alignment = alignment == null ? Alignment.CENTER : alignment;
        lineWidth = (int) clamp(lineWidth, 20, 2000);
        billboard = billboard == null ? Billboard.CENTER : billboard;
        sideGap = clamp(sideGap, 0, 0.5);
        viewRange = (int) clamp(viewRange, 4, 512);
        itemScale = clamp(itemScale, 0.1, 10);
    }

    static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    public HoloLook withScale(double v) {
        return new HoloLook(v, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withBackground(String v) {
        return new HoloLook(scale, v, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withShadow(boolean v) {
        return new HoloLook(scale, background, v, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withOpacity(int v) {
        return new HoloLook(scale, background, shadow, v, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withBrightness(int v) {
        return new HoloLook(scale, background, shadow, opacity, v, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withAlignment(Alignment v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, v, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withLineWidth(int v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, v,
                billboard, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withBillboard(Billboard v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                v, doubleSided, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withDoubleSided(boolean v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, v, sideGap, viewRange, seeThrough, itemScale);
    }

    public HoloLook withSideGap(double v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, v, viewRange, seeThrough, itemScale);
    }

    public HoloLook withViewRange(int v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, v, seeThrough, itemScale);
    }

    public HoloLook withSeeThrough(boolean v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, v, itemScale);
    }

    public HoloLook withItemScale(double v) {
        return new HoloLook(scale, background, shadow, opacity, brightness, alignment, lineWidth,
                billboard, doubleSided, sideGap, viewRange, seeThrough, v);
    }

    /**
     * Color de fondo como ARGB, o null para el gris por defecto del juego. "none" es
     * transparente del todo (alfa 0), que es lo que quiere un letrero de neon.
     */
    public static Integer backgroundArgb(String background) {
        if (background == null) return null;
        String value = background.trim();
        if (value.equalsIgnoreCase("default")) return null;
        if (value.equalsIgnoreCase("none")) return 0;
        if (!value.startsWith("#")) return null;
        String hex = value.substring(1);
        try {
            if (hex.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
            if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException invalid) {
            return null;
        }
        return null;
    }

    /** Opacidad del texto como byte de Minecraft: 100 % se envia como -1 (sin tocar). */
    public static byte opacityByte(int percent) {
        if (percent >= 100) return (byte) -1;
        int value = (int) Math.round(clamp(percent, 0, 100) * 255 / 100.0);
        // Por debajo de ~26 el cliente deja de dibujar el texto: se sube al minimo visible.
        return (byte) Math.max(26, value);
    }
}
