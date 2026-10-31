package dev.chatalyst.hologram;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preparacion del texto de una linea antes de pintarla. Sin Bukkit.
 *
 * <ul>
 *   <li>Estilos con nombre: {@code <galaxy>CASINO} usa el {@code Format} de
 *       {@code Fancy_Signs.Styles} (players.yml) o de {@code Holograms.Styles}, igual que en
 *       los carteles de madera.</li>
 *   <li>Animar sin perder el estilo: las animaciones que cortan el texto (typewriter, glitch,
 *       marquee...) trabajan en plano. Se separan las etiquetas de apertura del principio
 *       ({@code <#FF4D6D><bold>}) como plantilla y el resto como mensaje, y la animacion
 *       vuelve a envolver cada fotograma con ellas.</li>
 *   <li>Placeholders por jugador: si la linea lleva {@code %papi%} o {@code {token}}, cada
 *       jugador necesita su propia copia del texto.</li>
 * </ul>
 */
public final class HoloText {

    private static final Pattern LEADING_STYLE = Pattern.compile(
            "^<([a-z][a-z0-9_-]{0,31})>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_OPEN_TAGS = Pattern.compile("^((?:<[^/<>][^<>]*>)+)");
    private static final Pattern PAPI = Pattern.compile("%[A-Za-z0-9_]+(?:_[^%\\s]*)?%");
    private static final Pattern TOKEN = Pattern.compile("\\{[a-z][a-z0-9_]*}");
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    /** Plantilla con {msg} y el texto que se anima dentro. */
    public record Split(String template, String message) {
    }

    private HoloText() {
    }

    /**
     * Expande un estilo con nombre al principio de la linea. {@code styles} es id → formato
     * con {@code {text}}. Una etiqueta que no es un estilo se deja tal cual (sera MiniMessage).
     */
    public static String expandStyle(String raw, Map<String, String> styles) {
        if (raw == null || raw.isEmpty() || styles == null || styles.isEmpty()) {
            return raw == null ? "" : raw;
        }
        Matcher match = LEADING_STYLE.matcher(raw);
        if (!match.find()) return raw;
        String id = match.group(1).toLowerCase(Locale.ROOT);
        String format = styles.get(id);
        if (format == null || !format.contains("{text}")) return raw;
        String text = raw.substring(match.end());
        String closing = "</" + id + ">";
        if (text.toLowerCase(Locale.ROOT).endsWith(closing)) {
            text = text.substring(0, text.length() - closing.length());
        }
        return format.replace("{text}", text);
    }

    /**
     * Plantilla y mensaje para AnnounceAnimator. Sin etiquetas al principio, la plantilla es
     * {@code {msg}} y la animacion recibe la linea entera.
     */
    public static Split split(String styled) {
        String value = styled == null ? "" : styled;
        Matcher match = LEADING_OPEN_TAGS.matcher(value);
        if (!match.find()) return new Split("{msg}", value);
        String rest = value.substring(match.end());
        if (rest.isEmpty()) return new Split("{msg}", value);
        return new Split(match.group(1) + "{msg}", rest);
    }

    /** Si la linea depende de quien la mira. */
    public static boolean personal(String raw) {
        if (raw == null) return false;
        return PAPI.matcher(raw).find() || TOKEN.matcher(raw).find();
    }

    /** Caracteres visibles (sin etiquetas MiniMessage ni codigos &), para medir y animar. */
    public static int visibleLength(String raw) {
        if (raw == null) return 0;
        // Compilada una vez: String.replaceAll la recompilaba en cada llamada.
        String plain = LEGACY_CODE.matcher(TAG.matcher(raw).replaceAll("")).replaceAll("");
        return plain.codePointCount(0, plain.length());
    }
}
