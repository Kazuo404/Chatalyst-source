package dev.chatalyst.autoreply;

import dev.chatalyst.util.SafeRegex;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Auto-respuestas del chat (chat-bot): cuando un mensaje coincide con un trigger configurado
 * (texto contenido o "regex:..."), responde con lineas MiniMessage — al jugador que pregunto
 * o a todo el canal. Pensado para FAQs ("como gano dinero", "donde reclamo terreno"...) sin
 * necesitar staff conectado. Bilingue: Reply (ingles) / Reply_es (español) se eligen segun el
 * idioma del CLIENTE del receptor (convencion _es del proyecto), con fallback a la que exista.
 *
 * <p>La logica de matching y cooldowns vive aqui, sin tocar Bukkit mas alla de la config,
 * para poder testearla; el envio real lo hace {@link AutoReplyListener}. Los regex corren
 * con presupuesto anti-ReDoS ({@link SafeRegex}): un trigger mal escrito no puede clavar el
 * hilo del chat. Cooldown por regla+jugador y por regla global, para que ni una pregunta
 * repetida ni una FAQ popular conviertan al bot en spam.
 */
public final class AutoResponder {

    /** Limites de cordura: nadie legitimo necesita mas y acotan config rotas/abusivas. */
    private static final int MAX_RESPONSES = 128;
    private static final int MAX_TRIGGERS_PER_RESPONSE = 32;
    private static final int MAX_TRIGGER_LENGTH = 512;
    private static final int MAX_REPLY_LINES = 8;
    private static final int MAX_PLUGIN_VARIANTS = 12;
    private static final long MAX_COOLDOWN_SECONDS = 30L * 24L * 60L * 60L;
    private static final long MAX_DELAY_TICKS = 20L * 60L * 5L;

    /**
     * Variante de respuesta condicionada a un plugin instalado. Si el plugin {@code plugin}
     * esta presente en el servidor, esta respuesta sustituye a la por defecto — asi el bot
     * responde con el comando del plugin REAL del servidor (p. ej. {@code /jobs} si detecta
     * Jobs) en vez de asumir siempre los comandos nativos de Chatalyst.
     */
    public record PluginReply(String plugin, List<String> replies, List<String> repliesEs) {
        public List<String> replies(boolean spanish) {
            if (spanish && !repliesEs.isEmpty()) return repliesEs;
            return replies.isEmpty() ? repliesEs : replies;
        }
    }

    /** Un fragmento de respuesta compuesta, mostrado solo si su capacidad esta disponible. */
    public record SmartPart(String needs, String text, String textEs) {
        String text(boolean spanish) {
            return spanish && textEs != null && !textEs.isBlank() ? textEs : text;
        }

        /** Sin capacidad declarada, la linea no depende de nada y se dice siempre. */
        boolean always() {
            return needs == null || needs.isBlank();
        }
    }

    /**
     * Respuesta COMPUESTA e inteligente: se arma con las partes cuya capacidad este viva ahora
     * (comando registrado, plugin presente o feature encendida). Si ninguna aplica, responde con
     * {@code Unavailable} ("por ahora no esta disponible"). Asi "como veo mi dinero" solo ofrece
     * el comando de saldo que existe, y "como mando un privado" ofrece /msg y/o /inbox segun cual
     * este activo.
     */
    public record Smart(String intro, String introEs, List<SmartPart> parts,
                        List<String> unavailable, List<String> unavailableEs) {

        List<String> compose(boolean spanish, java.util.function.Predicate<String> capable) {
            List<String> chosen = new ArrayList<>();
            for (SmartPart part : parts) {
                if (part.always() || capable.test(part.needs())) chosen.add(part.text(spanish));
            }
            if (chosen.isEmpty()) {
                return spanish && !unavailableEs.isEmpty() ? unavailableEs : unavailable;
            }
            List<String> out = new ArrayList<>();
            String line = spanish && introEs != null && !introEs.isBlank() ? introEs : intro;
            if (line != null && !line.isBlank()) out.add(line);
            out.addAll(chosen);
            return out;
        }
    }

    /** Una regla de auto-respuesta ya validada. */
    public record Response(String name, List<String> containsTriggers,
                           List<List<String>> keywordGroups, List<Pattern> regexTriggers,
                           List<String> replies, List<String> repliesEs,
                           List<PluginReply> pluginReplies, Smart smart, boolean toEveryone,
                           long delayTicks, long playerCooldownMs, long globalCooldownMs,
                           Boolean requireQuestion) {
        // requireQuestion: override por regla de Precision.Require_Question (null = el global).

        /**
         * Lineas en el idioma del cliente del jugador (convencion {@code _es} del proyecto,
         * como Notify_Message_es en las reglas): Reply_es para clientes en español si existe;
         * si falta una de las dos listas, se cae a la otra en vez de callar.
         */
        public List<String> replies(boolean spanish) {
            if (spanish && !repliesEs.isEmpty()) return repliesEs;
            return replies.isEmpty() ? repliesEs : replies;
        }

        /**
         * Respuesta segun el estado REAL del servidor. Prioridad:
         * <ol>
         *   <li>Respuesta compuesta inteligente (Smart), si la regla la define y aporta lineas.</li>
         *   <li>Variante por plugin (If_Plugin): la primera cuyo plugin este presente.</li>
         *   <li>Respuesta fija por defecto.</li>
         * </ol>
         */
        public List<String> replies(boolean spanish, java.util.function.Predicate<String> capable) {
            if (smart != null) {
                List<String> composed = smart.compose(spanish, capable);
                if (!composed.isEmpty()) return composed;
            }
            for (PluginReply variant : pluginReplies) {
                if (capable.test("plugin:" + variant.plugin())) return variant.replies(spanish);
            }
            return replies(spanish);
        }
    }

    private final JavaPlugin plugin; // null solo en tests (ver el otro constructor)
    private final java.util.function.Consumer<String> warnSink;
    /**
     * Todo lo que decide si y como responder, en un snapshot inmutable reemplazado entero en
     * load(): el chat async lo lee UNA vez por mensaje y nunca ve una mezcla de dos recargas.
     *
     * <p>privateQuestions: la pregunta detectada NO va al chat general; solo el que pregunta ve su
     * mensaje y la respuesta. privateOnlyExplicit (Private_Questions_Mode: explicit, defecto): solo
     * se oculta si caso por un trigger escrito a mano; por deteccion difusa el mensaje sale al chat
     * y el bot contesta en privado, asi un falso positivo nunca se traga un mensaje. requireQuestion
     * y maxWords (Precision): las Keywords solo cuentan si el mensaje PARECE una pregunta y no es un
     * parrafo — sin eso "tengo mucho dinero jaja" disparaba solo por llevar una palabra de cada grupo.
     */
    record Settings(List<Response> responses, boolean privateQuestions,
                    boolean privateOnlyExplicit, boolean requireQuestion, int maxWords,
                    Set<String> questionWords) {
        static final Settings EMPTY = new Settings(List.of(), true, true, true, 14, QUESTION_WORDS);
    }

    private volatile Settings settings = Settings.EMPTY;
    private volatile boolean enabled;
    private volatile String bypass;
    // Auto-lenguaje: por defecto cada jugador recibe la respuesta en el idioma de su cliente.
    // Con Auto_Language:false todos reciben un idioma fijo (Language: en|es).
    private volatile boolean autoLanguage = true;
    private volatile boolean fixedSpanish;

    /** Palabras que hacen de un mensaje una pregunta si lo abren (tras muletillas). */
    static final Set<String> QUESTION_WORDS = normalizedSet(
            "como", "komo", "cmo", "donde", "dnd", "onde", "cual", "cuales", "cuando", "cuanto",
            "cuanta", "cuantos", "cuantas", "kuanto", "quien", "quienes", "puedo", "podemos",
            "pueden", "ayuda", "ayudenme", "ayudame", "dime", "digan", "explicame",
            "ensename", "how", "hw", "hows", "where", "wheres", "what", "whats", "wat", "wats",
            "which", "when", "who", "why", "can", "could", "help");
    /** Muletillas que pueden ir antes de la palabra interrogativa sin quitarle lo de pregunta. */
    static final Set<String> FILLERS = normalizedSet(
            "y", "e", "oye", "oigan", "oie", "ey", "hey", "bro", "wey", "we", "pero", "entonces",
            "pues", "ok", "hola", "buenas", "una", "pregunta", "perdon", "disculpen", "gente",
            "chicos", "and", "so", "hi", "hello", "yo", "guys", "but", "pls", "plz", "porfa",
            "ahora", "bueno", "a", "admin", "staff", "bot");
    /** Piden ayuda vayan donde vayan ("estoy atrapado ayuda"). */
    static final Set<String> HELP_WORDS = normalizedSet(
            "ayuda", "ayudenme", "ayudame", "auxilio", "help", "halp");
    /** Delante de "ayuda" hablan DE la ayuda, no la piden: "por la ayuda", "tu ayuda". */
    static final Set<String> HELP_DETERMINERS = normalizedSet(
            "la", "por", "tu", "su", "mi", "de", "una", "esa", "the", "your", "for", "my", "his");
    /** Un mensaje que da las gracias no esta pidiendo nada. */
    static final Set<String> THANKS = normalizedSet(
            "gracias", "grasias", "grax", "gracia", "thanks", "thx", "ty", "thank");
    /** Aperturas de varias palabras que preguntan solo si abren la frase o una clausula. */
    static final List<String> OPENING_PHRASES = List.copyOf(normalizedSet(
            "por que", "porq", "pq", "xq", "que es", "q es", "de que forma", "de que manera",
            "que forma", "que manera", "en que", "para que"));
    /** Solo cuentan como pregunta si abren el mensaje: a media frase son demasiado comunes. */
    static final Set<String> OPENING_WORDS = normalizedSet("tienen", "existe", "do", "does", "is", "are");
    /**
     * Frases que delatan una pregunta en cualquier parte del mensaje. "alguien" o "que" a secas
     * no: "alguien quiere party" o "que lag" no piden ayuda.
     */
    static final List<String> QUESTION_PHRASES = List.copyOf(normalizedSet(
            "no se como", "no se donde", "no se cual", "alguien sabe", "se puede", "como se",
            "que hago", "q hago", "ke hago", "que tengo que", "que necesito", "que se necesita",
            "como le hago", "como hago", "como salgo", "is there", "are there", "do you", "how do",
            "how to", "anyone know", "i need help", "necesito ayuda"));

    /**
     * Las listas se comparan con texto ya pasado por {@link #normalize}, que funde letras repetidas
     * ("hello" -> "helo", "staff" -> "staf"): se normalizan igual o nunca casarian.
     */
    private static Set<String> normalizedSet(String... words) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : words) {
            String n = normalize(w);
            if (!n.isEmpty()) out.add(n);
        }
        return java.util.Collections.unmodifiableSet(out);
    }
    // Ultimo disparo: por regla+jugador y por regla (global). Acotados por reglas x jugadores.
    private final Map<String, Long> lastByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGlobal = new ConcurrentHashMap<>();
    private final Set<String> slowTriggerWarned = ConcurrentHashMap.newKeySet();
    private volatile long maxPlayerCooldownMs;

    public AutoResponder(JavaPlugin plugin) {
        this.plugin = plugin;
        this.warnSink = null;
    }

    /** Para tests: sin plugin, los warnings van al sink (la logica es identica). */
    AutoResponder(java.util.function.Consumer<String> warnSink) {
        this.plugin = null;
        this.warnSink = warnSink;
    }

    public void load() {
        load(plugin.getConfig().getConfigurationSection("Auto_Responses"));
    }

    /** Carga desde una seccion (separado para poder testear con un YamlConfiguration). */
    public void load(ConfigurationSection sec) {
        lastByPlayer.clear();
        lastGlobal.clear();
        slowTriggerWarned.clear();
        if (sec == null) {
            enabled = false;
            settings = Settings.EMPTY;
            return;
        }
        enabled = sec.getBoolean("Enabled", false);
        bypass = sec.getString("Bypass_Permission", "chatalyst.bypass.autoresponse");
        autoLanguage = sec.getBoolean("Auto_Language", true);
        fixedSpanish = "es".equalsIgnoreCase(sec.getString("Language", "en"));
        boolean nextPrivate = sec.getBoolean("Private_Questions", true);
        boolean nextOnlyExplicit = !"all".equalsIgnoreCase(
                sec.getString("Private_Questions_Mode", "explicit"));
        boolean nextRequireQuestion = sec.getBoolean("Precision.Require_Question", true);
        int nextMaxWords = Math.max(0, sec.getInt("Precision.Max_Words", 14));
        List<String> extra = sec.getStringList("Precision.Question_Words");
        Set<String> words = new java.util.HashSet<>(QUESTION_WORDS);
        for (String w : extra) {
            String n = normalize(w);
            if (!n.isEmpty()) words.add(n);
        }
        long defaultPlayerCooldown = cooldownMillis(
                sec.getLong("Player_Cooldown_Seconds", 60));
        long defaultGlobalCooldown = cooldownMillis(
                sec.getLong("Global_Cooldown_Seconds", 10));
        long defaultDelay = delayTicks(sec.getLong("Delay_Ticks", 15));

        List<Response> next = new ArrayList<>();
        ConfigurationSection all = sec.getConfigurationSection("Responses");
        if (all != null) {
            for (String name : all.getKeys(false)) {
                if (next.size() >= MAX_RESPONSES) {
                    warn("too many responses; keeping the first " + MAX_RESPONSES + ".");
                    break;
                }
                ConfigurationSection r = all.getConfigurationSection(name);
                if (r == null) continue;
                Response parsed = parse(name, r, defaultPlayerCooldown, defaultGlobalCooldown,
                        defaultDelay);
                if (parsed != null) next.add(parsed);
            }
        }
        // Una sola escritura volatile: un mensaje que se procesa durante un /reload ve o la
        // configuracion vieja entera o la nueva entera, nunca una mezcla (p. ej. ocultar la
        // pregunta con el modo nuevo pero difundir la respuesta con el viejo).
        settings = new Settings(List.copyOf(next), nextPrivate, nextOnlyExplicit,
                nextRequireQuestion, nextMaxWords, Set.copyOf(words));
        maxPlayerCooldownMs = next.stream().mapToLong(Response::playerCooldownMs)
                .max().orElse(0L);
    }

    private Response parse(String name, ConfigurationSection r, long playerCooldown,
            long globalCooldown, long delay) {
        List<String> contains = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();
        List<String> triggers = r.getStringList("Triggers");
        for (String raw : triggers) {
            if (raw == null || raw.isBlank()) continue;
            if (contains.size() + regexes.size() >= MAX_TRIGGERS_PER_RESPONSE) break;
            if (raw.length() > MAX_TRIGGER_LENGTH) {
                warn("overlong trigger in '" + name + "' was skipped.");
                continue;
            }
            if (raw.startsWith("regex:")) {
                String expression = raw.substring(6);
                // Un "regex:" a medio escribir compila SIN error: el patron vacio es valido
                // y find() casa en la posicion 0 de cualquier texto, asi que la regla pasaba
                // a responder a todo el chat. Y el guardia de mas abajo no lo veia, porque
                // la lista no queda vacia: queda con un patron que siempre acierta.
                if (expression.isBlank()) {
                    warn("empty regex trigger in '" + name + "' was skipped; it would have"
                            + " matched every message.");
                    continue;
                }
                try {
                    regexes.add(Pattern.compile(expression,
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException ex) {
                    warn("invalid regex trigger in '" + name + "': " + ex.getDescription());
                }
            } else {
                contains.add(normalize(raw));
            }
        }
        List<List<String>> keywordGroups = parseKeywords(name, r);
        List<String> replies = replyLines(r, "Reply");
        List<String> repliesEs = replyLines(r, "Reply_es");
        List<PluginReply> pluginReplies = parsePluginVariants(name, r);
        Smart smart = parseSmart(name, r);
        boolean hasAnswer = !replies.isEmpty() || !repliesEs.isEmpty() || smart != null;
        if ((contains.isEmpty() && regexes.isEmpty() && keywordGroups.isEmpty()) || !hasAnswer) {
            warn("response '" + name + "' needs at least one trigger (Triggers or Keywords) and a"
                    + " Reply/Reply_es or a Smart block; skipped.");
            return null;
        }
        return new Response(name, List.copyOf(contains), keywordGroups, List.copyOf(regexes),
                replies, repliesEs, pluginReplies, smart,
                "everyone".equalsIgnoreCase(r.getString("To", "player")),
                delayTicks(r.getLong("Delay_Ticks", delay)),
                r.contains("Player_Cooldown_Seconds")
                        ? cooldownMillis(r.getLong("Player_Cooldown_Seconds"))
                        : playerCooldown,
                r.contains("Global_Cooldown_Seconds")
                        ? cooldownMillis(r.getLong("Global_Cooldown_Seconds"))
                        : globalCooldown,
                r.isBoolean("Require_Question") ? r.getBoolean("Require_Question") : null);
    }

    /**
     * Lee las variantes por plugin de una respuesta ({@code If_Plugin: <Plugin>: Reply/Reply_es}).
     * El orden del YAML se conserva: gana la primera cuyo plugin este instalado. Una entrada sin
     * lineas de respuesta se ignora.
     */
    private List<PluginReply> parsePluginVariants(String name, ConfigurationSection r) {
        ConfigurationSection ifPlugin = r.getConfigurationSection("If_Plugin");
        if (ifPlugin == null) return List.of();
        List<PluginReply> out = new ArrayList<>();
        for (String pluginName : ifPlugin.getKeys(false)) {
            if (pluginName == null || pluginName.isBlank()) continue;
            if (out.size() >= MAX_PLUGIN_VARIANTS) {
                warn("response '" + name + "' has too many If_Plugin variants; keeping the first "
                        + MAX_PLUGIN_VARIANTS + ".");
                break;
            }
            ConfigurationSection variant = ifPlugin.getConfigurationSection(pluginName);
            if (variant == null) continue;
            List<String> vReplies = replyLines(variant, "Reply");
            List<String> vRepliesEs = replyLines(variant, "Reply_es");
            if (vReplies.isEmpty() && vRepliesEs.isEmpty()) {
                warn("If_Plugin '" + pluginName + "' of response '" + name
                        + "' has no Reply/Reply_es; ignored.");
                continue;
            }
            out.add(new PluginReply(pluginName, vReplies, vRepliesEs));
        }
        return List.copyOf(out);
    }

    /**
     * Lee el bloque compuesto inteligente ({@code Smart: Intro / Parts / Unavailable}). Cada
     * parte necesita {@code Text}/{@code Text_es}; {@code Needs} es opcional y, cuando falta,
     * la linea se dice siempre en vez de depender de una capacidad. Sin partes
     * validas se ignora y la regla cae a {@code Reply}/{@code If_Plugin}.
     */
    private Smart parseSmart(String name, ConfigurationSection r) {
        ConfigurationSection s = r.getConfigurationSection("Smart");
        if (s == null) return null;
        List<SmartPart> parts = new ArrayList<>();
        for (Map<?, ?> row : s.getMapList("Parts")) {
            if (parts.size() >= MAX_PLUGIN_VARIANTS) break;
            Object needs = row.get("Needs");
            Object text = row.get("Text");
            Object textEs = row.get("Text_es");
            String need = needs == null ? "" : needs.toString().trim();
            String t = text == null ? "" : text.toString();
            String tEs = textEs == null ? "" : textEs.toString();
            // Sin Needs la parte es INCONDICIONAL, no invalida: es una linea que se dice
            // siempre ("los rangos se ganan jugando"), y exigir capacidad la tiraba en
            // silencio — el dueño escribia la respuesta y el bot contestaba sin ella.
            if (t.isBlank() && tEs.isBlank()) {
                warn("a Smart part of '" + name + "' has no Text/Text_es; skipped.");
                continue;
            }
            parts.add(new SmartPart(need, t, tEs));
        }
        if (parts.isEmpty()) {
            warn("response '" + name + "' has a Smart block with no valid Parts; ignored.");
            return null;
        }
        return new Smart(s.getString("Intro", ""), s.getString("Intro_es", ""), List.copyOf(parts),
                singleOrList(s, "Unavailable"), singleOrList(s, "Unavailable_es"));
    }

    /**
     * Lee los grupos de palabras clave ({@code Keywords: - "dinero|plata|money" - "gano|earn"}).
     * Cada linea es un grupo de sinonimos separados por '|'; la respuesta coincide cuando el
     * mensaje (normalizado) contiene una palabra de CADA grupo — asi detecta la pregunta aunque
     * este mal escrita, con leet o desordenada, sin exigir una frase exacta.
     */
    private List<List<String>> parseKeywords(String name, ConfigurationSection r) {
        List<List<String>> groups = new ArrayList<>();
        for (String raw : r.getStringList("Keywords")) {
            if (raw == null || raw.isBlank()) continue;
            if (groups.size() >= MAX_TRIGGERS_PER_RESPONSE) {
                warn("response '" + name + "' has too many Keyword groups; keeping the first "
                        + MAX_TRIGGERS_PER_RESPONSE + ".");
                break;
            }
            List<String> group = new ArrayList<>();
            for (String token : raw.split("\\|")) {
                String norm = normalize(token);
                if (!norm.isBlank() && !group.contains(norm)) group.add(norm);
            }
            if (!group.isEmpty()) groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    /** Un valor de config que puede venir como cadena suelta o como lista (acota lineas). */
    private static List<String> singleOrList(ConfigurationSection s, String key) {
        List<String> list = s.getStringList(key);
        if (!list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String line : list) {
                if (line != null && !line.isBlank() && out.size() < MAX_REPLY_LINES) out.add(line);
            }
            return List.copyOf(out);
        }
        String single = s.getString(key);
        return single != null && !single.isBlank() ? List.of(single) : List.of();
    }

    private static List<String> replyLines(ConfigurationSection r, String key) {
        List<String> out = new ArrayList<>();
        for (String line : r.getStringList(key)) {
            if (line == null || line.isBlank()) continue;
            if (out.size() >= MAX_REPLY_LINES) break;
            out.add(line);
        }
        return List.copyOf(out);
    }

    public boolean isEnabled() {
        return enabled && !settings.responses().isEmpty();
    }

    public String bypassPermission() {
        return bypass;
    }

    /** True si cada jugador recibe la respuesta en el idioma de su cliente (Auto_Language). */
    public boolean autoLanguage() {
        return autoLanguage;
    }

    /** Con auto-lenguaje apagado, el idioma fijo para todos (true = español). */
    public boolean fixedSpanish() {
        return fixedSpanish;
    }

    /** True si la pregunta detectada se responde en privado y no se manda al chat general. */
    public boolean privateQuestions() {
        return settings.privateQuestions();
    }

    /** True si, en modo privado, la pregunta de esta coincidencia se oculta del chat general. */
    public boolean hidesQuestion(Match match) {
        return match != null && match.hide();
    }

    /**
     * Regla que responde al mensaje (la mas especifica) si sus cooldowns lo permiten (los marca
     * como consumidos), o null.
     */
    public Response tryMatch(UUID playerId, String plainText, long nowMs) {
        Match m = match(playerId, plainText, nowMs);
        return m == null ? null : m.response();
    }

    /**
     * Lo que casa y COMO caso, con la decision de privacidad ya tomada sobre el MISMO snapshot de
     * configuracion. {@code explicit}: por un trigger escrito a mano (texto o regex), no por la
     * deteccion difusa de Keywords — el bot esta seguro de que es esa pregunta.
     * {@code privateMode}: la respuesta va solo al que pregunto. {@code hide}: ademas su mensaje
     * no sale al chat general.
     */
    public record Match(Response response, boolean explicit, int score, boolean privateMode,
                        boolean hide) { }

    /** Como {@link #tryMatch} pero diciendo como caso y que hacer con la privacidad. */
    public Match match(UUID playerId, String plainText, long nowMs) {
        if (!enabled || plainText == null || plainText.isBlank()) return null;
        Settings s = settings; // una lectura: todo el mensaje se decide con la misma recarga
        // Se normaliza UNA vez: minusculas, sin acentos, sin leet (din3r0->dinero), sin
        // repeticiones (holaaaa->hola) y con la puntuacion como separador. Asi los triggers de
        // "contiene" y las palabras clave toleran como escribe de verdad la gente.
        Message msg = new Message(plainText, normalize(plainText), s);
        // Gana la regla MAS ESPECIFICA, no la primera del archivo: "como protejo mi casa" casa
        // con homes (casa) y con land-claim (proteg), y debe responder land-claim. Un trigger
        // explicito gana a cualquier Keyword; empate: el orden del YAML.
        List<Response> all = s.responses();
        Response best = null;
        boolean bestExplicit = false;
        int bestScore = 0;
        for (Response response : all) {
            if (matchesExplicit(response, msg)) {
                if (!bestExplicit) {
                    best = response;
                    bestExplicit = true;
                    bestScore = EXPLICIT_SCORE;
                }
                continue;
            }
            if (bestExplicit) continue;
            int score = keywordMatchScore(response, msg);
            if (score > bestScore) {
                best = response;
                bestScore = score;
            }
        }
        if (best == null) return null;
        // Si la ganadora esta en cooldown, se calla: responder con la segunda mejor era contestar
        // otra cosa ("como protejo mi casa" repetido acababa explicando /sethome).
        if (!cooldownsAllow(best, playerId, nowMs)) return null;
        boolean hide = s.privateQuestions() && (bestExplicit || !s.privateOnlyExplicit());
        return new Match(best, bestExplicit, bestScore, s.privateQuestions(), hide);
    }

    /** Nota de un trigger explicito; las Keywords se topan por debajo y nunca la alcanzan. */
    private static final int EXPLICIT_SCORE = 1_000;
    private static final int MAX_KEYWORD_SCORE = EXPLICIT_SCORE - 1;

    /**
     * El mensaje ya preparado: normalizado, con espacios a los lados para buscar palabras
     * completas, partido en palabras, y si parece pregunta (calculado perezosamente una vez).
     */
    private static final class Message {
        final String raw;
        final String normalized;
        final String padded;
        final String[] words;
        final boolean shortEnough;
        final Settings settings;
        private int question = -1; // -1 sin calcular; un Message vive en un solo hilo

        Message(String raw, String normalized, Settings settings) {
            this.raw = raw;
            this.normalized = normalized;
            this.settings = settings;
            this.padded = ' ' + normalized + ' ';
            this.words = normalized.isEmpty() ? new String[0] : normalized.split(" ");
            // Un parrafo no es una FAQ: los triggers de texto y las Keywords se ignoran pasado
            // Max_Words. Los regex no: quien escribe uno ya decide exactamente que debe casar.
            this.shortEnough = settings.maxWords() <= 0 || words.length <= settings.maxWords();
        }

        boolean question() {
            if (question < 0) {
                question = looksLikeQuestion(raw, normalized, settings.questionWords()) ? 1 : 0;
            }
            return question == 1;
        }
    }

    /** 0 = no casa por Keywords; si casa, su especificidad (siempre menor que un trigger). */
    private static int keywordMatchScore(Response response, Message msg) {
        if (!msg.shortEnough || response.keywordGroups().isEmpty()) return 0;
        boolean needQuestion = response.requireQuestion() != null
                ? response.requireQuestion() : msg.settings.requireQuestion();
        if (needQuestion && !msg.question()) return 0;
        return Math.min(MAX_KEYWORD_SCORE,
                keywordScore(response.keywordGroups(), msg.words, msg.padded));
    }

    private boolean matchesExplicit(Response response, Message msg) {
        if (msg.shortEnough) {
            // Frase completa, no substring: "ip" ya no casa con "tip" ni "equipo". Y no negada:
            // "no se puede volar" afirma, no pregunta "se puede volar".
            for (String needle : response.containsTriggers()) {
                if (containsUnnegated(msg.padded, needle)) return true;
            }
        }
        String raw = msg.raw;
        for (Pattern pattern : response.regexTriggers()) {
            try {
                if (SafeRegex.find(pattern, raw, SafeRegex.DEFAULT_BUDGET_NANOS)) return true;
            } catch (SafeRegex.BudgetExceededException slow) {
                if (slowTriggerWarned.add(response.name())) {
                    warn("a regex trigger of '" + response.name() + "' exceeded the time budget"
                            + " and was skipped; it likely has catastrophic backtracking.");
                }
            }
        }
        return false;
    }

    /**
     * Coincide si el mensaje contiene una palabra de CADA grupo (AND entre grupos, OR dentro).
     * Un keyword casa como PREFIJO DE PALABRA (no substring en medio de otra): asi "gan" pilla
     * "gano/ganar" pero "mp" NO pilla "comprar". Los keywords con espacio ("hit up") se buscan
     * como frase suelta.
     *
     * <p>Devuelve 0 si no casa; si casa, una puntuacion de especificidad para elegir la mejor
     * regla cuando varias casan: la longitud del keyword mas largo acertado en cada grupo (un
     * acierto con "proteg" dice mas que uno con "casa"), con el PRIMER grupo (el tema, por
     * convencion) valiendo el doble que los de intencion — "como activo el fly" es de volar, no
     * de activar cosas. Desempate: cuantos keywords acertaron en total.
     */
    static int keywordScore(List<List<String>> groups, String normalized) {
        return keywordScore(groups, normalized.isEmpty() ? new String[0] : normalized.split(" "),
                ' ' + normalized + ' ');
    }

    private static int keywordScore(List<List<String>> groups, String[] words, String padded) {
        if (groups.isEmpty()) return 0;
        int total = 0;
        int hitCount = 0;
        for (int g = 0; g < groups.size(); g++) {
            int best = 0;
            for (String keyword : groups.get(g)) {
                boolean hit = keyword.indexOf(' ') >= 0
                        ? padded.contains(' ' + keyword + ' ')
                        : startsAnyWord(words, keyword);
                if (hit) {
                    hitCount++;
                    best = Math.max(best, keyword.length());
                }
            }
            if (best == 0) return 0; // falta este tema/intencion -> no es esta pregunta
            total += g == 0 ? best * 2 : best;
        }
        return total * 10 + Math.min(hitCount, 9);
    }

    /**
     * True si el mensaje tiene pinta de pregunta: lleva ? o ¿, alguna de sus 3 primeras palabras es
     * interrogativa (como, donde, how...), abre con una palabra que solo pregunta al principio
     * (que, hay, do...) o contiene una frase delatora (alguien sabe, no se como...).
     */
    static boolean looksLikeQuestion(String raw, String normalized, Set<String> questionWords) {
        if (raw != null && (raw.indexOf('?') >= 0 || raw.indexOf('¿') >= 0)) return true;
        if (normalized.isEmpty()) return false;
        if (opensWithQuestion(normalized, questionWords)) return true;
        // Cada clausula cuenta: "estoy atorado, como salgo" pregunta en su segunda parte.
        if (raw != null) {
            for (String clause : CLAUSE_SPLIT.split(raw)) {
                String c = normalize(clause);
                if (!c.isEmpty() && opensWithQuestion(c, questionWords)) return true;
            }
        }
        String[] words = normalized.split(" ");
        if (asksForHelp(words)) return true;
        String padded = ' ' + normalized + ' ';
        for (String phrase : QUESTION_PHRASES) {
            if (containsUnnegated(padded, phrase)) return true;
        }
        return false;
    }

    /**
     * "ayuda"/"help" pide ayuda ("estoy atrapado ayuda"), salvo que hable DE la ayuda ("gracias
     * por la ayuda", "tu ayuda") o el mensaje este dando las gracias.
     */
    private static boolean asksForHelp(String[] words) {
        boolean help = false;
        for (int i = 0; i < words.length; i++) {
            if (THANKS.contains(words[i])) return false;
            if (HELP_WORDS.contains(words[i]) && (i == 0 || !HELP_DETERMINERS.contains(words[i - 1]))) {
                help = true;
            }
        }
        return help;
    }

    /**
     * True si {@code padded} (texto normalizado con un espacio a cada lado) contiene la frase como
     * palabras completas y al menos una vez SIN un "no" justo delante: "pvp en el spawn no se
     * puede" afirma. Las frases que ya empiezan por "no" ("no se como") no se consideran negadas.
     */
    static boolean containsUnnegated(String padded, String phrase) {
        String needle = ' ' + phrase + ' ';
        int at = padded.indexOf(needle);
        while (at >= 0) {
            boolean negated = !phrase.startsWith("no ") && padded.startsWith(" no", at - 3);
            if (!negated) return true;
            at = padded.indexOf(needle, at + 1);
        }
        return false;
    }

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[,.;:!¡]+");

    /**
     * La palabra interrogativa tiene que ABRIR la frase, saltando solo muletillas ("oye", "y",
     * "bro"...): "y es donde mas dinero sacas" o "asi como" no preguntan nada.
     */
    private static boolean opensWithQuestion(String normalized, Set<String> questionWords) {
        String[] words = normalized.split(" ");
        int first = 0;
        while (first < words.length - 1 && first < 3 && FILLERS.contains(words[first])) first++;
        if (OPENING_WORDS.contains(words[first]) || questionWords.contains(words[first])) {
            return true;
        }
        // Aperturas de varias palabras: "por que tengo lag", "que es afk", "de que forma gano".
        String rest = String.join(" ", java.util.Arrays.copyOfRange(words, first, words.length)) + ' ';
        for (String opening : OPENING_PHRASES) {
            if (rest.startsWith(opening + ' ')) return true;
        }
        return false;
    }

    /** True si alguna palabra del mensaje EMPIEZA por el keyword (prefijo, no substring interno). */
    private static boolean startsAnyWord(String[] words, String keyword) {
        for (String word : words) {
            if (word.startsWith(keyword)) return true;
        }
        return false;
    }

    /**
     * Normaliza como escribe la gente de verdad para el matcheo difuso: minusculas, quita
     * acentos (NFD + sin marcas), deshace leet comun ({@code 0->o, 3->e, $->s}...), colapsa
     * repeticiones ({@code holaaaa->hola}) y convierte toda puntuacion en un unico espacio.
     */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String stripped = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(stripped.length());
        char prev = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = deleet(stripped.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                if (c != prev) {
                    out.append(c);
                    prev = c;
                }
            } else if (prev != ' ' && out.length() > 0) {
                out.append(' ');
                prev = ' ';
            }
        }
        return out.toString().trim();
    }

    private static char deleet(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1' -> 'i';
            case '3' -> 'e';
            case '4' -> 'a';
            case '5', '$' -> 's';
            case '7' -> 't';
            case '8' -> 'b';
            case '9' -> 'g';
            case '@' -> 'a';
            default -> c;
        };
    }

    private boolean cooldownsAllow(Response response, UUID playerId, long nowMs) {
        String playerKey = response.name() + ':' + playerId;
        // Cooldown por jugador: check-then-put no atomico, pero su unica consecuencia (el mismo
        // jugador manda dos mensajes que coinciden a la vez y recibe dos respuestas privadas)
        // es inofensiva.
        if (response.playerCooldownMs() > 0) {
            Long last = lastByPlayer.get(playerKey);
            if (last != null && nowMs - last < response.playerCooldownMs()) return false;
        }
        // Cooldown global como PUERTA ATOMICA: tryMatch corre en el pool async del chat y dos
        // jugadores pueden disparar la misma FAQ a la vez. Con un check-then-put suelto ambos
        // pasarian y el bot spamearia a TODO el server dos (o mas) veces dentro de la ventana;
        // compute() serializa por clave y solo deja pasar a uno.
        if (response.globalCooldownMs() > 0) {
            boolean[] allowed = {true};
            lastGlobal.compute(response.name(), (name, last) -> {
                if (last != null && nowMs - last < response.globalCooldownMs()) {
                    allowed[0] = false;
                    return last; // ventana activa: no la reiniciamos
                }
                return nowMs;
            });
            if (!allowed[0]) return false;
        }
        // Solo registramos lo que de verdad se consulta (cooldown activo); asi un cooldown
        // desactivado no hace crecer los mapas sin motivo.
        if (response.playerCooldownMs() > 0) {
            lastByPlayer.put(playerKey, nowMs);
            // Poda ocasional para que jugadores idos no acumulen entradas para siempre.
            if (lastByPlayer.size() > 10_000) {
                // Nunca podar un cooldown aun activo: antes se usaba siempre una hora, por
                // lo que una regla configurada a 24 h podia volver a dispararse al crecer el
                // cache. El mayor cooldown cargado define la ventana segura.
                long cutoff = nowMs - Math.max(maxPlayerCooldownMs, 3_600_000L);
                lastByPlayer.values().removeIf(t -> t < cutoff);
            }
        }
        return true;
    }

    static long cooldownMillis(long configuredSeconds) {
        long seconds = Math.max(0L, Math.min(MAX_COOLDOWN_SECONDS, configuredSeconds));
        return seconds * 1_000L;
    }

    static long delayTicks(long configuredTicks) {
        return Math.max(0L, Math.min(MAX_DELAY_TICKS, configuredTicks));
    }

    private void warn(String message) {
        if (plugin != null) {
            plugin.getLogger().warning("Auto_Responses: " + message);
        } else if (warnSink != null) {
            warnSink.accept(message);
        }
    }
}
