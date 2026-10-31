package dev.chatalyst.rules;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.EnumSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Una regla de chat: un patron que, al coincidir, ejecuta acciones
 * (avisar, notificar al staff, reemplazar, cancelar). Se carga desde rules.yml.
 */
public final class Rule {

    static final int MAX_REGEX_LENGTH = 2_048;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final int MAX_ENTRY_LENGTH = 1_024;
    private static final long MAX_DELAY_MILLIS = 30L * 24L * 60L * 60L * 1_000L;

    public final String name;
    public final Pattern pattern;
    public final String ignorePermission;   // permiso que exonera de la regla (bypass)
    public final List<String> ignoreContains; // si el mensaje contiene alguno, se ignora la regla
    public final boolean deny;              // cancela el mensaje
    public final String replace;            // si != null, reemplaza lo que coincide (puede ser "")
    public final boolean replaceRaw;        // true = permite grupos ($1); false = texto literal seguro
    public final String warn;               // aviso MiniMessage a quien escribio (o null)
    public final String warnEs;             // variante espanola opcional del aviso
    public final String notifyPermission;   // a quien avisar (staff) o null
    public final String notifyMessage;      // mensaje del aviso al staff
    public final String notifyMessageEs;    // variante espanola opcional del aviso al staff
    public final boolean stopAfter;         // si true, no se evaluan mas reglas tras esta
    public final int points;                // puntos de advertencia que suma al coincidir
    public final int priority;              // mayor primero; 0 por defecto
    public final Set<RuleContext> contexts; // contextos donde aplica; vacio = todos
    public final List<String> actions;      // comandos de consola al coincidir
    public final Extras extras;             // acciones/condiciones/tiempos avanzados
    public final Advanced advanced;         // placeholders, scripts, datos y acciones v2

    /**
     * Opciones avanzadas de una regla (todas opcionales). Acciones extra al coincidir,
     * condiciones require/ignore y ventanas de tiempo. Null/""/valores &lt;=0 = sin efecto.
     */
    public record Extras(
            String kickReason,
            double fine,
            String soundKey,
            String title, String subtitle,
            String actionbar,
            String bossbar,
            String toastTitle, String toastDescription,
            boolean denySilently,
            long delayMs,          // minimo entre activaciones de la regla (global)
            long playerDelayMs,    // minimo entre activaciones por jugador
            long beginsAt,         // epoch ms; 0 = siempre activa
            long expiresAt,        // epoch ms; 0 = nunca caduca
            String requirePermission,
            Set<String> requireWorlds, Set<String> ignoreWorlds,
            Set<String> requireGamemodes, Set<String> ignoreGamemodes,
            Set<String> requireChannels, Set<String> ignoreChannels,
            Set<String> requireRegions, Set<String> ignoreRegions) {

        public static final Extras NONE = new Extras(null, 0, null, null, null, null, null,
                null, null, false, 0, 0, 0, 0, "",
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        /** True si la regla esta dentro de su ventana Begins/Expires en {@code now}. */
        public boolean activeAt(long now) {
            if (beginsAt > 0 && now < beginsAt) return false;
            return expiresAt <= 0 || now < expiresAt;
        }

        public boolean hasConditions() {
            return !requirePermission.isEmpty()
                    || !requireWorlds.isEmpty() || !ignoreWorlds.isEmpty()
                    || !requireGamemodes.isEmpty() || !ignoreGamemodes.isEmpty()
                    || !requireChannels.isEmpty() || !ignoreChannels.isEmpty()
                    || !requireRegions.isEmpty() || !ignoreRegions.isEmpty();
        }
    }

    /** Rule-engine v2 features kept separate so the legacy schema remains compatible. */
    public record Advanced(
            Map<String, String> requireVariables,
            Map<String, String> ignoreVariables,
            Map<String, String> requireData,
            Map<String, String> ignoreData,
            List<String> requireScripts,
            List<String> ignoreScripts,
            Map<String, String> setData,
            List<String> removeData,
            String openBook,
            boolean audit,
            boolean discordStaff,
            // v2.1 :
            List<String> playerCommands,   // "then command" — se ejecutan COMO el jugador
            List<String> proxyCommands,    // "<backend> <command>" — consola remota dirigida
            Map<String, String> rewriteInWorld, // mundo -> mensaje entero de reemplazo
            String replaceProlong,         // "@prolong" — rellena el reemplazo hasta el largo del match
            boolean ignoreMuted,           // no aplica la regla a un jugador silenciado
            boolean stripAccents,          // pliega acentos antes de matchear (á -> a)
            boolean ignoreUuid,            // enmascara UUIDs del texto antes de matchear
            boolean requireDiscord,        // solo si el mensaje viene de Discord
            boolean ignoreDiscord) {       // no si el mensaje viene de Discord

        public static final Advanced NONE = new Advanced(Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), Map.of(), List.of(), "", false, false,
                List.of(), List.of(), Map.of(), "", false, false, false, false, false);

        public boolean hasConditions() {
            return !requireVariables.isEmpty() || !ignoreVariables.isEmpty()
                    || !requireData.isEmpty() || !ignoreData.isEmpty()
                    || !requireScripts.isEmpty() || !ignoreScripts.isEmpty();
        }

        /** Acciones del motor avanzado (Set_Data/Open_Book/Audit/Discord). Los comandos de
         *  jugador/proxy los ejecuta RuleEngine directamente, no pasan por aqui. */
        public boolean hasActions() {
            return !setData.isEmpty() || !removeData.isEmpty() || !openBook.isEmpty()
                    || audit || discordStaff;
        }

        /** Gates simples (muteo, origen Discord) evaluables sin lookups caros. */
        public boolean hasSimpleGates() {
            return ignoreMuted || requireDiscord || ignoreDiscord;
        }
    }

    private Rule(String name, Pattern pattern, String ignorePermission, List<String> ignoreContains,
                 boolean deny, String replace, boolean replaceRaw, String warn, String warnEs,
                 String notifyPermission, String notifyMessage, String notifyMessageEs,
                 boolean stopAfter, int points, int priority,
                 Set<RuleContext> contexts, List<String> actions, Extras extras,
                 Advanced advanced) {
        this.name = name;
        this.pattern = pattern;
        this.ignorePermission = ignorePermission;
        this.ignoreContains = ignoreContains;
        this.deny = deny;
        this.replace = replace;
        this.replaceRaw = replaceRaw;
        this.warn = warn;
        this.warnEs = warnEs;
        this.notifyPermission = notifyPermission;
        this.notifyMessage = notifyMessage;
        this.notifyMessageEs = notifyMessageEs;
        this.stopAfter = stopAfter;
        this.points = points;
        this.priority = priority;
        this.contexts = contexts;
        this.actions = actions;
        this.extras = extras;
        this.advanced = advanced;
    }

    /** Devuelve la regla, o null si el regex es invalido (se avisa por el logger del que llama). */
    public static Rule fromConfig(String name, ConfigurationSection sec) throws PatternSyntaxException {
        String regex = sec.getString("Match", "");
        if (regex.isBlank()) {
            throw new PatternSyntaxException("Match cannot be empty", regex, -1);
        }
        if (regex.length() > MAX_REGEX_LENGTH) {
            throw new PatternSyntaxException("Match exceeds " + MAX_REGEX_LENGTH
                    + " characters", regex.substring(0, MAX_REGEX_LENGTH), MAX_REGEX_LENGTH);
        }
        int flags = Pattern.UNICODE_CASE;
        if (sec.getBoolean("Case_Sensitive", false)) {
            // sensible a mayusculas: sin CASE_INSENSITIVE
        } else {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        Pattern pattern = Pattern.compile(regex, flags);

        List<String> contains = boundedList(sec.getStringList("Ignore_Contains"));
        contains.replaceAll(s -> s.toLowerCase(Locale.ROOT));

        ConfigurationSection notify = sec.getConfigurationSection("Notify");
        String notifyPerm = notify != null ? notify.getString("Permission") : sec.getString("Notify_Permission");
        String notifyMsg = notify != null ? notify.getString("Message") : sec.getString("Notify_Message");
        String notifyMsgEs = notify != null ? notify.getString("Message_es") : sec.getString("Notify_Message_es");

        String replace = sec.contains("Replace") ? sec.getString("Replace", "") : null;
        int priority = sec.getInt("Priority", 0);
        Set<RuleContext> contexts = EnumSet.noneOf(RuleContext.class);
        List<String> rawContexts = sec.getStringList("Contexts");
        for (String raw : rawContexts) {
            RuleContext ctx = RuleContext.fromString(raw);
            if (ctx != null) contexts.add(ctx);
        }
        List<String> actions = new java.util.ArrayList<>();
        ConfigurationSection actionSec = sec.getConfigurationSection("Actions");
        if (actionSec != null) {
            actions.addAll(boundedList(actionSec.getStringList("Commands")));
        }

        return new Rule(
                name,
                pattern,
                sec.getString("Ignore_Permission", ""),
                contains,
                sec.getBoolean("Deny", false) || sec.getBoolean("Deny_Silently", false),
                replace,
                sec.getBoolean("Replace_Raw", false),
                sec.getString("Warn"),
                sec.getString("Warn_es"),
                notifyPerm,
                notifyMsg,
                notifyMsgEs,
                sec.getBoolean("Stop_After", false),
                Math.max(0, sec.getInt("Points", 0)),
                priority,
                contexts,
                List.copyOf(actions),
                extrasFromConfig(sec),
                advancedFromConfig(sec)
        );
    }

    private static Advanced advancedFromConfig(ConfigurationSection sec) {
        ConfigurationSection require = sec.getConfigurationSection("Require");
        ConfigurationSection ignore = sec.getConfigurationSection("Ignore");
        ConfigurationSection actions = sec.getConfigurationSection("Actions");
        return new Advanced(
                stringMap(require, "Variables"), stringMap(ignore, "Variables"),
                stringMap(require, "Data"), stringMap(ignore, "Data"),
                stringList(require, "Scripts"), stringList(ignore, "Scripts"),
                stringMap(actions, "Set_Data"), stringList(actions, "Remove_Data"),
                actions != null ? actions.getString("Open_Book", "") : "",
                actions != null && actions.getBoolean("Audit", false),
                actions != null && actions.getBoolean("Discord_Staff", false),
                stringList(actions, "Player_Commands"),
                stringList(actions, "Proxy_Commands"),
                stringMap(actions, "Rewrite_In_World"),
                normalizeProlong(sec.getString("Replace_Prolong", "")),
                ignore != null && ignore.getBoolean("Muted", false),
                sec.getBoolean("Strip_Accents", false),
                ignore != null && ignore.getBoolean("Uuid", false),
                require != null && require.getBoolean("Discord", false),
                ignore != null && ignore.getBoolean("Discord", false));
    }

    /** @prolong accepts exactly one printable Unicode code point, including supplementary ones. */
    static String normalizeProlong(String configured) {
        if (configured == null || configured.isEmpty()
                || configured.codePointCount(0, configured.length()) != 1) return "";
        int codePoint = configured.codePointAt(0);
        return Character.isISOControl(codePoint) ? "" : configured;
    }

    private static Map<String, String> stringMap(ConfigurationSection parent, String key) {
        if (parent == null) return Map.of();
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) return Map.of();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        // Bukkit treats dots in YAML keys as nested paths. Reading every leaf restores
        // intuitive state keys such as "onboarding.step" instead of losing the value.
        for (String entry : section.getKeys(true)) {
            if (values.size() >= MAX_LIST_ENTRIES) break;
            if (section.isConfigurationSection(entry)) continue;
            Object value = section.get(entry);
            if (value != null && entry.length() <= MAX_ENTRY_LENGTH) {
                String text = String.valueOf(value);
                values.put(entry, text.length() <= MAX_ENTRY_LENGTH
                        ? text : text.substring(0, MAX_ENTRY_LENGTH));
            }
        }
        return Map.copyOf(values);
    }

    private static List<String> stringList(ConfigurationSection parent, String key) {
        return parent == null ? List.of() : List.copyOf(boundedList(parent.getStringList(key)));
    }

    private static Extras extrasFromConfig(ConfigurationSection sec) {
        ConfigurationSection title = sec.getConfigurationSection("Title");
        ConfigurationSection toast = sec.getConfigurationSection("Toast");
        ConfigurationSection require = sec.getConfigurationSection("Require");
        ConfigurationSection ignore = sec.getConfigurationSection("Ignore");
        Extras extras = new Extras(
                sec.getString("Kick"),
                boundedFine(sec.getDouble("Fine", 0)),
                sec.getString("Sound"),
                title != null ? title.getString("Title") : null,
                title != null ? title.getString("Subtitle") : null,
                sec.getString("Actionbar"),
                sec.getString("Bossbar"),
                toast != null ? toast.getString("Title") : null,
                toast != null ? toast.getString("Description") : null,
                sec.getBoolean("Deny_Silently", false),
                boundedDelayMillis(sec.getDouble("Delay_Seconds", 0)),
                boundedDelayMillis(sec.getDouble("Player_Delay_Seconds", 0)),
                parseDate(sec.getString("Begins")),
                parseDate(sec.getString("Expires")),
                require != null ? require.getString("Permission", "") : "",
                lowerSet(require, "Worlds"), lowerSet(ignore, "Worlds"),
                lowerSet(require, "Gamemodes"), lowerSet(ignore, "Gamemodes"),
                lowerSet(require, "Channels"), lowerSet(ignore, "Channels"),
                lowerSet(require, "Regions"), lowerSet(ignore, "Regions"));
        return extras;
    }

    private static Set<String> lowerSet(ConfigurationSection sec, String key) {
        if (sec == null) return Set.of();
        List<String> raw = boundedList(sec.getStringList(key));
        if (raw.isEmpty()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String s : raw) out.add(s.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    private static List<String> boundedList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return new java.util.ArrayList<>();
        List<String> out = new java.util.ArrayList<>(Math.min(raw.size(), MAX_LIST_ENTRIES));
        for (String value : raw) {
            if (out.size() >= MAX_LIST_ENTRIES) break;
            if (value == null) continue;
            out.add(value.length() <= MAX_ENTRY_LENGTH
                    ? value : value.substring(0, MAX_ENTRY_LENGTH));
        }
        return out;
    }

    static long boundedDelayMillis(double seconds) {
        if (Double.isNaN(seconds) || seconds <= 0) return 0L;
        if (seconds == Double.POSITIVE_INFINITY) return MAX_DELAY_MILLIS;
        double millis = Math.min(seconds * 1_000.0, MAX_DELAY_MILLIS);
        return Math.round(millis);
    }

    static double boundedFine(double fine) {
        return Double.isFinite(fine) && fine > 0 ? Math.min(fine, 1_000_000_000_000.0) : 0.0;
    }

    /** "yyyy-MM-dd HH:mm" (zona del servidor) -> epoch ms; 0 si falta o es invalida. */
    static long parseDate(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            return java.time.LocalDateTime.parse(raw.trim(), formatter)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ex) {
            return 0;
        }
    }

    public boolean appliesTo(RuleContext context) {
        return contexts == null || contexts.isEmpty() || (context != null && contexts.contains(context));
    }
}
