package dev.chatalyst.hologram;

import java.util.Locale;

/**
 * Una linea de un cartel: texto (MiniMessage, estilos con nombre, placeholders) o un objeto
 * flotante (ItemDisplay). Se escribe igual desde el comando que en el archivo: un texto que
 * empieza por {@code item:} (o {@code objeto:} / {@code icono:}) es un objeto.
 *
 * @param animation animacion propia de la linea, o null para heredar la del cartel
 */
public record HoloLine(Kind kind, String content, String animation) {

    public enum Kind { TEXT, ITEM, IMAGE }

    private static final String[] ITEM_PREFIXES = {"item:", "objeto:", "icono:", "icon:"};
    /** Pixel art: {@code imagen:logo.png@32} (fuente y ancho en pixeles). */
    private static final String[] IMAGE_PREFIXES = {"imagen:", "image:", "img:"};
    /** Tope de una linea. Holgado: un degradado letra a letra importado ocupa mucho en MiniMessage. */
    public static final int MAX_LENGTH = 2048;

    public HoloLine {
        kind = kind == null ? Kind.TEXT : kind;
        content = content == null ? "" : content;
        if (content.length() > MAX_LENGTH) content = content.substring(0, MAX_LENGTH);
        animation = animation == null || animation.isBlank() ? null : animation;
    }

    public static HoloLine text(String content) {
        return new HoloLine(Kind.TEXT, content, null);
    }

    /** Lo que escribio el staff, tal cual se guarda: {@code item:diamond} o el texto. */
    public static HoloLine parse(String raw) {
        String value = raw == null ? "" : raw.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : IMAGE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                String spec = value.substring(prefix.length()).strip();
                if (ImageSource.parsePixelLine(spec) != null) {
                    return new HoloLine(Kind.IMAGE, spec, null);
                }
            }
        }
        for (String prefix : ITEM_PREFIXES) {
            if (lower.startsWith(prefix)) {
                String id = value.substring(prefix.length()).strip().toLowerCase(Locale.ROOT);
                if (!id.isEmpty()) return new HoloLine(Kind.ITEM, id, null);
            }
        }
        return text(value);
    }

    /** Forma escrita, la inversa de {@link #parse}. */
    public String raw() {
        return switch (kind) {
            case ITEM -> "item:" + content;
            case IMAGE -> "imagen:" + content;
            case TEXT -> content;
        };
    }

    public HoloLine withAnimation(String next) {
        return new HoloLine(kind, content, next);
    }

    public boolean isImage() {
        return kind == Kind.IMAGE;
    }

    public boolean isText() {
        return kind == Kind.TEXT;
    }
}
