package dev.chatalyst.tablist;

import dev.chatalyst.util.MM;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Configuracion y render puro compartido por el nametag flotante, TAB y chat. */
final class NametagAnimation {

    // Mas pasos = cada cuadro mueve el gradiente una fraccion menor -> desliz mas fluido (el
    // ciclo completo dura WAVE_STEPS * Interval_Ticks / 20 segundos).
    private static final int WAVE_STEPS = 60;
    // FLOW: bucle continuo y sin costura. La paleta se trata como un CIRCULO de color; cada
    // cuadro rota el muestreo una fraccion, y el gradiente se reconstruye con FLOW_STOPS paradas
    // cuyo primer y ultimo color coinciden -> el ciclo cierra perfecto tras FLOW_STEPS cuadros.
    private static final int FLOW_STEPS = 60;
    private static final int FLOW_STOPS = 12;
    private static final int MAX_FRAMES = 32;
    private static final int MAX_TEMPLATE_LENGTH = 2048;

    enum Mode { OFF, FRAMES, WAVE, FLOW }

    record Settings(Mode mode, int intervalTicks, int tabIntervalTicks,
                    List<String> frames, String waveContent, String waveColors,
                    boolean applyToTab, boolean applyToChat) {

        static Settings off() {
            return new Settings(Mode.OFF, 6, 10, List.of(), "{player_nick}",
                    "#FFD700:#FF4D6D:#FFD700", false, false);
        }

        boolean enabled() {
            return mode != Mode.OFF;
        }
    }

    private NametagAnimation() {
    }

    static Settings load(FileConfiguration config) {
        String root = "Nametag.Fancy.Animation";
        boolean enabled = config.getBoolean(root + ".Enabled", false);
        var section = config.getConfigurationSection(root);
        if (!enabled || section == null) return Settings.off();
        Settings parsed = parse(section,
                config.getBoolean(root + ".Apply_To.Tab", true),
                config.getBoolean(root + ".Apply_To.Chat", true),
                false); // el global NO cae a onda: frames vacio = OFF (comportamiento historico)
        if (parsed.mode() == Mode.FRAMES && parsed.frames().isEmpty()) {
            // OFF conservando los intervalos ya clampados (no los de Settings.off()).
            return new Settings(Mode.OFF, parsed.intervalTicks(), parsed.tabIntervalTicks(),
                    parsed.frames(), parsed.waveContent(), parsed.waveColors(),
                    parsed.applyToTab(), parsed.applyToChat());
        }
        return parsed;
    }

    /** True si cada jugador puede elegir su propia animacion (via el GUI). */
    static boolean perPlayer(FileConfiguration config) {
        return config.getBoolean("Nametag.Fancy.Animation.Per_Player", false);
    }

    /**
     * Presets que un jugador puede ELEGIR (Nametag.Fancy.Animation.Presets): id -> Settings.
     * Nunca aplican al chat (una linea ya enviada no se anima); solo nametag flotante y tab.
     */
    static java.util.Map<String, Settings> loadPresets(FileConfiguration config) {
        java.util.Map<String, Settings> out = new java.util.LinkedHashMap<>();
        var presets = config.getConfigurationSection("Nametag.Fancy.Animation.Presets");
        if (presets == null) return out;
        for (String id : presets.getKeys(false)) {
            var section = presets.getConfigurationSection(id);
            if (section == null) continue;
            Settings s = parse(section, true, false, true); // un preset siempre anima
            if (s.enabled()) out.put(id.toLowerCase(Locale.ROOT), s);
        }
        return out;
    }

    /**
     * Parsea Mode/Frames/Wave de una seccion (global o preset). {@code framesFallbackWave}: si en
     * modo frames no hay frames validos, un preset cae a FLOW (siempre anima) mientras que el
     * global conserva el modo FRAMES para que {@link #load} lo apague.
     */
    private static Settings parse(org.bukkit.configuration.ConfigurationSection section,
                                  boolean applyToTab, boolean applyToChat, boolean framesFallbackWave) {
        String configuredMode = section.getString("Mode", "frames");
        Mode mode = switch (configuredMode == null ? "" : configuredMode.toLowerCase(Locale.ROOT)) {
            case "wave" -> Mode.WAVE;
            case "flow" -> Mode.FLOW;
            default -> Mode.FRAMES;
        };

        int interval = clamp(section.getInt("Interval_Ticks", 6), 2, 200);
        int tabInterval = clamp(section.getInt("Tab_Interval_Ticks", 10), 6, 200);

        ArrayList<String> frames = new ArrayList<>();
        for (String candidate : section.getStringList("Frames")) {
            if (candidate == null || candidate.isBlank()
                    || candidate.length() > MAX_TEMPLATE_LENGTH) continue;
            frames.add(candidate);
            if (frames.size() == MAX_FRAMES) break;
        }
        if (mode == Mode.FRAMES && frames.isEmpty() && framesFallbackWave) mode = Mode.FLOW;

        String waveContent = section.getString("Wave.Content", "{player_nick}");
        if (waveContent == null || waveContent.isBlank()
                || waveContent.length() > MAX_TEMPLATE_LENGTH) {
            waveContent = "{player_nick}";
        }
        List<String> colors = section.getStringList("Wave.Colors").stream()
                .filter(color -> color != null && color.matches("(?i)#[0-9a-f]{6}"))
                .limit(12)
                .toList();
        if (colors.size() < 2) colors = List.of("#FFD700", "#FF4D6D", "#FFD700");

        return new Settings(mode, interval, tabInterval, List.copyOf(frames), waveContent,
                String.join(":", colors), applyToTab, applyToChat);
    }

    /** Plantilla del cuadro actual. {@code fallback} es el formato estatico del nametag. */
    static String template(Settings settings, long frame, String fallback) {
        return switch (settings.mode()) {
            case FRAMES -> settings.frames().get(
                    (int) Math.floorMod(frame, settings.frames().size()));
            case WAVE -> waveWrap(settings.waveColors(), frame, settings.waveContent());
            case FLOW -> flowWrap(settings.waveColors(), frame, settings.waveContent());
            default -> fallback;
        };
    }

    /**
     * Anima el color del TITULO flotante con el mismo efecto que el tag (WAVE u FLOW). Frames rota
     * contenido del apodo, ajeno a un titulo, asi que ahi el titulo no cambia. Envuelve el titulo
     * resuelto quitando su color propio para que el gradiente en movimiento sea el que se vea;
     * bold/italic y demas se conservan.
     */
    static String animateTitle(Settings settings, long frame, String resolvedTitle) {
        if (resolvedTitle == null || resolvedTitle.isEmpty()) return resolvedTitle;
        return switch (settings.mode()) {
            case WAVE -> waveWrap(settings.waveColors(), frame, MM.stripColors(resolvedTitle));
            case FLOW -> flowWrap(settings.waveColors(), frame, MM.stripColors(resolvedTitle));
            default -> resolvedTitle; // FRAMES/OFF: el titulo no se anima
        };
    }

    /** Gradiente de onda (fase ping-pong de Adventure): rebota entre los extremos de la paleta. */
    private static String waveWrap(String colors, long frame, String content) {
        double phase = (Math.floorMod(frame, WAVE_STEPS) / (double) WAVE_STEPS) * 2.0 - 1.0;
        return "<gradient:" + colors + ":" + String.format(Locale.ROOT, "%.3f", phase) + ">"
                + content + "</gradient>";
    }

    /**
     * Gradiente de bucle continuo. La paleta es un CIRCULO (la ultima parada vuelve a la primera,
     * cerrada automaticamente por el muestreo modular), y cada cuadro rota el offset una fraccion
     * de vuelta: el color fluye en una sola direccion y cierra perfecto tras FLOW_STEPS cuadros,
     * sin la costura ni el rebote de la fase de MiniMessage.
     */
    private static String flowWrap(String colors, long frame, String content) {
        int[][] palette = parsePalette(colors);
        double offset = Math.floorMod(frame, FLOW_STEPS) / (double) FLOW_STEPS;
        StringBuilder sb = new StringBuilder("<gradient");
        for (int i = 0; i < FLOW_STOPS; i++) {
            double t = (i / (double) (FLOW_STOPS - 1) + offset) % 1.0;
            sb.append(':').append(sample(palette, t));
        }
        return sb.append('>').append(content).append("</gradient>").toString();
    }

    /** Convierte "#RRGGBB:#RRGGBB:..." en tripletes RGB. La paleta ya viene validada por parse(). */
    private static int[][] parsePalette(String colors) {
        String[] parts = colors.split(":");
        int[][] out = new int[parts.length][3];
        for (int i = 0; i < parts.length; i++) {
            int rgb = Integer.parseInt(parts[i].substring(1), 16); // quita el '#'
            out[i] = new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        }
        return out;
    }

    /** Color en la posicion ciclica {@code t} in [0,1) de la paleta-circulo (N segmentos, cierra). */
    private static String sample(int[][] palette, double t) {
        int n = palette.length;
        double scaled = t * n;                     // N segmentos: incluye el cierre ultimo->primero
        int i = ((int) Math.floor(scaled)) % n;
        double frac = scaled - Math.floor(scaled);
        int[] a = palette[i];
        int[] b = palette[(i + 1) % n];
        int r = (int) Math.round(a[0] + (b[0] - a[0]) * frac);
        int g = (int) Math.round(a[1] + (b[1] - a[1]) * frac);
        int bl = (int) Math.round(a[2] + (b[2] - a[2]) * frac);
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, bl);
    }

    /**
     * Degradado ESTATICO para el chat: una linea ya enviada no puede animarse, asi que el preset
     * se muestra como su paleta tal cual (sin fase, sin desplazamiento) para que TODAS las lineas
     * se vean iguales. FRAMES usa su primer cuadro como representante; OFF deja el apodo intacto.
     */
    static String chatGradient(Settings settings, String content) {
        return switch (settings.mode()) {
            case FLOW, WAVE -> "<gradient:" + settings.waveColors() + ">" + content + "</gradient>";
            case FRAMES -> settings.frames().isEmpty() ? content : settings.frames().get(0);
            default -> content;
        };
    }

    /**
     * En chat no se puede mutar una linea ya enviada. El snapshot actual reemplaza solo los
     * placeholders visuales del apodo; el nombre real y los datos de identidad no se tocan.
     */
    static void applyChatSnapshot(Map<String, String> variables, String renderedFrame) {
        if (variables == null || renderedFrame == null) return;
        variables.put("player_nick", renderedFrame);
        variables.put("rank_nick", renderedFrame);
    }

    /**
     * Los colores internos elegidos en /nickcolor ganarian a un color exterior del cuadro.
     * Para que frames/wave realmente animen el color, se quitan solo los colores del apodo;
     * bold/italic/underline y demas Styles se conservan. El mapa original no se modifica.
     */
    /**
     * Las variables con las que se pinta el cartel con esta animacion: sin color del apodo SOLO
     * si de verdad anima. Con la animacion apagada no hay ningun color que lo sustituya, y
     * quitarlo dejaba el apodo sin el color de /nickcolor (blanco, o el del formato).
     */
    static Map<String, String> variablesFor(Settings settings, Map<String, String> variables) {
        return settings != null && settings.enabled()
                ? animationVariables(variables) : variables;
    }

    static Map<String, String> animationVariables(Map<String, String> variables) {
        Map<String, String> prepared = new java.util.HashMap<>(variables);
        stripNicknameColor(prepared, "player_nick");
        stripNicknameColor(prepared, "rank_nick");
        return prepared;
    }

    private static void stripNicknameColor(Map<String, String> variables, String key) {
        String value = variables.get(key);
        if (value != null) variables.put(key, MM.stripColors(value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
