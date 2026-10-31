package dev.chatalyst.announce;

import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Animaciones del /announce sobre los displays re-renderizables (title, actionbar y
 * bossbar; chat y toast son de un solo envio y quedan estaticos). Un ticker async genera
 * los fotogramas y cada envio se despacha al scheduler del jugador (Folia-safe), igual
 * que el resto de displays del plugin.
 *
 * <ul>
 *   <li><b>typewriter</b>: el texto se revela letra a letra (en plano, con el estilo del
 *       borrador aplicado al trozo visible) y se mantiene al completarse.</li>
 *   <li><b>expand</b>: como typewriter pero revelando desde el centro hacia afuera.</li>
 *   <li><b>glitch</b>: todo el texto aparece revuelto (obfuscated) y se va descifrando
 *       de izquierda a derecha hasta quedar legible.</li>
 *   <li><b>wave</b>: el gradiente/arcoiris del estilo fluye por el texto animando su fase;
 *       con un estilo liso cae a una ola arcoiris sobre el mensaje.</li>
 *   <li><b>frames</b>: rota una secuencia configurable de plantillas MiniMessage, incluidos
 *       colores, degradados y estilos, igual que la animacion por cuadros del nametag.</li>
 *   <li><b>pulse</b>: parpadeo suave alternando el estilo elegido con una version
 *       apagada en gris.</li>
 *   <li><b>flash</b>: parpadeo rapido de alarma alternando el estilo con rojo intenso.</li>
 *   <li><b>marquee</b>: el texto se desplaza en bucle como un letrero luminoso.</li>
 *   <li><b>countdown</b>: 3, 2, 1 (rojo/amarillo/verde) y despues el mensaje.</li>
 * </ul>
 */
public final class AnnounceAnimator {

    // 10 fps durante ~8 s, alineado con la vida de la bossbar temporizada de Displays.
    private static final long FRAME_MS = 100;
    private static final int TOTAL_FRAMES = 80;
    // typewriter/expand: caracteres revelados por fotograma.
    private static final int CHARS_PER_FRAME = 2;
    // pulse: fotogramas por medio ciclo (0.5 s encendido, 0.5 s apagado).
    private static final int PULSE_HALF_CYCLE = 5;
    // flash: medio ciclo mas rapido (0.3 s), es una alarma.
    private static final int FLASH_HALF_CYCLE = 3;
    // wave: fotogramas de un ciclo completo del gradiente.
    private static final int WAVE_CYCLE = 20;
    // glitch: fotogramas que tarda el texto en descifrarse del todo (~5 s, luego se mantiene).
    private static final int GLITCH_REVEAL_FRAMES = 50;
    // marquee: ancho de la ventana visible y separador entre repeticiones del mensaje.
    private static final int MARQUEE_WIDTH = 24;
    private static final String MARQUEE_GAP = "   ✦   ";
    // countdown: fotogramas por numero (1 s cada uno: 3, 2, 1).
    private static final int COUNTDOWN_FRAMES = 10;
    // El ticker avanza una vez cada 100 ms = dos ticks de Minecraft.
    private static final int MINECRAFT_TICKS_PER_FRAME = 2;
    private static final int DEFAULT_FRAME_INTERVAL_TICKS = 6;
    private static final int MAX_CONFIGURED_FRAMES = 32;
    private static final int MAX_FRAME_TEMPLATE_LENGTH = 2048;
    private static final List<String> DEFAULT_FRAME_TEMPLATES = List.of(
            "<gradient:#240046:#7B2CBF>{msg}</gradient>",
            "<gradient:#7B2CBF:#9D4EDD>{msg}</gradient>",
            "<gradient:#9D4EDD:#C77DFF>{msg}</gradient>",
            "<gradient:#C77DFF:#E0AAFF>{msg}</gradient>");

    private AnnounceAnimator() {
    }

    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final Map<UUID, Long> TITLE_GENERATIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> ACTIONBAR_GENERATIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, BossBar> PLAYER_BARS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Invalida la animacion anterior de estas superficies para cada destinatario. Una vista
     * previa solo reemplaza lo que ve ese admin; un broadcast reemplaza la animacion anterior
     * de todos y evita titles/actionbars intercalados o bossbars apiladas.
     */
    public static void cancel(JavaPlugin plugin, List<? extends Player> targets, String display) {
        boolean title = "title".equals(display) || "epic".equals(display);
        boolean actionbar = "actionbar".equals(display);
        boolean bossbar = "bossbar".equals(display) || "epic".equals(display);
        for (Player player : targets) {
            UUID id = player.getUniqueId();
            // Quitar la entrada (en vez de subir la generacion) basta para invalidar: un ticker
            // en vuelo ve null y se detiene. Ademas, si el nuevo envio NO es animado (anim=none,
            // el valor por defecto) nadie llamaria a unregister, asi que un put dejaria la
            // entrada colgada para siempre — una fuga lenta por cada jugador que reciba un
            // anuncio no animado de title/actionbar. El camino animado vuelve a registrar.
            if (title) TITLE_GENERATIONS.remove(id);
            if (actionbar) ACTIONBAR_GENERATIONS.remove(id);
            if (bossbar) {
                BossBar previous = PLAYER_BARS.remove(id);
                if (previous != null) {
                    FoliaTasks.entity(plugin, player, p -> p.hideBossBar(previous));
                }
            }
        }
    }

    /** Title animado: un fotograma por tick del ticker, el ultimo con fade-out normal. */
    public static void title(JavaPlugin plugin, List<? extends Player> targets,
                             String anim, String template, String message) {
        title(plugin, targets, anim, template, message, Component.empty());
    }

    /** Igual, con un subtitulo fijo debajo (el anuncio de un ban: quien y por quien). */
    public static void title(JavaPlugin plugin, List<? extends Player> targets,
                             String anim, String template, String message, Component subtitle) {
        List<? extends Player> snapshot = List.copyOf(targets);
        FrameSequence sequence = frameSequence(plugin);
        long generation = register(TITLE_GENERATIONS, snapshot);
        var perFrame = Title.Times.times(Duration.ZERO, Duration.ofMillis(FRAME_MS * 4), Duration.ZERO);
        var lastFrame = Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(1));
        run(plugin, (frame, done) -> {
            if (!hasCurrent(snapshot, TITLE_GENERATIONS, generation)) return false;
            Component text = frame(anim, template, message, frame,
                    sequence.templates(), sequence.intervalTicks());
            var title = Title.title(text, subtitle, done ? lastFrame : perFrame);
            sendCurrent(plugin, snapshot, TITLE_GENERATIONS, generation, p -> p.showTitle(title));
            return true;
        }, () -> unregister(TITLE_GENERATIONS, snapshot, generation));
    }

    /** Action bar animada; el cliente la desvanece solo al terminar. */
    public static void actionbar(JavaPlugin plugin, List<? extends Player> targets,
                                 String anim, String template, String message) {
        List<? extends Player> snapshot = List.copyOf(targets);
        FrameSequence sequence = frameSequence(plugin);
        long generation = register(ACTIONBAR_GENERATIONS, snapshot);
        run(plugin, (frame, done) -> {
            if (!hasCurrent(snapshot, ACTIONBAR_GENERATIONS, generation)) return false;
            Component text = frame(anim, template, message, frame,
                    sequence.templates(), sequence.intervalTicks());
            sendCurrent(plugin, snapshot, ACTIONBAR_GENERATIONS, generation, p -> p.sendActionBar(text));
            return true;
        }, () -> unregister(ACTIONBAR_GENERATIONS, snapshot, generation));
    }

    // Barras en vuelo: si el plugin se apaga a mitad de animacion, el ticker muere sin su
    // cleanup y la barra quedaria visible hasta relog; onDisable las esconde via shutdownAll.
    private static final java.util.Set<BossBar> ACTIVE_BARS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Bossbar animada: ademas del texto, la barra se vacia durante su vida (drain). */
    public static void bossbar(JavaPlugin plugin, List<? extends Player> targets,
                               String anim, String template, String message) {
        List<? extends Player> snapshot = List.copyOf(targets);
        FrameSequence sequence = frameSequence(plugin);
        BossBar bar = BossBar.bossBar(frame(anim, template, message, 0,
                        sequence.templates(), sequence.intervalTicks()), 1.0f,
                BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        ACTIVE_BARS.add(bar);
        for (Player player : snapshot) {
            PLAYER_BARS.put(player.getUniqueId(), bar);
            FoliaTasks.entity(plugin, player, p -> {
                if (PLAYER_BARS.get(p.getUniqueId()) == bar) p.showBossBar(bar);
            });
        }
        run(plugin, (frame, done) -> {
            if (snapshot.stream().noneMatch(p -> PLAYER_BARS.get(p.getUniqueId()) == bar)) return false;
            // La API de Adventure para bossbars es segura entre hilos (viewers copy-on-write).
            bar.name(frame(anim, template, message, frame,
                    sequence.templates(), sequence.intervalTicks()));
            bar.progress(Math.max(0f, 1f - (float) frame / TOTAL_FRAMES));
            return true;
        }, () -> {
            ACTIVE_BARS.remove(bar);
            for (Player player : snapshot) {
                PLAYER_BARS.remove(player.getUniqueId(), bar);
                FoliaTasks.entity(plugin, player, p -> p.hideBossBar(bar));
            }
        });
    }

    /** Esconde las bossbars de animaciones aun en vuelo (llamar desde onDisable). */
    public static void shutdownAll(JavaPlugin plugin) {
        // En Folia el shutdown detiene los ticks regionales antes de onDisable. Tocar aqui
        // jugadores de varias regiones seria ilegal; la desconexion elimina sus viewers.
        if (!FoliaTasks.isFolia()) {
            for (BossBar bar : ACTIVE_BARS) {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    p.hideBossBar(bar);
                }
            }
        }
        PLAYER_BARS.clear();
        TITLE_GENERATIONS.clear();
        ACTIONBAR_GENERATIONS.clear();
        ACTIVE_BARS.clear();
    }

    private static long register(Map<UUID, Long> generations, List<? extends Player> targets) {
        long generation = NEXT_GENERATION.incrementAndGet();
        targets.forEach(player -> generations.put(player.getUniqueId(), generation));
        return generation;
    }

    private static void unregister(Map<UUID, Long> generations,
                                   List<? extends Player> targets, long generation) {
        targets.forEach(player -> generations.remove(player.getUniqueId(), generation));
    }

    private static boolean hasCurrent(List<? extends Player> targets,
                                      Map<UUID, Long> generations, long generation) {
        Long expected = generation;
        return targets.stream().anyMatch(player ->
                expected.equals(generations.get(player.getUniqueId())));
    }

    private static void sendCurrent(JavaPlugin plugin, List<? extends Player> targets,
                                    Map<UUID, Long> generations, long generation,
                                    java.util.function.Consumer<Player> action) {
        for (Player player : targets) {
            if (!Long.valueOf(generation).equals(generations.get(player.getUniqueId()))) continue;
            FoliaTasks.entity(plugin, player, p -> {
                if (Long.valueOf(generation).equals(generations.get(p.getUniqueId()))) action.accept(p);
            });
        }
    }

    /** Ticker de fotogramas: emite (frame, esUltimo) y al acabar corre el cleanup. */
    private static void run(JavaPlugin plugin, FrameSink sink, Runnable onEnd) {
        AtomicInteger counter = new AtomicInteger();
        try {
            plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
                int frame = counter.getAndIncrement();
                if (frame >= TOTAL_FRAMES) {
                    task.cancel();
                    finish(plugin, onEnd);
                    return;
                }
                try {
                    if (!sink.accept(frame, frame == TOTAL_FRAMES - 1)) {
                        task.cancel();
                        finish(plugin, onEnd);
                    }
                } catch (Throwable t) {
                    // Un fotograma roto (p. ej. plantilla exotica) no debe dejar el ticker vivo.
                    task.cancel();
                    plugin.getLogger().warning("Announcement animation stopped: " + t.getMessage());
                    finish(plugin, onEnd);
                }
            }, FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not start announcement animation: " + t.getMessage());
            finish(plugin, onEnd);
        }
    }

    private static void finish(JavaPlugin plugin, Runnable onEnd) {
        if (onEnd == null) return;
        try {
            onEnd.run();
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not clean up announcement animation: " + t.getMessage());
        }
    }

    @FunctionalInterface
    private interface FrameSink {
        boolean accept(int frame, boolean last);
    }

    /** Renderiza el fotograma N del mensaje segun la animacion elegida. */
    public static Component frame(String anim, String template, String message, int frame) {
        return frame(anim, template, message, frame,
                DEFAULT_FRAME_TEMPLATES, DEFAULT_FRAME_INTERVAL_TICKS);
    }

    /** Variante con secuencia explicita, usada por el preset configurable y sus pruebas. */
    public static Component frame(String anim, String template, String message, int frame,
                           List<String> frameTemplates, int intervalTicks) {
        return switch (anim) {
            case "typewriter" -> {
                // En plano y escapado: cortar MiniMessage/legacy a mitad de tag lo romperia.
                int[] plain = codePointsOf(message);
                int shown = Math.min(plain.length, (frame + 1) * CHARS_PER_FRAME);
                yield MM.full(template.replace("{msg}", MM.escape(text(plain, 0, shown))));
            }
            case "expand" -> {
                int[] plain = codePointsOf(message);
                int mid = plain.length / 2;
                int radius = (frame + 1) * CHARS_PER_FRAME;
                int from = Math.max(0, mid - radius);
                int to = Math.min(plain.length, mid + radius);
                yield MM.full(template.replace("{msg}", MM.escape(text(plain, from, to))));
            }
            case "glitch" -> {
                int[] plain = codePointsOf(message);
                int shown = Math.min(plain.length,
                        (int) Math.ceil(plain.length * (frame + 1) / (double) GLITCH_REVEAL_FRAMES));
                String tail = text(plain, shown, plain.length);
                yield MM.full(template.replace("{msg}", MM.escape(text(plain, 0, shown))
                        + (tail.isEmpty() ? "" : "<obfuscated>" + MM.escape(tail) + "</obfuscated>")));
            }
            case "marquee" -> {
                // Ventana deslizante sobre el mensaje en bucle, como un letrero luminoso.
                int[] loop = (plainOf(message) + MARQUEE_GAP).codePoints().toArray();
                int width = Math.min(MARQUEE_WIDTH, loop.length);
                int start = frame % loop.length;
                StringBuilder window = new StringBuilder(width);
                for (int i = 0; i < width; i++) {
                    window.appendCodePoint(loop[(start + i) % loop.length]);
                }
                yield MM.full(template.replace("{msg}", MM.escape(window.toString())));
            }
            case "wave" -> {
                String own = waveOwnColors(Legacy.toMini(template.replace("{msg}", message)), frame);
                yield MM.full(own != null ? own : waveWrap(template, message, frame));
            }
            case "frames" -> {
                List<String> frames = frameTemplates == null || frameTemplates.isEmpty()
                        ? DEFAULT_FRAME_TEMPLATES : frameTemplates;
                int ticks = Math.max(MINECRAFT_TICKS_PER_FRAME, intervalTicks);
                int sequenceIndex = Math.floorMod(
                        frame * MINECRAFT_TICKS_PER_FRAME / ticks, frames.size());
                yield MM.full(frames.get(sequenceIndex).replace("{msg}", Legacy.toMini(message)));
            }
            case "pulse" -> (frame / PULSE_HALF_CYCLE) % 2 == 0
                    ? MM.full(template.replace("{msg}", Legacy.toMini(message)))
                    : MM.full("<#7B68A0>" + MM.escape(plainOf(message)));
            case "flash" -> (frame / FLASH_HALF_CYCLE) % 2 == 0
                    ? MM.full(template.replace("{msg}", Legacy.toMini(message)))
                    : MM.full("<#FF4D6D><bold>" + MM.escape(plainOf(message)));
            case "countdown" -> {
                if (frame < COUNTDOWN_FRAMES * 3) {
                    int n = 3 - frame / COUNTDOWN_FRAMES;
                    String color = switch (n) {
                        case 3 -> "<#FF4D6D>";
                        case 2 -> "<#FFD166>";
                        default -> "<#3DFFA8>";
                    };
                    yield MM.full(color + "<bold>" + n);
                }
                yield MM.full(template.replace("{msg}", Legacy.toMini(message)));
            }
            default -> MM.full(template.replace("{msg}", Legacy.toMini(message)));
        };
    }

    /**
     * Captura la configuracion al arrancar la animacion. Asi un reload durante el ticker no
     * mezcla dos secuencias, y una lista malformada nunca deja la animacion sin fallback.
     */
    private static FrameSequence frameSequence(JavaPlugin plugin) {
        List<String> configured = plugin.getConfig().getStringList("Announcer.Animation.Frames");
        java.util.ArrayList<String> accepted = new java.util.ArrayList<>();
        for (String candidate : configured) {
            if (candidate == null || candidate.isBlank()
                    || candidate.length() > MAX_FRAME_TEMPLATE_LENGTH) continue;
            accepted.add(candidate);
            if (accepted.size() == MAX_CONFIGURED_FRAMES) break;
        }
        List<String> templates = accepted.isEmpty()
                ? DEFAULT_FRAME_TEMPLATES : List.copyOf(accepted);
        int interval = plugin.getConfig().getInt(
                "Announcer.Animation.Interval_Ticks", DEFAULT_FRAME_INTERVAL_TICKS);
        interval = Math.max(MINECRAFT_TICKS_PER_FRAME, Math.min(200, interval));
        return new FrameSequence(templates, interval);
    }

    private record FrameSequence(List<String> templates, int intervalTicks) {
    }

    /** Mensaje en texto plano (sin legacy ni MiniMessage), para animaciones que cortan. */
    private static String plainOf(String message) {
        return MM.strip(Legacy.toMini(message));
    }

    private static int[] codePointsOf(String message) {
        return plainOf(message).codePoints().toArray();
    }

    private static String text(int[] codePoints, int from, int to) {
        return new String(codePoints, from, to - from);
    }

    // Acepta todas las formas validas: <rainbow>, <rainbow:!>, <rainbow:2>, <rainbow:!2>, <rainbow:-3>.
    private static final java.util.regex.Pattern RAINBOW_TAG =
            java.util.regex.Pattern.compile("<rainbow(?::!?-?\\d*)?>");
    private static final java.util.regex.Pattern GRADIENT_TAG =
            java.util.regex.Pattern.compile("<gradient:([^>]+)>");
    // Fase ya presente al final de un gradiente de config, en cualquier forma que acepte
    // Double.parseDouble ("0.5", "+0.5", "1.", ".5", "1e-1").
    private static final java.util.regex.Pattern TRAILING_PHASE =
            java.util.regex.Pattern.compile(":[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$");

    /**
     * Inyecta la fase del fotograma en el tag de color de la plantilla: los gradientes
     * aceptan una fase decimal [-1,1] y el arcoiris una entera [0,9]. Si el gradiente de
     * la config ya traia fase, se sustituye (dos fases = tag invalido que se veria en
     * crudo). Un estilo liso no tiene nada que desplazar: cae a un arcoiris del mensaje.
     */
    static String withPhase(String template, int frame) {
        var rainbow = RAINBOW_TAG.matcher(template);
        if (rainbow.find()) {
            return template.substring(0, rainbow.start())
                    + "<rainbow:" + rainbowPhase(frame) + ">"
                    + template.substring(rainbow.end());
        }
        var gradient = GRADIENT_TAG.matcher(template);
        if (gradient.find()) {
            double phase = -1.0 + 2.0 * (frame % WAVE_CYCLE) / WAVE_CYCLE;
            String colors = TRAILING_PHASE.matcher(gradient.group(1)).replaceFirst("");
            return template.substring(0, gradient.start())
                    + "<gradient:" + colors + ":" + String.format(Locale.ROOT, "%.2f", phase) + ">"
                    + template.substring(gradient.end());
        }
        return template.replace("{msg}", "<rainbow:" + rainbowPhase(frame) + ">{msg}</rainbow>");
    }

    private static final java.util.regex.Pattern HEX_OPEN =
            java.util.regex.Pattern.compile("<(?:color:|c:)?(#[0-9a-fA-F]{6})>");
    private static final java.util.regex.Pattern COLOR_CLOSE =
            java.util.regex.Pattern.compile("</(?:#[0-9a-fA-F]{6}|color|c)(?::#[0-9a-fA-F]{6})?>");

    /**
     * Onda sobre los colores que ya trae el texto (p. ej. un cartel con un color por letra):
     * cada letra toma el color de la que tiene {@code frame} posiciones detras, asi el
     * degradado propio recorre la palabra sin perder su paleta. Las etiquetas que no son de
     * color (bold, italic...) se dejan donde estaban. Devuelve null si el texto no trae al
     * menos dos colores hex propios o usa etiquetas que no se pueden repintar letra a letra
     * (gradient, rainbow, transition...): entonces se usa la onda de siempre.
     */
    static String waveOwnColors(String mini, int frame) {
        if (mini == null || mini.isEmpty()) return null;
        String lower = mini.toLowerCase(Locale.ROOT);
        if (lower.contains("<gradient") || lower.contains("<rainbow") || lower.contains("<transition")
                || lower.contains("\\<")) return null;
        // Primera pasada: color de cada letra visible y las piezas (etiqueta o letra).
        List<String> pieces = new java.util.ArrayList<>();
        List<Boolean> isChar = new java.util.ArrayList<>();
        List<String> colors = new java.util.ArrayList<>();
        String current = null;
        int i = 0;
        while (i < mini.length()) {
            char ch = mini.charAt(i);
            if (ch == '<') {
                int end = mini.indexOf('>', i);
                if (end < 0) return null;
                String tag = mini.substring(i, end + 1);
                var open = HEX_OPEN.matcher(tag);
                if (open.matches()) {
                    current = open.group(1);
                } else if (!COLOR_CLOSE.matcher(tag).matches()) {
                    pieces.add(tag);
                    isChar.add(false);
                }
                i = end + 1;
                continue;
            }
            int cp = mini.codePointAt(i);
            if (current == null) return null; // letras sin color propio: no hay paleta que mover
            pieces.add(new String(Character.toChars(cp)));
            isChar.add(true);
            colors.add(current);
            i += Character.charCount(cp);
        }
        if (colors.size() < 2 || colors.stream().distinct().count() < 2) return null;
        int n = colors.size();
        StringBuilder out = new StringBuilder(mini.length() + n * 8);
        int index = 0;
        for (int p = 0; p < pieces.size(); p++) {
            if (!isChar.get(p)) {
                out.append(pieces.get(p));
                continue;
            }
            String color = colors.get(Math.floorMod(index - frame, n));
            out.append('<').append(color).append('>').append(MM.escape(pieces.get(p)))
                    .append("</").append(color).append('>');
            index++;
        }
        return out.toString();
    }

    /**
     * Onda clasica (fase del gradiente o arcoiris). Si el mensaje trae etiquetas propias no
     * se mete dentro de un {@code <rainbow>}: sus cierres cerrarian tambien el arcoiris y el
     * {@code </rainbow>} final saldria escrito en el cartel. En ese caso se colorea el texto
     * plano.
     */
    static String waveWrap(String template, String message, int frame) {
        String phased = withPhase(template, frame);
        String mini = Legacy.toMini(message);
        if (!phased.contains("<rainbow:" + rainbowPhase(frame) + ">{msg}</rainbow>") || !mini.contains("<")) {
            return phased.replace("{msg}", mini);
        }
        return phased.replace("{msg}", MM.escape(MM.strip(mini)));
    }

    private static int rainbowPhase(int frame) {
        return (frame / 2) % 10;
    }
}
