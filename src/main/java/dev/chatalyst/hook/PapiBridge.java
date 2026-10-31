package dev.chatalyst.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aislado en su propia clase a proposito: solo se carga cuando PlaceholderAPI esta
 * instalado (lo llama {@link Placeholders} tras comprobarlo). Referenciar aqui la clase
 * de PAPI no dispara NoClassDefFoundError si el plugin no esta presente.
 */
final class PapiBridge {

    private static final Pattern TOKEN = Pattern.compile("%[^%\\s]+%");

    private PapiBridge() {
    }

    /**
     * Se resuelve placeholder a placeholder, no la cadena entera de una vez: es la unica forma
     * de saber donde empieza y acaba cada valor para pasarlo por {@link PapiValues} sin tocar
     * el formato que lo rodea.
     */
    /** Valor tal cual lo da PAPI (sin pasar a MiniMessage); "" si falla. */
    static String raw(Player p, String placeholder) {
        try {
            String value = PlaceholderAPI.setPlaceholders(p, placeholder);
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    static String set(Player p, String input) {
        try {
            Matcher m = TOKEN.matcher(input);
            if (!m.find()) return input;
            StringBuilder out = new StringBuilder(input.length() + 16);
            int last = 0;
            do {
                out.append(input, last, m.start());
                String token = m.group();
                String value = PlaceholderAPI.setPlaceholders(p, token);
                // Sin expansion que lo reconozca, PAPI lo devuelve tal cual: se deja literal.
                out.append(value.equals(token) ? token : PapiValues.toMini(value));
                last = m.end();
            } while (m.find());
            return out.append(input, last, input.length()).toString();
        } catch (Throwable t) {
            return input;
        }
    }
}
