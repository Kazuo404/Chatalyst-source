package dev.chatalyst.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte codigos de color legacy (&6, &l, &#RRGGBB...) a etiquetas MiniMessage, para que
 * los jugadores puedan usar el estilo clasico con & ademas de MiniMessage (&lt;gold&gt;).
 */
public final class Legacy {

    private static final Pattern HEX = Pattern.compile("(?i)[&§]#([0-9a-f]{6})");
    private static final Pattern HEX_SECTION = Pattern.compile("(?i)§#([0-9a-f]{6})");
    // El formato hex de Bungee/Spigot: §x§1§2§3§4§5§6. Es el que sueltan los prefijos de
    // LuckPerms/Vault con RGB; sin esto cada §N se leia como un color suelto y "VIP" salia
    // dorado (§6) en vez de #123456.
    private static final Pattern BUNGEE_HEX = bungeeHex("[&§]");
    private static final Pattern BUNGEE_HEX_SECTION = bungeeHex("§");

    private Legacy() {
    }

    /** Devuelve el texto con los codigos & (o §) convertidos a etiquetas MiniMessage. */
    public static String toMini(String input) {
        return convert(input, BUNGEE_HEX, HEX, "&§");
    }

    /**
     * Igual, pero SOLO para los codigos escritos con '§'.
     *
     * <p>El '§' es el codigo interno de Minecraft: nadie lo teclea a proposito en una config, y
     * cuando aparece viene de otro plugin (el prefijo de LuckPerms/Vault, un placeholder de
     * PlaceholderAPI, lo que pinte TAB). El '&', en cambio, es texto legitimo — "Tom &amp;
     * Jerry" no puede volverse un color — asi que esta variante no lo toca.
     */
    public static String sectionToMini(String input) {
        return convert(input, BUNGEE_HEX_SECTION, HEX_SECTION, "§");
    }

    private static Pattern bungeeHex(String marker) {
        String digit = marker + "([0-9a-f])";
        return Pattern.compile("(?i)" + marker + "x" + digit.repeat(6));
    }

    private static String convert(String input, Pattern bungee, Pattern hex, String markers) {
        if (input == null || input.isEmpty()) return input;

        // Hex de Bungee primero: sus §x/§1... se confundirian con codigos de un caracter.
        Matcher b = bungee.matcher(input);
        StringBuilder rgb = new StringBuilder();
        while (b.find()) {
            StringBuilder color = new StringBuilder(6);
            for (int group = 1; group <= 6; group++) color.append(b.group(group));
            b.appendReplacement(rgb, "<#" + color + ">");
        }
        b.appendTail(rgb);
        input = rgb.toString();

        // Hex: &#RRGGBB / §#RRGGBB -> <#RRGGBB>
        Matcher m = hex.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "<#" + m.group(1) + ">");
        }
        m.appendTail(sb);
        String out = sb.toString();

        // Codigos de un caracter tras & o §.
        out = replaceCode(out, markers, '0', "<black>");
        out = replaceCode(out, markers, '1', "<dark_blue>");
        out = replaceCode(out, markers, '2', "<dark_green>");
        out = replaceCode(out, markers, '3', "<dark_aqua>");
        out = replaceCode(out, markers, '4', "<dark_red>");
        out = replaceCode(out, markers, '5', "<dark_purple>");
        out = replaceCode(out, markers, '6', "<gold>");
        out = replaceCode(out, markers, '7', "<gray>");
        out = replaceCode(out, markers, '8', "<dark_gray>");
        out = replaceCode(out, markers, '9', "<blue>");
        out = replaceCode(out, markers, 'a', "<green>");
        out = replaceCode(out, markers, 'b', "<aqua>");
        out = replaceCode(out, markers, 'c', "<red>");
        out = replaceCode(out, markers, 'd', "<light_purple>");
        out = replaceCode(out, markers, 'e', "<yellow>");
        out = replaceCode(out, markers, 'f', "<white>");
        out = replaceCode(out, markers, 'l', "<bold>");
        out = replaceCode(out, markers, 'm', "<strikethrough>");
        out = replaceCode(out, markers, 'n', "<underlined>");
        out = replaceCode(out, markers, 'o', "<italic>");
        out = replaceCode(out, markers, 'k', "<obfuscated>");
        out = replaceCode(out, markers, 'r', "<reset>");
        return out;
    }

    private static String replaceCode(String s, String markers, char code, String tag) {
        String out = s;
        for (int i = 0; i < markers.length(); i++) {
            char marker = markers.charAt(i);
            out = out.replace(marker + "" + code, tag)
                    .replace(marker + "" + Character.toUpperCase(code), tag);
        }
        return out;
    }

    /**
     * Codigo legacy del color ("&c" para los 16 vanilla, "&#RRGGBB" para RGB), o "" si es
     * null. El camino inverso a {@link #toMini}: para plugins que solo entienden & (TAB).
     */
    public static String code(net.kyori.adventure.text.format.TextColor color) {
        if (color == null) return "";
        net.kyori.adventure.text.format.NamedTextColor named =
                net.kyori.adventure.text.format.NamedTextColor.namedColor(color.value());
        if (named != null) {
            String c = NAMED_CODES.get(named.toString());
            if (c != null) return "&" + c;
        }
        return String.format("&#%06x", color.value());
    }

    private static final java.util.Map<String, String> NAMED_CODES = java.util.Map.ofEntries(
            java.util.Map.entry("black", "0"), java.util.Map.entry("dark_blue", "1"),
            java.util.Map.entry("dark_green", "2"), java.util.Map.entry("dark_aqua", "3"),
            java.util.Map.entry("dark_red", "4"), java.util.Map.entry("dark_purple", "5"),
            java.util.Map.entry("gold", "6"), java.util.Map.entry("gray", "7"),
            java.util.Map.entry("dark_gray", "8"), java.util.Map.entry("blue", "9"),
            java.util.Map.entry("green", "a"), java.util.Map.entry("aqua", "b"),
            java.util.Map.entry("red", "c"), java.util.Map.entry("light_purple", "d"),
            java.util.Map.entry("yellow", "e"), java.util.Map.entry("white", "f"));
}
