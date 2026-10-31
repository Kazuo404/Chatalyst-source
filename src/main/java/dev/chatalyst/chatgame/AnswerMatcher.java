package dev.chatalyst.chatgame;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decide si lo que alguien escribio en el chat es la respuesta.
 *
 * <p>El chat no es un formulario: la gente escribe sin tildes, con mayusculas, con un punto al
 * final o con "la" delante ("la paz"). Todo eso se pliega antes de comparar. Los numeros se
 * comparan como numeros ({@code 12}, {@code 12.0} y {@code 12,0} son lo mismo) para que una
 * respuesta matematica no dependa de como se escriba el decimal.
 */
public final class AnswerMatcher {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9.\\-/^ ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern THOUSANDS = Pattern.compile("-?\\d{1,3}(?:,\\d{3})+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    /** Articulos que nadie deberia tener que escribir para acertar una capital o un elemento. */
    private static final Set<String> ARTICLES = Set.of("el", "la", "los", "las", "un", "una", "the", "a");

    private AnswerMatcher() {
    }

    /** True si {@code typed} vale por alguna de las respuestas aceptadas. */
    public static boolean matches(String typed, List<String> accepted) {
        if (typed == null || accepted == null || accepted.isEmpty()) return false;
        String guess = normalize(typed);
        if (guess.isEmpty()) return false;
        Double guessNumber = number(guess);
        for (String answer : accepted) {
            String expected = normalize(answer);
            if (expected.isEmpty()) continue;
            if (guess.equals(expected)) return true;
            Double expectedNumber = number(expected);
            if (guessNumber != null && expectedNumber != null
                    && Math.abs(guessNumber - expectedNumber) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minusculas, sin tildes, sin signos que no cambian la respuesta, espacios colapsados y
     * sin articulo delante. Se conservan {@code . - / ^} porque forman parte de numeros,
     * fracciones y potencias.
     */
    static String normalize(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).strip();
        // "300,000" o "25,000": coma de miles (como se escribe en Mexico), no decimal.
        if (THOUSANDS.matcher(s).matches()) s = s.replace(",", "");
        s = MARKS.matcher(s).replaceAll("").toLowerCase(Locale.ROOT).replace(',', '.');
        s = NOT_WORD.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).strip();
        int space = s.indexOf(' ');
        if (space > 0 && ARTICLES.contains(s.substring(0, space))) s = s.substring(space + 1);
        return s;
    }

    /** El valor si el texto (ya normalizado) es un numero y nada mas. */
    static Double number(String normalized) {
        if (!NUMBER.matcher(normalized).matches()) return null;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
