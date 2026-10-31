package dev.chatalyst.nick;

import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;

/**
 * Un nick escrito por un jugador, en sus DOS formas: {@code stored} es lo que se guarda y se
 * renderiza (MiniMessage), y {@code plain} es lo que ese texto se LEE en pantalla una vez
 * pintado.
 *
 * <p>La distincion no es cosmetica, es la que sostiene dos defensas. El limite de longitud y
 * el chequeo de suplantacion se miden sobre {@code plain} justamente porque un atacante
 * controla {@code stored}: {@code <red>Notch} no es la cadena "Notch" pero se LEE "Notch", y
 * comparar contra la forma guardada dejaria pasar la suplantacion que el chequeo existe para
 * impedir. Lo mismo con la longitud: si no, medio kilobyte de etiquetas de color cabe en un
 * nick "de 3 letras".
 *
 * <p>El texto de entrada debe llegar ya sin caracteres invisibles ({@code Sanitize
 * .stripInvisible}) y con los emojis resueltos; esta clase solo decide la politica de color.
 */
public record NickText(String stored, String plain) {

    /**
     * @param allowColor si el dueño del nick tiene permiso de color. Con permiso se aceptan
     *     codigos legacy ({@code &6}) ademas de MiniMessage, pero SOLO color y decoracion:
     *     {@link Legacy#toMini} deja pasar etiquetas crudas, asi que sin
     *     {@link MM#sanitizeInteraction} un {@code <click:run_command:...>} se inyectaria como
     *     boton vivo en el chat de todos al pasar por {@code {player_nick}}. Sin permiso el
     *     nick es texto plano y se le quitan los signos que abren etiquetas.
     */
    public static NickText of(String rawNick, boolean allowColor) {
        if (rawNick == null) return new NickText(null, "");
        String stored = allowColor
                ? MM.sanitizeInteraction(Legacy.toMini(rawNick))
                : rawNick.replace('§', ' ').replace("<", "").replace(">", "");
        return new NickText(stored, MM.strip(stored));
    }

    /** Se mide sobre la forma leida, nunca sobre las etiquetas. */
    public boolean longerThan(int maxLength) {
        return plain.length() > maxLength;
    }

    /** La forma con la que se busca un choque de identidad. Ver {@link #spoofKey(String)}. */
    public String spoofKey() {
        return spoofKey(plain);
    }

    /**
     * Reconstruye un apodo de VARIAS palabras a partir de los argumentos del comando.
     *
     * <p>Bukkit parte la linea por el espacio ASCII, asi que {@code /nick El Rey} llega como
     * dos argumentos y quedarse con el primero dejaba el apodo en "El". Se vuelven a unir
     * desde {@code from}.
     *
     * <p>Los huecos se colapsan: {@code "a".split(" ")} conserva las cadenas vacias de en
     * medio, de modo que {@code /nick El   Rey} llegaria con dos vacios y sin esto se
     * guardaria un apodo con tres espacios seguidos. Tambien se recorta a los lados, porque
     * un apodo que empieza o acaba en espacio no se distingue de uno que no.
     */
    public static String joinArguments(String[] args, int from) {
        if (args == null || from >= args.length) return "";
        StringBuilder out = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            String word = args[i];
            if (word == null || word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word);
        }
        return out.toString().trim();
    }

    /**
     * Reduce un texto a lo que un ojo humano distingue de un nombre de Minecraft, para poder
     * comparar. El anti-suplantacion compara CADENAS, asi que sin esto bastaba con que las
     * cadenas difirieran de un modo que nadie ve: "Notch " con un espacio al final se lee
     * "Notch" y no es igual a "Notch".
     *
     * <p>Dos pasos, y ninguno es cosmetico:
     * <ul>
     *   <li>NFKC pliega las variantes de compatibilidad a su forma normal, de modo que los
     *       latinos de ancho completo ("Ｎｏｔｃｈ") colapsan al ASCII que imitan. De paso
     *       convierte en espacio normal a los espacios que {@link Character#isWhitespace} no
     *       reconoce — el duro (00A0), el de cifra (2007), el fino (202F) — que si no se
     *       colarian enteros por el filtro de abajo.</li>
     *   <li>Fuera todo espacio, y a minusculas con {@code Locale.ROOT} (nunca el del sistema:
     *       en un JVM turco "I" no baja a "i").</li>
     * </ul>
     *
     * <p>Esto NO prohibe los apodos con espacios, que en muchos servidores son deliberados:
     * "El Rey" da la clave "elrey" y solo se rechaza si existe un jugador llamado asi.
     */
    public static String spoofKey(String readable) {
        if (readable == null || readable.isEmpty()) return "";
        String folded = java.text.Normalizer.normalize(
                readable, java.text.Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(out::appendCodePoint);
        return out.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
