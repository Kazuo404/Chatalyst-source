package dev.chatalyst.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Efectos animados propios de los titulos (2026-10-15, KZ: "titulos animados y mejor diseñados").
 *
 * <ul>
 *   <li>{@code <flujo:#A:#B:#C>Texto</flujo>}: degradado en bucle continuo que fluye por el texto
 *       (la paleta es un circulo de color).</li>
 *   <li>{@code <brillo:#A:#B>Texto</brillo>}: degradado fijo con un destello claro que recorre el
 *       texto de izquierda a derecha y descansa un momento.</li>
 *   <li>{@code <glitch:#A:#B>Texto</glitch>}: degradado que "falla" a ratos.</li>
 *   <li>{@code <pulso:#A:#B>Texto</pulso>}: todo el texto late entre los colores (2026-10-18).</li>
 *   <li>{@code <chispa:#A:#B>Texto</chispa>}: degradado con 1 o 2 letras que titilan (2026-10-18).</li>
 * </ul>
 *
 * Solo el titulo flotante sobre la cabeza se refresca cuadro a cuadro ({@link #animate}). En el
 * chat, el TAB, los menus y los toasts una linea no se puede animar: ahi {@link #toStatic} los deja
 * como un {@code <gradient>} normal, para que ningun sitio pinte la etiqueta literal.
 */
public final class TitleEffects {

    private static final Pattern EFFECT = Pattern.compile(
            "(?is)<(flujo|brillo|glitch|pulso|chispa)((?::#[0-9a-f]{6}){1,12})>(.*?)</\\1>");
    /** Cuadros que tarda el flujo en dar la vuelta completa (con refresco de 2 ticks: 6 s). */
    static final int FLOW_STEPS = 60;
    private static final int FLOW_STOPS = 12;
    /** Medio ancho del destello, en letras, y cuadros de pausa entre pasadas. */
    private static final double SHINE_HALF_WIDTH = 2.5;
    private static final int SHINE_PAUSE = 30;
    private static final int[] SHINE = {0xFB, 0xF2, 0xFF};

    private TitleEffects() {
    }

    /** True si el texto lleva algun efecto propio (comprobacion barata antes de la regex). */
    public static boolean has(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.contains("<flujo") || lower.contains("<brillo") || lower.contains("<glitch")
                || lower.contains("<pulso") || lower.contains("<chispa");
    }

    private static final char TOKEN_OPEN = '';
    private static final char TOKEN_CLOSE = '';

    /**
     * Pasa {@code input} por {@code sanitize} (el filtro de etiquetas de los titulos) SIN perder los
     * efectos: ese filtro solo conoce etiquetas de estilo estandar y borraba {@code <flujo>} y
     * {@code <brillo>}, dejando el titulo sin color. Cada efecto se aparta como un marcador, se
     * filtra el resto y se devuelve con su contenido reducido a texto plano escapado.
     */
    public static String sanitizeKeeping(String input, java.util.function.UnaryOperator<String> sanitize) {
        if (!has(input)) return sanitize.apply(input);
        java.util.List<String> blocks = new java.util.ArrayList<>();
        Matcher m = EFFECT.matcher(input);
        StringBuilder marked = new StringBuilder();
        while (m.find()) {
            String kind = m.group(1).toLowerCase(Locale.ROOT);
            blocks.add("<" + kind + m.group(2) + ">" + MM.escape(MM.strip(m.group(3))) + "</" + kind + ">");
            m.appendReplacement(marked, Matcher.quoteReplacement(
                    "" + TOKEN_OPEN + (blocks.size() - 1) + TOKEN_CLOSE));
        }
        m.appendTail(marked);
        String out = sanitize.apply(marked.toString());
        for (int i = 0; i < blocks.size(); i++) {
            out = out.replace("" + TOKEN_OPEN + i + TOKEN_CLOSE, blocks.get(i));
        }
        return out;
    }

    /** Version fija de los efectos: un degradado normal con la misma paleta. */
    public static String toStatic(String input) {
        if (!has(input)) return input;
        return replace(input, (kind, colors, content) ->
                "<gradient" + colors + ">" + content + "</gradient>");
    }

    /** Cuadro {@code frame} de los efectos (el contador del nametag flotante). */
    public static String animate(String input, long frame) {
        if (!has(input)) return input;
        return replace(input, (kind, colors, content) -> switch (kind) {
            case "flujo" -> flow(palette(colors), frame, content);
            case "glitch" -> glitch(palette(colors), frame, MM.strip(content));
            case "pulso" -> pulse(palette(colors), frame, MM.strip(content));
            case "chispa" -> sparkle(palette(colors), frame, MM.strip(content));
            default -> shine(palette(colors), frame, MM.strip(content));
        });
    }

    /** Cuadros por latido (con refresco de 2 ticks: 1,5 s) y cuadros que tarda en subir. */
    static final int PULSE_STEPS = 15;
    private static final int PULSE_RISE = 4;

    /**
     * Todo el texto late a la vez entre los colores de la paleta, como un corazon: sube rapido
     * hasta el ultimo color y baja suave al primero. Cada letra conserva un leve degradado
     * (un cuarto de paleta) para que no quede plano.
     */
    static String pulse(int[][] palette, long frame, String plain) {
        int[] cps = plain.codePoints().toArray();
        int n = cps.length;
        if (n == 0) return "";
        double beat = pulseLevel(Math.floorMod(frame, PULSE_STEPS));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp = cps[i];
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                continue;
            }
            double spread = n == 1 ? 0 : (i / (double) (n - 1) - 0.5) * 0.25;
            appendColored(sb, open(palette, beat + spread), cp);
        }
        return sb.toString();
    }

    /** Nivel 0..1 del latido en el paso {@code step}: subida rapida y bajada suave. */
    static double pulseLevel(int step) {
        double t = step < PULSE_RISE
                ? step / (double) PULSE_RISE
                : 1 - (step - PULSE_RISE) / (double) (PULSE_STEPS - PULSE_RISE);
        return t * t * (3 - 2 * t);
    }

    /** Cuadros que dura cada estrella: aparece, brilla y se apaga. */
    static final int SPARKLE_LIFE = 6;
    private static final int[] SPARKLE = {0xFF, 0xFC, 0xF0};

    /**
     * Degradado fijo en el que, cada {@link #SPARKLE_LIFE} cuadros, 1 o 2 letras elegidas al azar
     * (determinista por cuadro) destellan casi blancas y se apagan, como estrellas titilando.
     */
    static String sparkle(int[][] palette, long frame, String plain) {
        int[] cps = plain.codePoints().toArray();
        int n = cps.length;
        if (n == 0) return "";
        long cycle = Math.floorDiv(frame, SPARKLE_LIFE);
        int phase = (int) Math.floorMod(frame, SPARKLE_LIFE);
        // Triangulo 0 -> 1 -> 0 a lo largo de la vida de la estrella, suavizado.
        double k = 1 - Math.abs(phase - (SPARKLE_LIFE - 1) / 2.0) / ((SPARKLE_LIFE - 1) / 2.0);
        k = k * k * (3 - 2 * k);
        java.util.Set<Integer> stars = sparkleLetters(cps, cycle);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp = cps[i];
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                continue;
            }
            int[] c = open(palette, n == 1 ? 0 : i / (double) (n - 1));
            if (stars.contains(i)) c = mix(c, SPARKLE, k * 0.9);
            appendColored(sb, c, cp);
        }
        return sb.toString();
    }

    /** Letras (nunca espacios) que destellan en el ciclo {@code cycle}: 1 o 2, distintas. */
    static java.util.Set<Integer> sparkleLetters(int[] cps, long cycle) {
        java.util.List<Integer> letters = new java.util.ArrayList<>();
        for (int i = 0; i < cps.length; i++) {
            if (!Character.isWhitespace(cps[i])) letters.add(i);
        }
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (letters.isEmpty()) return out;
        long h = mixBits(cycle * 0x9E3779B97F4A7C15L + 0x51ED27L);
        int count = Math.min(letters.size(), 1 + (int) Math.floorMod(h >>> 40, 2));
        for (int k = 0; out.size() < count && k < 8; k++) {
            out.add(letters.get((int) Math.floorMod(mixBits(h + k * 131L), letters.size())));
        }
        return out;
    }

    private static void appendColored(StringBuilder sb, int[] color, int cp) {
        sb.append('<').append(hex(color)).append('>');
        if (cp == '<' || cp == '\\') sb.append('\\');
        sb.appendCodePoint(cp);
    }

    /** Cuadros que dura cada "tic" del glitch y cuantos de cada 10 tics fallan. */
    private static final int GLITCH_TICK = 2;
    private static final int GLITCH_BURSTS = 3;
    private static final int[] GLITCH_FLASH = {0xFB, 0xF2, 0xFF};

    /**
     * Degradado fijo que "falla": en algunos tics unas letras se revuelven (obfuscated) y destellan,
     * y el degradado se invierte un instante. Pseudoaleatorio pero determinista por cuadro.
     */
    static String glitch(int[][] palette, long frame, String plain) {
        int[] cps = plain.codePoints().toArray();
        int n = cps.length;
        if (n == 0) return "";
        long h = mixBits(Math.floorDiv(frame, GLITCH_TICK));
        boolean burst = Math.floorMod(h, 10) < GLITCH_BURSTS;
        java.util.Set<Integer> rotas = new java.util.HashSet<>();
        if (burst) {
            int cuantas = 1 + (int) Math.floorMod(h >>> 8, Math.min(3, n));
            for (int k = 0; k < cuantas; k++) rotas.add((int) Math.floorMod(mixBits(h + k * 31L), n));
        }
        boolean invertir = burst && Math.floorMod(h >>> 16, 2) == 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp = cps[i];
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                continue;
            }
            double t = n == 1 ? 0 : i / (double) (n - 1);
            int[] c = open(palette, invertir ? 1 - t : t);
            boolean rota = rotas.contains(i);
            if (rota) c = mix(c, GLITCH_FLASH, 0.6);
            sb.append('<').append(hex(c)).append('>');
            if (rota) sb.append("<obf>");
            if (cp == '<' || cp == '\\') sb.append('\\');
            sb.appendCodePoint(cp);
            if (rota) sb.append("</obf>");
        }
        return sb.toString();
    }

    private static long mixBits(long x) {
        x = (x ^ (x >>> 33)) * 0xff51afd7ed558ccdL;
        x = (x ^ (x >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return x ^ (x >>> 33);
    }

    private interface Render {
        String apply(String kind, String colors, String content);
    }

    private static String replace(String input, Render render) {
        Matcher m = EFFECT.matcher(input);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String rendered = render.apply(m.group(1).toLowerCase(Locale.ROOT), m.group(2), m.group(3));
            m.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String flow(int[][] palette, long frame, String content) {
        double offset = Math.floorMod(frame, FLOW_STEPS) / (double) FLOW_STEPS;
        StringBuilder sb = new StringBuilder("<gradient");
        for (int i = 0; i < FLOW_STOPS; i++) {
            double t = (i / (double) (FLOW_STOPS - 1) + offset) % 1.0;
            sb.append(':').append(hex(cyclic(palette, t)));
        }
        return sb.append('>').append(content).append("</gradient>").toString();
    }

    static String shine(int[][] palette, long frame, String plain) {
        int[] cps = plain.codePoints().toArray();
        int n = cps.length;
        if (n == 0) return "";
        int period = n + (int) Math.ceil(SHINE_HALF_WIDTH * 2) + SHINE_PAUSE;
        double center = Math.floorMod(frame, period) - SHINE_HALF_WIDTH;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp = cps[i];
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                continue;
            }
            int[] base = open(palette, n == 1 ? 0 : i / (double) (n - 1));
            double k = Math.max(0, 1 - Math.abs(i - center) / SHINE_HALF_WIDTH);
            k = k * k * (3 - 2 * k); // suavizado: el destello entra y sale sin escalon
            int[] c = mix(base, SHINE, k * 0.85);
            sb.append('<').append(hex(c)).append('>');
            if (cp == '<' || cp == '\\') sb.append('\\');
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    static int[][] palette(String colors) {
        String[] parts = colors.substring(1).split(":"); // quita el ':' inicial
        int[][] out = new int[parts.length][3];
        for (int i = 0; i < parts.length; i++) {
            int rgb = Integer.parseInt(parts[i].substring(1), 16);
            out[i] = new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        }
        return out;
    }

    /** Paleta como circulo: la ultima parada vuelve a la primera. */
    private static int[] cyclic(int[][] p, double t) {
        int n = p.length;
        double s = t * n;
        int i = ((int) Math.floor(s)) % n;
        return mix(p[i], p[(i + 1) % n], s - Math.floor(s));
    }

    /** Paleta abierta: t=0 la primera parada, t=1 la ultima. */
    private static int[] open(int[][] p, double t) {
        if (p.length == 1) return p[0];
        double s = Math.min(1, Math.max(0, t)) * (p.length - 1);
        int i = Math.min((int) Math.floor(s), p.length - 2);
        return mix(p[i], p[i + 1], s - i);
    }

    private static int[] mix(int[] a, int[] b, double f) {
        return new int[]{
                (int) Math.round(a[0] + (b[0] - a[0]) * f),
                (int) Math.round(a[1] + (b[1] - a[1]) * f),
                (int) Math.round(a[2] + (b[2] - a[2]) * f)};
    }

    private static String hex(int[] c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c[0], c[1], c[2]);
    }
}
