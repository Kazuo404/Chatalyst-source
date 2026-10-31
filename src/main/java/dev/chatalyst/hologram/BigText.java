package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /cartel grande <id> <color|degradado> <texto...> [escala:N]}: el color y el tamaño
 * de las letras grandes. Sin Bukkit.
 *
 * <p>Un color es un nombre (español o ingles) o un {@code #RRGGBB}; un degradado son dos o mas
 * separados por guiones ({@code amarillo-dorado}). Los nombres apuntan a colores de MiniMessage
 * o de la paleta del plugin, asi que nada de esto mete un color fuera de ella.
 */
public final class BigText {

    /** Nombre escrito → color de MiniMessage. El orden es el de las sugerencias. */
    public static final Map<String, String> COLORS = colors();

    /** Colores sueltos que se ofrecen al autocompletar (los nombres en español). */
    public static final List<String> SUGGESTED_COLORS = List.of("rojo", "naranja", "amarillo",
            "dorado", "verde", "lima", "celeste", "turquesa", "mar", "azul", "morado", "violeta",
            "lavanda", "rosa", "blanco", "plata", "gris", "negro");

    /**
     * El preset "fachada" de holograms.yml, por si el dueño lo borro de su archivo: letras
     * grandes, planas, de doble cara, sin fondo y con brillo fijo, visibles de lejos.
     */
    public static final Map<String, String> FACADE = facade();

    /** Degradados tipicos que se ofrecen al autocompletar. */
    public static final List<String> SUGGESTED_GRADIENTS = List.of("amarillo-dorado",
            "rojo-dorado", "celeste-azul", "turquesa-azul", "verde-mar", "violeta-lavanda",
            "morado-rosa");

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern SCALE = Pattern.compile(
            "(?i)(?:escala|scale|tamaño|tamano|size):([0-9]+(?:[.,][0-9]+)?)");

    private BigText() {
    }

    private static Map<String, String> facade() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("escala", "7");
        m.put("fondo", "none");
        m.put("sombra", "on");
        m.put("orientacion", "fijo");
        m.put("doblecara", "on");
        m.put("separacion", "0.03");
        m.put("brillo", "15");
        m.put("distancia", "160");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<String, String> colors() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "red", "rojo", "red");
        put(m, "dark_red", "rojooscuro", "darkred", "granate");
        put(m, "#FF9F1C", "naranja", "orange");
        put(m, "yellow", "amarillo", "yellow");
        put(m, "gold", "dorado", "oro", "gold");
        put(m, "green", "verde", "green");
        put(m, "dark_green", "verdeoscuro", "darkgreen");
        put(m, "#7BFFB0", "lima", "menta", "lime", "mint");
        put(m, "aqua", "celeste", "cian", "aqua", "cyan", "lightblue");
        put(m, "dark_aqua", "turquesa", "turquoise", "teal");
        put(m, "#54A6FF", "mar", "sea");
        put(m, "blue", "azul", "blue");
        put(m, "dark_blue", "azuloscuro", "darkblue", "marino", "navy");
        put(m, "dark_purple", "morado", "purple");
        put(m, "#B56CFF", "lila", "violeta", "violet");
        put(m, "#E4A8FF", "lavanda", "lavender");
        put(m, "light_purple", "rosa", "pink", "magenta");
        put(m, "white", "blanco", "white");
        put(m, "#C0C0C0", "plata", "plateado", "silver");
        put(m, "gray", "gris", "gray", "grey");
        put(m, "black", "negro", "black");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, String> map, String color, String... names) {
        for (String name : names) map.put(name, color);
    }

    /** Un color suelto a su forma MiniMessage, o null si no se entiende. */
    public static String color(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (HEX.matcher(value).matches()) return value.toUpperCase(Locale.ROOT);
        return COLORS.get(value);
    }

    /**
     * Formato con {@code {text}} para ese color o degradado (en negrita, como un letrero), o
     * null si alguno de los colores no existe.
     */
    public static String format(String token) {
        if (token == null || token.isBlank()) return null;
        // Con -1 un guion suelto al final ("amarillo-") deja un trozo vacio y se rechaza.
        String[] parts = token.split("-", -1);
        if (parts.length > 5) return null;
        List<String> colors = new ArrayList<>();
        for (String part : parts) {
            String color = color(part);
            if (color == null) return null;
            colors.add(color);
        }
        if (colors.size() == 1) return "<" + colors.getFirst() + "><bold>{text}</bold>";
        return "<gradient:" + String.join(":", colors) + "><bold>{text}</bold></gradient>";
    }

    /** {@code escala:16} al final del comando: el tamaño pedido, o null si no es eso. */
    public static Double scale(String raw) {
        if (raw == null) return null;
        var match = SCALE.matcher(raw.strip());
        if (!match.matches()) return null;
        double value = Double.parseDouble(match.group(1).replace(',', '.'));
        return value >= HoloLook.MIN_SCALE && value <= HoloLook.MAX_SCALE ? value : null;
    }

    /** Si el argumento tiene forma de {@code escala:N} (valga o no el numero). */
    public static boolean looksLikeScale(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT)
                .matches("(escala|scale|tamaño|tamano|size):.*");
    }
}
