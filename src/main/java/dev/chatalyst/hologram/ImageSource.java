package dev.chatalyst.hologram;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De donde sale una imagen: un archivo de {@code plugins/Chatalyst/images/} o una URL https.
 * Sin Bukkit.
 *
 * <p>Un nombre de archivo no puede llevar barras ni empezar por punto: nada de
 * {@code ../../server.properties}. Una URL tiene que ser https y sin espacios; la descarga en
 * si (tamaño, tiempo) la limita {@code ImageLibrary}.
 */
public record ImageSource(String value, boolean url) {

    private static final Pattern FILE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9 _.()-]{0,95}\\.(?i:png|jpe?g|gif)");
    private static final Pattern PIXEL_LINE = Pattern.compile("^(.+?)(?:@(\\d{1,3}))?$");

    /** La fuente escrita, o null si no es un archivo valido ni una URL https. */
    public static ImageSource parse(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.toLowerCase(Locale.ROOT).startsWith("https://")) {
            if (value.length() > 512 || value.chars().anyMatch(Character::isWhitespace)) return null;
            try {
                URI uri = URI.create(value);
                if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            return new ImageSource(value, true);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("http://")) return null;
        return FILE.matcher(value).matches() && !value.contains("..")
                ? new ImageSource(value, false) : null;
    }

    /** Una linea de pixel art guardada: fuente y ancho ({@code logo.png@32}). */
    public record PixelLine(ImageSource source, Integer width) {

        public String encode() {
            return source.value() + (width == null ? "" : "@" + width);
        }
    }

    /** Lee {@code fuente[@ancho]}; null si la fuente no vale. */
    public static PixelLine parsePixelLine(String content) {
        if (content == null) return null;
        Matcher m = PIXEL_LINE.matcher(content.strip());
        if (!m.matches()) return null;
        ImageSource source = parse(m.group(1));
        if (source == null) return null;
        Integer width = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        return new PixelLine(source, width);
    }

    /** Nombre corto para mensajes: el archivo, o el dominio de la URL. */
    public String shown() {
        if (!url) return value;
        try {
            return URI.create(value).getHost();
        } catch (IllegalArgumentException invalid) {
            return "url";
        }
    }
}
