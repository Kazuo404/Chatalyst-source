package dev.chatalyst.gui;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logica pura del gradiente PROPIO del chat (dos colores elegidos por el jugador), separada del
 * menu para poder probarla sin servidor.
 *
 * <p>El tag que se guarda siempre sale de aqui y siempre tiene la misma forma,
 * {@code <gradient:A:B>}, con A y B validados: o un hex {@code #RRGGBB} o uno de los 16 colores
 * con nombre de Minecraft. Nada que el jugador escriba llega a MiniMessage sin pasar esa regex,
 * asi que un argumento como {@code red><click:...>} no puede colarse en el tag.
 */
public final class ChatCustomGradient {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> NAMED = Set.of(
            "white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow",
            "green", "dark_green", "aqua", "dark_aqua", "blue", "dark_blue",
            "light_purple", "dark_purple");
    /** Exactamente dos extremos: es lo unico que este constructor sabe crear. */
    private static final Pattern TAG = Pattern.compile("<gradient:([^:>]+):([^:>]+)>");
    /** Tag de un solo color: {@code <#RRGGBB>} o {@code <red>}. */
    private static final Pattern SINGLE = Pattern.compile("<([^<>:]+)>");

    private ChatCustomGradient() {
    }

    /** Extremo valido normalizado ({@code #FF6B9B} o {@code red}), o null si no lo es. */
    public static String endpoint(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (HEX.matcher(value).matches()) return value.toUpperCase(Locale.ROOT);
        String named = value.toLowerCase(Locale.ROOT);
        return NAMED.contains(named) ? named : null;
    }

    /** {@code <gradient:A:B>} con dos extremos validos, o null si alguno no lo es. */
    public static String tag(String a, String b) {
        String first = endpoint(a);
        String second = endpoint(b);
        if (first == null || second == null) return null;
        return "<gradient:" + first + ":" + second + ">";
    }

    /** Los dos extremos de un gradiente propio guardado, o null si el tag no tiene esa forma. */
    public static List<String> parse(String tag) {
        if (tag == null) return null;
        Matcher m = TAG.matcher(tag.trim());
        if (!m.matches()) return null;
        String first = endpoint(m.group(1));
        String second = endpoint(m.group(2));
        return first == null || second == null ? null : List.of(first, second);
    }

    /**
     * Valor de color de un tag de la paleta, el que va dentro del gradiente:
     * {@code <#FF6B9B>} da {@code #FF6B9B}; {@code <red>} da {@code red}. Null si no es un color
     * plano valido (un gradiente de la paleta no sirve como extremo).
     */
    public static String valueOf(String paletteTag) {
        if (paletteTag == null) return null;
        Matcher m = SINGLE.matcher(paletteTag.trim());
        return m.matches() ? endpoint(m.group(1)) : null;
    }

    /**
     * Tag seguro para PREVISUALIZAR el color guardado aunque no este en la paleta actual: un
     * gradiente propio valido, o un color plano valido. Cualquier otra cosa (un tag que se quito
     * de la config, o algo raro en el almacenamiento) da "" y la vista previa sale sin color.
     */
    public static String previewTag(String stored) {
        if (stored == null || stored.isBlank()) return "";
        List<String> ends = parse(stored);
        if (ends != null) return "<gradient:" + ends.get(0) + ":" + ends.get(1) + ">";
        String single = valueOf(stored);
        return single == null ? "" : "<" + single + ">";
    }
}
