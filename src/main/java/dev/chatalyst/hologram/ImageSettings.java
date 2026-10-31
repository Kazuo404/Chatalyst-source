package dev.chatalyst.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Limites y ajustes de las imagenes de los carteles ({@code Holograms.Images} en
 * holograms.yml). Todos tienen tope: una imagen la elige el staff, pero un archivo enorme o un
 * GIF de mil fotogramas no puede tumbar el servidor por un despiste.
 *
 * @param maxFileBytes    tamaño maximo del archivo (local o descargado)
 * @param maxDimension    lado maximo de la imagen original, en pixeles
 * @param maxFrames       fotogramas maximos de un GIF (los demas se ignoran)
 * @param pixelDefault    ancho por defecto del pixel art, en pixeles
 * @param pixelMax        ancho maximo del pixel art
 * @param pixelAdvance    ancho en px del caracter de pixel en la fuente del juego
 * @param lineHeight      alto en px de una linea de TextDisplay
 * @param colorBits       bits por canal del color del pixel art (agrupa mas con menos)
 * @param muralMaxSide    lado maximo de un mural, en mapas
 * @param muralMaxTiles   mapas maximos de un mural
 * @param muralMaxFps     cambios por segundo maximos de un GIF en mural
 * @param muralRange      distancia a la que se manda la animacion de un mural, en bloques
 * @param ditherDefault   dithering por defecto en murales nuevos
 * @param urlEnabled      si se aceptan imagenes por URL (ademas del permiso)
 */
public record ImageSettings(long maxFileBytes, int maxDimension, int maxFrames,
                            int pixelDefault, int pixelMax, int pixelAdvance, int lineHeight,
                            int colorBits, int muralMaxSide, int muralMaxTiles, int muralMaxFps,
                            int muralRange, boolean ditherDefault, boolean urlEnabled) {

    public static final ImageSettings DEFAULT = new ImageSettings(5L * 1024 * 1024, 2048, 60,
            32, 96, 9, 10, 5, 8, 48, 5, 64, true, true);

    public static ImageSettings load(FileConfiguration config) {
        ConfigurationSection s = config.getConfigurationSection("Holograms.Images");
        if (s == null) return DEFAULT;
        ImageSettings d = DEFAULT;
        return new ImageSettings(
                clamp(s.getLong("Max_File_Kb", d.maxFileBytes / 1024), 16, 20 * 1024) * 1024,
                (int) clamp(s.getInt("Max_Dimension", d.maxDimension), 16, 8192),
                (int) clamp(s.getInt("Max_Frames", d.maxFrames), 1, 300),
                (int) clamp(s.getInt("Pixel_Art.Default_Width", d.pixelDefault), 4, 160),
                (int) clamp(s.getInt("Pixel_Art.Max_Width", d.pixelMax), 4, 160),
                (int) clamp(s.getInt("Pixel_Art.Pixel_Advance", d.pixelAdvance), 4, 32),
                (int) clamp(s.getInt("Pixel_Art.Line_Height", d.lineHeight), 4, 32),
                (int) clamp(s.getInt("Pixel_Art.Color_Bits", d.colorBits), 3, 8),
                (int) clamp(s.getInt("Mural.Max_Side", d.muralMaxSide), 1, 32),
                (int) clamp(s.getInt("Mural.Max_Maps", d.muralMaxTiles), 1, 512),
                (int) clamp(s.getInt("Mural.Max_Fps", d.muralMaxFps), 1, 20),
                (int) clamp(s.getInt("Mural.Animation_Range", d.muralRange), 8, 256),
                s.getBoolean("Mural.Dither", d.ditherDefault),
                s.getBoolean("Allow_Urls", d.urlEnabled));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Ancho de pixel art pedido, acotado; el de por defecto si no se pidio. */
    public int pixelWidth(Integer requested) {
        if (requested == null) return Math.min(pixelDefault, pixelMax);
        return Math.max(1, Math.min(pixelMax, requested));
    }

    /** Factor vertical para que cada pixel quede cuadrado (ancho del bloque / alto de linea). */
    public float squareFactor() {
        return (float) pixelAdvance / lineHeight;
    }
}
