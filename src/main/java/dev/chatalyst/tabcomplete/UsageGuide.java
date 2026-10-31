package dev.chatalyst.tabcomplete;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Guia de argumentos para el TAB, sacada del {@code usage:} de plugin.yml.
 *
 * <p>Muchos jugadores no saben que va en cada hueco de un comando. Cuando el autocompletado
 * normal no tiene nada que ofrecer, esta clase dice que se espera en esa posicion
 * ({@code <jugador>}, {@code <formato>}, {@code [pagina]}...) y, si el usage nombra palabras
 * fijas ({@code accept}, {@code list}...), las ofrece tambien.
 *
 * <p>Gramatica del usage (la que ya usa plugin.yml):
 * <ul>
 *   <li>{@code palabra}: literal que se escribe tal cual.</li>
 *   <li>{@code <x>} / {@code [x]}: hueco obligatorio / opcional. Si dentro hay {@code |} sin
 *       espacios ({@code <nickname|off>}) es UN hueco que se muestra entero.</li>
 *   <li>{@code <a b|c>} / {@code [a b|c]}: grupo con alternativas de varias palabras; se
 *       expande en secuencias.</li>
 *   <li>{@code |} u {@code " or "} al nivel superior: formas alternativas del comando.</li>
 * </ul>
 *
 * <p>Sin Bukkit: se prueba con tests unitarios y el listener solo la consulta.
 */
public final class UsageGuide {

    /** Tope de secuencias expandidas: un usage patologico no debe costar memoria. */
    private static final int MAX_SEQUENCES = 512;

    /** Palabras sueltas dentro de un grupo que en realidad nombran un hueco, no un literal. */
    private static final Set<String> SLOT_WORDS = Set.of(
            "name", "player", "message", "msg", "text", "id", "nickname", "color");

    /** Traduccion de los nombres de hueco para quien juega en espanol. */
    private static final Map<String, String> ES = Map.ofEntries(
            Map.entry("player", "jugador"), Map.entry("player...", "jugador..."),
            Map.entry("message", "mensaje"), Map.entry("msg", "mensaje"),
            Map.entry("text", "texto"), Map.entry("format", "formato"),
            Map.entry("name", "nombre"), Map.entry("reason", "motivo"),
            Map.entry("time", "tiempo"), Map.entry("amount", "cantidad"),
            Map.entry("price", "precio"), Map.entry("page", "pagina"),
            Map.entry("count", "cantidad"), Map.entry("date", "fecha"),
            Map.entry("action", "accion"), Map.entry("nickname", "apodo"),
            Map.entry("title", "titulo"), Map.entry("key", "clave"),
            Map.entry("seconds", "segundos"), Map.entry("rule", "regla"),
            Map.entry("rank", "rango"), Map.entry("product", "producto"),
            Map.entry("category", "categoria"), Map.entry("min", "minimo"),
            Map.entry("staff", "staff"), Map.entry("first", "first"));

    sealed interface Token permits Literal, Slot {
    }

    /** Palabra fija del comando. */
    record Literal(String word) implements Token {
    }

    /** Hueco a rellenar; {@code display} es el texto original ({@code <player>}). */
    record Slot(String display) implements Token {
    }

    private final List<List<Token>> sequences;
    private final Set<String> slotDisplays;

    private UsageGuide(List<List<Token>> sequences) {
        this.sequences = sequences;
        Set<String> displays = new LinkedHashSet<>();
        for (List<Token> seq : sequences) {
            for (Token t : seq) {
                if (t instanceof Slot s) {
                    displays.add(s.display().toLowerCase(Locale.ROOT));
                    displays.add(translate(s.display(), true).toLowerCase(Locale.ROOT));
                }
            }
        }
        this.slotDisplays = Set.copyOf(displays);
    }

    /** Parsea un usage completo ({@code /cmd ...}); null o vacio = guia vacia. */
    public static UsageGuide parse(String usage) {
        if (usage == null || usage.isBlank()) return new UsageGuide(List.of());
        String body = usage.trim();
        // "/tag [a] or /tag <b> ...": cada forma alternativa empieza otra vez por /cmd.
        body = body.replaceAll("\\s+or\\s+/\\S+", " | ");
        body = body.replaceFirst("^/\\S+\\s*", "");
        try {
            List<List<Token>> seqs = new Parser(body).alternatives(')');
            return new UsageGuide(List.copyOf(seqs));
        } catch (RuntimeException malformed) {
            // Un usage mal escrito no debe romper el TAB: simplemente no hay guia.
            return new UsageGuide(List.of());
        }
    }

    List<List<Token>> sequences() {
        return sequences;
    }

    /**
     * Sugerencias para el ultimo argumento de {@code args} (el que se esta escribiendo; puede
     * ser ""). Devuelve literales que encajan con lo escrito y, si el argumento esta vacio,
     * los huecos que tocan en esa posicion.
     */
    public List<String> suggest(String[] args, boolean spanish) {
        if (args.length == 0) return List.of();
        int pos = args.length - 1;
        String typed = args[pos].toLowerCase(Locale.ROOT);
        boolean empty = typed.isEmpty() || typed.startsWith("<") || typed.startsWith("[");
        Set<String> out = new LinkedHashSet<>();
        for (List<Token> seq : sequences) {
            if (pos >= seq.size() || !prefixMatches(seq, args, pos)) continue;
            Token t = seq.get(pos);
            if (t instanceof Literal l) {
                if (l.word().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(l.word());
            } else if (t instanceof Slot s && empty) {
                out.add(translate(s.display(), spanish));
            }
        }
        return List.copyOf(out);
    }

    /** True si {@code arg} es literalmente uno de los huecos ({@code <jugador>}) de esta guia. */
    public boolean isSlotPlaceholder(String arg) {
        return arg != null && (arg.startsWith("<") || arg.startsWith("["))
                && slotDisplays.contains(arg.toLowerCase(Locale.ROOT));
    }

    private static boolean prefixMatches(List<Token> seq, String[] args, int upto) {
        for (int i = 0; i < upto; i++) {
            if (seq.get(i) instanceof Literal l && !l.word().equalsIgnoreCase(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** Traduce las palabras de un hueco conservando simbolos: {@code <player|off>} -> {@code <jugador|off>}. */
    static String translate(String display, boolean spanish) {
        if (!spanish) return display;
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= display.length(); i++) {
            char c = i < display.length() ? display.charAt(i) : '|';
            boolean wordChar = i < display.length()
                    && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_');
            if (wordChar) {
                word.append(c);
                continue;
            }
            if (!word.isEmpty()) {
                String w = word.toString();
                out.append(ES.getOrDefault(w.toLowerCase(Locale.ROOT), w));
                word.setLength(0);
            }
            if (i < display.length()) out.append(c);
        }
        return out.toString();
    }

    /** Descenso recursivo sobre el usage; expande a secuencias de tokens. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        /** alternativas := seq ('|' seq)* hasta {@code close} o el final. */
        List<List<Token>> alternatives(char close) {
            List<List<Token>> all = new ArrayList<>();
            while (true) {
                all.addAll(sequence(close));
                cap(all);
                skipSpaces();
                if (i < s.length() && s.charAt(i) == '|') {
                    i++;
                    continue;
                }
                return all;
            }
        }

        /** seq := item* ; cada item multiplica las secuencias acumuladas. */
        private List<List<Token>> sequence(char close) {
            List<List<Token>> acc = new ArrayList<>();
            acc.add(List.of());
            while (true) {
                skipSpaces();
                if (i >= s.length()) return acc;
                char c = s.charAt(i);
                if (c == '|' || c == close) return acc;
                List<List<Token>> options = item();
                List<List<Token>> next = new ArrayList<>();
                for (List<Token> prefix : acc) {
                    for (List<Token> opt : options) {
                        List<Token> joined = new ArrayList<>(prefix.size() + opt.size());
                        joined.addAll(prefix);
                        joined.addAll(opt);
                        next.add(List.copyOf(joined));
                    }
                }
                cap(next);
                acc = next;
            }
        }

        private List<List<Token>> item() {
            char c = s.charAt(i);
            if (c == '<' || c == '[') {
                char close = c == '<' ? '>' : ']';
                int end = matching(i, c, close);
                String inner = s.substring(i + 1, end);
                String whole = s.substring(i, end + 1);
                boolean simple = inner.indexOf(' ') < 0 && inner.indexOf('<') < 0
                        && inner.indexOf('[') < 0;
                List<List<Token>> options;
                if (simple) {
                    options = List.of(List.of(new Slot(whole)));
                } else {
                    i++;
                    options = alternatives(close);
                    expect(close);
                    options = options.stream().map(Parser::slotWords).toList();
                }
                i = end + 1;
                if (c == '[') {
                    List<List<Token>> withEmpty = new ArrayList<>(options);
                    withEmpty.add(List.of()); // al final: se sugiere antes rellenar que saltar
                    return withEmpty;
                }
                return options;
            }
            int start = i;
            while (i < s.length() && " |<>[]".indexOf(s.charAt(i)) < 0) i++;
            if (start == i) throw new IllegalArgumentException("unexpected '" + c + "'");
            return List.of(List.of(new Literal(s.substring(start, i))));
        }

        /** Dentro de un grupo, {@code name}/{@code player} sueltos son huecos, no literales. */
        private static List<Token> slotWords(List<Token> seq) {
            List<Token> out = new ArrayList<>(seq.size());
            for (Token t : seq) {
                if (t instanceof Literal l
                        && SLOT_WORDS.contains(l.word().toLowerCase(Locale.ROOT))) {
                    out.add(new Slot("<" + l.word() + ">"));
                } else {
                    out.add(t);
                }
            }
            return List.copyOf(out);
        }

        private int matching(int from, char open, char close) {
            int depth = 0;
            for (int j = from; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '<' || c == '[') depth++;
                else if (c == '>' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        if (c != close) throw new IllegalArgumentException("mismatched " + open);
                        return j;
                    }
                }
            }
            throw new IllegalArgumentException("unclosed " + open);
        }

        private void expect(char c) {
            skipSpaces();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c);
            }
        }

        private void skipSpaces() {
            while (i < s.length() && s.charAt(i) == ' ') i++;
        }

        private static void cap(List<?> list) {
            if (list.size() > MAX_SEQUENCES) {
                throw new IllegalArgumentException("usage expands too much");
            }
        }
    }
}
