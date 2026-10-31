package dev.chatalyst.util;

/**
 * Convierte texto a versalitas unicode ("tilin" -> "ᴛɪʟɪɴ"), el estilo de letra pequeña que
 * usan muchos servers en TAB, scoreboards y nametags.
 *
 * <p>Solo cambia las letras VISIBLES: los codigos de color ({@code §7}, {@code &a},
 * {@code §x§1§2...}, {@code &#RRGGBB}) y las etiquetas MiniMessage ({@code <gray>}) se dejan
 * intactos, o se romperian los colores del texto. La {@code x} no tiene versalita en unicode
 * y se queda como esta.
 */
public final class SmallCaps {

    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private SmallCaps() {
    }

    public static String convert(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            // §X o &X: el codigo y su caracter se copian tal cual.
            if ((c == '§' || c == '&') && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '#' && c == '&' && i + 8 <= input.length()) {
                    out.append(input, i, i + 8); // &#RRGGBB
                    i += 8;
                    continue;
                }
                if (c == '§' || "0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(next) >= 0) {
                    out.append(c).append(next);
                    i += 2;
                    continue;
                }
            }
            // <etiqueta> de MiniMessage: entera, sin convertir su nombre ni sus argumentos.
            if (c == '<') {
                int close = input.indexOf('>', i);
                if (close > i) {
                    out.append(input, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            int index = PLAIN.indexOf(Character.toLowerCase(c));
            out.append(index >= 0 ? SMALL.charAt(index) : c);
            i++;
        }
        return out.toString();
    }
}
