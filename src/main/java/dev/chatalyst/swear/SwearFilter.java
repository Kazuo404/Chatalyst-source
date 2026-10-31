package dev.chatalyst.swear;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.SafeRegex;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtro de insultos anti-evasion. Por cada palabra genera un patron que tolera repeticiones,
 * separadores, leet speak, acentos, homoglifos cirilicos y sustituciones foneticas comunes
 * (k por c, v por u/b...), de modo que "p.u.t.o", "putooo", "pµt0", "pvto" o "kabron" tambien
 * se detectan. Ademas trata las transiciones camelCase como limite de palabra
 * ("LaPutaDeTuMadre" detecta "Puta") y tiene una lista "Contains" para frases inequivocas
 * pegadas en minusculas ("laputadetumadre"), censurando el token completo. Censura (tapa las
 * letras) o bloquea segun la config, y respeta una whitelist para no marcar palabras
 * legitimas que contienen un fragmento prohibido.
 */
public final class SwearFilter {

    public enum Action { CENSOR, DENY }

    public record Result(String text, boolean matched) {
    }

    private final JavaPlugin plugin;

    // Volatile + colecciones inmutables reemplazadas enteras en load(): el chat async
    // filtra mientras /reload recarga y nunca ve una lista de patrones a medio llenar.
    private volatile boolean enabled;
    private volatile Action action;
    private volatile char censorChar;
    private volatile int points;
    private volatile boolean warnUsePlayerLanguage;
    private volatile String warn;
    private volatile String warnEs;
    private volatile String bypass;
    private volatile boolean collapseRepeats;
    private volatile boolean stripSeparators;
    private volatile boolean leet;
    private volatile boolean camelBoundaries;
    private volatile boolean nameAlert;
    private volatile List<Pattern> patterns = List.of();
    private volatile List<Pattern> containsPatterns = List.of();
    private volatile Set<String> whitelist = Set.of();

    public SwearFilter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "swear.yml");
        if (!file.exists()) {
            plugin.saveResource("swear.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Defaults del jar: un swear.yml viejo (sin "Contains" u otras claves nuevas) sigue
        // recibiendo las listas empaquetadas sin que el owner tenga que regenerar el archivo.
        try (InputStream in = plugin.getResource("swear.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // sin el recurso empaquetado solo se pierden los defaults de claves nuevas
        }

        enabled = yaml.getBoolean("Enabled", true);
        action = "DENY".equalsIgnoreCase(yaml.getString("Action", "CENSOR")) ? Action.DENY : Action.CENSOR;
        String c = yaml.getString("Censor_Char", "*");
        censorChar = c.isEmpty() ? '*' : c.charAt(0);
        points = yaml.getInt("Points", 1);
        warnUsePlayerLanguage = yaml.getBoolean("Use_Player_Language", true);
        warn = yaml.getString("Warn", "");
        warnEs = yaml.getString("Warn_es", "");
        bypass = yaml.getString("Bypass_Permission", "chatalyst.bypass.swear");
        collapseRepeats = yaml.getBoolean("Normalize.Collapse_Repeats", true);
        stripSeparators = yaml.getBoolean("Normalize.Strip_Separators", true);
        leet = yaml.getBoolean("Normalize.Leet", true);
        camelBoundaries = yaml.getBoolean("Normalize.Camel_Boundaries", true);
        nameAlert = yaml.getBoolean("Name_Alert.Enabled", true);

        Set<String> nextWhitelist = new HashSet<>();
        for (String w : yaml.getStringList("Whitelist")) {
            nextWhitelist.add(normalize(w));
        }
        whitelist = Set.copyOf(nextWhitelist); // publicacion atomica

        List<Pattern> nextPatterns = new ArrayList<>();
        for (String word : yaml.getStringList("Words")) {
            if (!word.isBlank()) {
                nextPatterns.add(buildPattern(word.trim().toLowerCase(Locale.ROOT)));
            }
        }
        patterns = List.copyOf(nextPatterns); // publicacion atomica

        List<Pattern> nextContains = new ArrayList<>();
        for (String word : yaml.getStringList("Contains")) {
            if (word.isBlank()) continue;
            Pattern p = buildContainsPattern(
                    word.trim().toLowerCase(Locale.ROOT), leet, collapseRepeats);
            if (p != null) nextContains.add(p); // null = entrada sin letras ni digitos
        }
        containsPatterns = List.copyOf(nextContains); // publicacion atomica
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isNameAlertEnabled() {
        return nameAlert;
    }

    public Action getAction() {
        return action;
    }

    public int getPoints() {
        return points;
    }

    public String getWarn() {
        return warn;
    }

    public String getWarn(CommandSender who) {
        if (!warnUsePlayerLanguage || !Lang.isSpanish(who)) return warn;
        if (warnEs != null && !warnEs.isEmpty()) return warnEs;
        String translated = Lang.raw(who, "swear.warn");
        return "swear.warn".equals(translated) ? warn : translated;
    }

    public boolean hasBypass(org.bukkit.permissions.Permissible who) {
        return bypass != null && !bypass.isEmpty() && who.hasPermission(bypass);
    }

    /** Analiza el texto: devuelve la version censurada y si hubo coincidencia. */
    public Result apply(String input) {
        if (!enabled || input == null || input.isEmpty()) return new Result(input, false);
        // NFKC aplana los alfabetos "fancy" de los generadores de texto (ｆｕｌｌｗｉｄｔｈ,
        // 𝓼𝓬𝓻𝓲𝓹𝓽, ⓒⓘⓡⓒⓛⓔⓓ...) a letras normales antes de filtrar. Si no hay coincidencia se
        // devuelve el input original intacto; si la hay, la censura parte del texto plegado
        // (los indices ya no casan con el original, y aplanar un mensaje censurado es aceptable).
        String text = fold(input);
        boolean matched = false;
        for (Pattern p : patterns) {
            // Presupuesto anti-ReDoS: los patrones salen de swear.yml (leet + separadores
            // opcionales) y las clases leet solapan con la clase separadora en simbolos como
            // '!' o '$'; una palabra larga y un mensaje crafteado podrian disparar backtracking.
            // Si se agota el plazo, se salta ESA palabra (fail-open), como en el motor de reglas.
            Matcher m = p.matcher(SafeRegex.budgeted(text, SafeRegex.DEFAULT_BUDGET_NANOS));
            StringBuilder sb = new StringBuilder();
            boolean localMatch = false;
            try {
                while (m.find()) {
                    if (isWhitelisted(text, m.start(), m.end())) {
                        m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                        continue;
                    }
                    localMatch = true;
                    m.appendReplacement(sb, Matcher.quoteReplacement(censor(m.group())));
                }
                m.appendTail(sb);
            } catch (SafeRegex.BudgetExceededException slow) {
                plugin.getLogger().warning("Swear pattern '" + p.pattern() + "' exceeded its regex"
                        + " budget; skipping it for this message. Shorten that word or disable the"
                        + " leet/separator options in swear.yml.");
                continue; // deja el texto de esta palabra sin tocar
            }
            if (localMatch) {
                text = sb.toString();
                matched = true;
            }
        }
        // Pasada "Contains": frases inequivocas pegadas dentro de otra palabra
        // ("laputadetumadre"). Como el insulto va camuflado en el token, se censura el TOKEN
        // completo, no solo el fragmento (censurar solo "tumadre" dejaria legible "laputade").
        for (Pattern p : containsPatterns) {
            Matcher m = p.matcher(SafeRegex.budgeted(text, SafeRegex.DEFAULT_BUDGET_NANOS));
            StringBuilder out = null;
            int copied = 0;
            try {
                while (m.find()) {
                    if (isWhitelisted(text, m.start(), m.end())) continue;
                    int[] span = tokenSpan(text, m.start(), m.end());
                    int ws = Math.max(span[0], copied); // el token pudo censurarse ya en parte
                    if (out == null) out = new StringBuilder(text.length());
                    out.append(text, copied, ws).append(censor(text.substring(ws, span[1])));
                    copied = span[1];
                }
            } catch (SafeRegex.BudgetExceededException slow) {
                plugin.getLogger().warning("Swear contains-pattern '" + p.pattern() + "' exceeded"
                        + " its regex budget; skipping it for this message. Shorten that entry in"
                        + " swear.yml (Contains).");
                continue; // deja el texto de esta frase sin tocar
            }
            if (out != null) {
                out.append(text, copied, text.length());
                text = out.toString();
                matched = true;
            }
        }
        return new Result(matched ? text : input, matched);
    }

    // Pliega compatibilidad Unicode (NFKC): ｐｕｔａ / 𝓹𝓾𝓽𝓪 / ⓟⓤⓣⓐ -> puta.
    // Estatico para poder testearlo sin arrancar el plugin.
    static String fold(String s) {
        String nfkc = Normalizer.isNormalized(s, Normalizer.Form.NFKC)
                ? s : Normalizer.normalize(s, Normalizer.Form.NFKC);
        return confusables(nfkc);
    }

    // NFKC NO pliega las versalitas (ᴘᴜᴛᴀ, lo que mas sale de los generadores de "texto
    // fancy") ni las letras griegas que parecen latinas (pυtα). Se traducen aqui, solo para
    // BUSCAR: si nada casa, apply() devuelve el mensaje original intacto.
    private static final String CONFUSABLE_FROM =
            "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ" + "αβεηικνορτυχγωϲ";
    private static final String CONFUSABLE_TO =
            "abcdefghijklmnopqrstuvwyz" + "abenikvoptuxywc";

    static String confusables(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = CONFUSABLE_FROM.indexOf(c);
            if (idx < 0) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(s.length()).append(s, 0, i);
            out.append(CONFUSABLE_TO.charAt(idx));
        }
        return out == null ? s : out.toString();
    }

    // Tapa cada letra/digito del fragmento; deja los separadores como estaban.
    private String censor(String match) {
        StringBuilder sb = new StringBuilder(match.length());
        for (int i = 0; i < match.length(); i++) {
            char ch = match.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) ? censorChar : ch);
        }
        return sb.toString();
    }

    // El fragmento pertenece a una palabra de la whitelist? (se expande a la palabra completa)
    private boolean isWhitelisted(String text, int start, int end) {
        if (whitelist.isEmpty()) return false;
        int[] span = tokenSpan(text, start, end);
        return whitelist.contains(normalize(text.substring(span[0], span[1])));
    }

    // Expande [start, end) al token completo que lo contiene (limites: espacios en blanco).
    // Estatico para poder testearlo sin arrancar el plugin.
    static int[] tokenSpan(String text, int start, int end) {
        int ws = start;
        while (ws > 0 && !Character.isWhitespace(text.charAt(ws - 1))) ws--;
        int we = end;
        while (we < text.length() && !Character.isWhitespace(text.charAt(we))) we++;
        return new int[] {ws, we};
    }

    // Normaliza un texto igual que detectaria el filtro (para comparar la whitelist).
    // Pliega acentos, homoglifos cirilicos y las sustituciones foneticas de charClass
    // ademas del leet clasico: una palabra whitelisted escrita "pútà" o con 'k' por 'c'
    // sigue reconociendose. Las entradas de la whitelist pasan por ESTA misma funcion al
    // cargar, asi ambos lados comparan en la misma forma canonica.
    private String normalize(String s) {
        String out = fold(s).toLowerCase(Locale.ROOT);
        if (leet) {
            // Acentos fuera (pútà -> puta) y una letra canonica por familia de charClass.
            out = Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            StringBuilder folded = new StringBuilder(out.length());
            for (int i = 0; i < out.length(); i++) {
                folded.append(foldChar(out.charAt(i)));
            }
            out = folded.toString();
        }
        if (stripSeparators) {
            out = out.replaceAll("[\\s\\W_]", "");
        }
        if (collapseRepeats) {
            out = out.replaceAll("(.)\\1+", "$1");
        }
        return out;
    }

    // Una letra canonica por familia de charClass (leet, homoglifos y subs foneticas).
    // Donde las clases solapan ('1' esta en i y en l; 'v' en b, u y v) se elige UNA forma:
    // ambos lados de la comparacion whitelist usan este mismo pliegue, asi que es coherente.
    private static char foldChar(char c) {
        return switch (c) {
            case '@', '4', 'а' -> 'a';
            case '8', 'v' -> 'b';
            case 'ç', 'k', 'с', 'к' -> 'c';
            case '3', 'е' -> 'e';
            case '1', '!', 'y', 'і', 'у' -> 'i';
            case '|' -> 'l';
            case 'м' -> 'm';
            case '0', 'о' -> 'o';
            case 'р' -> 'p';
            case '5', '$', 'z', 'ѕ' -> 's';
            case '7', 'т' -> 't';
            case 'х' -> 'x';
            default -> c;
        };
    }

    private Pattern buildPattern(String word) {
        return buildPattern(word, leet, stripSeparators, collapseRepeats, camelBoundaries);
    }

    // Construye el patron anti-evasion de una palabra. Exige que sea una PALABRA completa
    // (lookarounds que no permiten letras/digitos pegados) para no marcar "disputa",
    // "hermano" o "penetrar", pero SI tolera separadores y leet DENTRO de la palabra.
    // Con camelBoundaries, una transicion minuscula->MAYUSCULA tambien cuenta como limite,
    // asi "LaPutaDeTuMadre" detecta "Puta" sin marcar "Computadora" (sin transicion interna).
    // Estatico y con flags explicitos para poder testearlo sin arrancar el plugin.
    static Pattern buildPattern(String word, boolean leet, boolean stripSeparators,
                                boolean collapseRepeats, boolean camelBoundaries) {
        // El (?-i:) es imprescindible: con CASE_INSENSITIVE el JDK hace que \p{Lu}/\p{Ll}
        // acepten ambas cajas, y la transicion camelCase dejaria de distinguirlas.
        String open = camelBoundaries
                ? "(?:(?<![\\p{L}\\p{N}])|(?-i:(?<=\\p{Ll})(?=\\p{Lu})))"
                : "(?<![\\p{L}\\p{N}])";
        String close = camelBoundaries
                ? "(?:(?![\\p{L}\\p{N}])|(?-i:(?<=\\p{Ll})(?=\\p{Lu})))"
                : "(?![\\p{L}\\p{N}])";
        StringBuilder rx = new StringBuilder(open);
        for (int i = 0; i < word.length(); i++) {
            if (i > 0 && stripSeparators) {
                rx.append("[\\s\\W_]*"); // separadores opcionales entre letras
            }
            rx.append(charClass(word.charAt(i), leet));
            if (collapseRepeats) {
                rx.append('+'); // una o mas (tolera repeticiones)
            }
        }
        rx.append(close);
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // Patron "Contains": coincide en CUALQUIER parte del texto, sin limites de palabra y SIN
    // tolerar separadores. Asi "laputadetumadre" cae, pero "tu madre" (con espacio) no se
    // marca: las versiones separadas ya las cubren las palabras individuales de Words.
    // Solo apto para frases inequivocas (un fragmento corto marcaria palabras legitimas).
    static Pattern buildContainsPattern(String word, boolean leet, boolean collapseRepeats) {
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!Character.isLetterOrDigit(c)) continue; // "hijo de puta" == "hijodeputa"
            rx.append(charClass(c, leet));
            if (collapseRepeats) {
                rx.append('+');
            }
        }
        // Una entrada sin letras ni digitos ("!!!", solo emojis) daria un patron VACIO que
        // casa en cada posicion (zero-width) y censuraria el mensaje entero: se descarta.
        if (rx.length() == 0) return null;
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // Clase de caracteres por letra: leet clasico, acentos, homoglifos cirilicos (а е о р с...)
    // y sustituciones foneticas frecuentes en chat hispano (k por c, v por u/b, j por g...).
    // OJO al añadir simbolos que tambien son separadores ('!','$','|'): solapan con [\s\W_]*
    // y aumentan el backtracking; el presupuesto de SafeRegex es la red de seguridad.
    private static String charClass(char c, boolean leet) {
        if (!leet) {
            return Pattern.quote(String.valueOf(c));
        }
        return switch (c) {
            case 'a' -> "[a@4áàâäãа]";
            case 'b' -> "[b8v]";
            case 'c' -> "[cçkс]";
            case 'e' -> "[e3éèêëе]";
            case 'g' -> "[g9]"; // sin 'j': "verga" marcaria "verja" (lista "jilipollas" aparte)
            case 'i' -> "[i1!yíìîïі]";
            case 'k' -> "[kcк]";
            case 'l' -> "[l1|]";
            case 'm' -> "[mм]";
            case 'o' -> "[o0óòôöõо]";
            case 'p' -> "[pр]";
            case 's' -> "[s5$zѕ]";
            case 't' -> "[t7т]";
            case 'u' -> "[uvúùûü]";
            case 'v' -> "[vb]";
            case 'x' -> "[xх]";
            case 'y' -> "[yу]";
            default -> Pattern.quote(String.valueOf(c));
        };
    }
}
