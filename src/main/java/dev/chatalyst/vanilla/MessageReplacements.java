package dev.chatalyst.vanilla;

import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SafeRegex;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cambia el texto de mensajes que Chatalyst NO escribio: el aviso fijo de otro plugin que no
 * trae su propio fichero de idioma, o que delata con su prefijo que plugin es.
 *
 * <p>Cada regla es un mensaje a reconocer y el texto que se manda en su lugar. La comparacion
 * se hace sobre el texto PLANO del mensaje — los colores del original no cuentan — y contra el
 * mensaje entero, no contra un trozo: una regla "No tienes permiso" no puede comerse la mitad
 * de una frase del chat de un jugador que casualmente la contenga.
 *
 * <p>Comodines: {@code %0%}, {@code %1%}... capturan lo que haya en ese hueco y se pueden
 * volver a escribir en el reemplazo; {@code %player%} solo casa con el nombre de quien recibe
 * el mensaje. Un reemplazo vacio oculta el mensaje.
 *
 * <p>Esta clase NO depende de PacketEvents (la intercepcion vive en el puente aislado): aqui
 * solo esta la regla -> texto, para poder probarla sin servidor.
 */
public final class MessageReplacements {

    /** Por encima de esto no es un aviso de plugin: es un libro, y no se mira. */
    static final int MAX_LENGTH = 1024;
    /** Presupuesto por regla y mensaje. Corre en un hilo de red: no puede esperar. */
    private static final long BUDGET_NANOS = 2_000_000L;

    private static final Pattern TOKEN = Pattern.compile("%(player|\\d{1,2})%");
    private static final Pattern LEGACY_CODE =
            Pattern.compile("(?i)[&§](?:#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /** Lo que hay que hacer con un mensaje que caso con una regla. */
    public record Outcome(boolean hide, Component replacement) {
    }

    /** Una regla ya compilada. {@code replacement} es MiniMessage con los comodines dentro. */
    record Rule(Pattern pattern, boolean bindsPlayer, List<Integer> wildcards,
                String replacement) {
    }

    private final JavaPlugin plugin;
    private volatile boolean enabled;
    private volatile List<Rule> rules = List.of();

    public MessageReplacements(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Message_Replacements.Enabled", false);
        List<Rule> fresh = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("Message_Replacements.Rules")) {
            Object match = entry.get("Match");
            Object replace = entry.get("Replace");
            if (match == null) continue;
            Rule rule = compile(String.valueOf(match), replace == null ? "" : String.valueOf(replace));
            if (rule == null) {
                plugin.getLogger().warning("Message_Replacements: the rule \"" + match
                        + "\" has no text to recognise and was skipped.");
                continue;
            }
            fresh.add(rule);
        }
        rules = List.copyOf(fresh);
    }

    /** True si hay algo que hacer: evita desempaquetar cada mensaje del servidor para nada. */
    public boolean isActive() {
        return enabled && !rules.isEmpty();
    }

    /** El mensaje reescrito para {@code viewerName}, o null para dejar pasar el original. */
    public Outcome rewrite(String viewerName, Component original) {
        if (!isActive() || original == null) return null;
        return apply(rules, PLAIN.serialize(original), viewerName);
    }

    // ---- Logica pura (sin Bukkit) ------------------------------------------------------

    /**
     * Compila una regla, o null si no tiene texto fijo que reconocer.
     *
     * <p>Una regla hecha solo de comodines ("%0%") casaria con TODO lo que manda el servidor;
     * nadie quiere eso y casi siempre es una linea a medio escribir, asi que se descarta.
     */
    static Rule compile(String match, String replacement) {
        String template = MM.strip(LEGACY_CODE.matcher(match).replaceAll("")).strip();
        StringBuilder regex = new StringBuilder();
        List<Integer> wildcards = new ArrayList<>();
        boolean bindsPlayer = false;
        boolean literal = false;
        Matcher token = TOKEN.matcher(template);
        int from = 0;
        while (token.find()) {
            literal |= appendLiteral(regex, template.substring(from, token.start()));
            from = token.end();
            if (token.group(1).equals("player")) {
                // Un nombre, no "lo que sea": despues se exige que sea el de quien lo recibe.
                regex.append(bindsPlayer ? "\\k<player>" : "(?<player>[^\\s]{1,48})");
                bindsPlayer = true;
                continue;
            }
            int index = Integer.parseInt(token.group(1));
            if (wildcards.contains(index)) {
                regex.append("\\k<w").append(index).append('>');
            } else {
                wildcards.add(index);
                regex.append("(?<w").append(index).append(">.*?)");
            }
        }
        literal |= appendLiteral(regex, template.substring(from));
        if (!literal) return null;
        return new Rule(Pattern.compile(regex.toString(), Pattern.DOTALL), bindsPlayer,
                List.copyOf(wildcards), Legacy.toMini(replacement == null ? "" : replacement));
    }

    private static boolean appendLiteral(StringBuilder regex, String text) {
        if (text.isEmpty()) return false;
        regex.append(Pattern.quote(text));
        return !text.isBlank();
    }

    /** Primera regla que casa con el texto plano, aplicada; null si ninguna. */
    static Outcome apply(List<Rule> rules, String plain, String viewerName) {
        if (plain == null) return null;
        String text = plain.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) return null;
        for (Rule rule : rules) {
            Map<String, String> captured = match(rule, text, viewerName);
            if (captured == null) continue;
            if (rule.replacement().isBlank()) return new Outcome(true, Component.empty());
            return new Outcome(false, MM.full(fill(rule.replacement(), captured)));
        }
        return null;
    }

    /** Lo capturado por los comodines, o null si la regla no casa con este mensaje. */
    private static Map<String, String> match(Rule rule, String text, String viewerName) {
        Matcher matcher = rule.pattern().matcher(SafeRegex.budgeted(text, BUDGET_NANOS));
        try {
            if (!matcher.matches()) return null;
            Map<String, String> captured = new HashMap<>();
            if (rule.bindsPlayer()) {
                String named = matcher.group("player");
                if (viewerName == null || !viewerName.equalsIgnoreCase(named)) return null;
            }
            for (int index : rule.wildcards()) {
                captured.put(String.valueOf(index), matcher.group("w" + index));
            }
            captured.put("player", viewerName == null ? "" : viewerName);
            return captured;
        } catch (SafeRegex.BudgetExceededException slow) {
            // Fail-open: ante la duda el mensaje sale como lo escribio su plugin.
            return null;
        }
    }

    /**
     * Escribe lo capturado dentro del reemplazo. Va ESCAPADO: lo que cayo en un comodin es
     * texto de otro plugin (o de un jugador citado por el) y no puede colar etiquetas.
     */
    static String fill(String replacement, Map<String, String> captured) {
        Matcher token = TOKEN.matcher(replacement);
        StringBuilder out = new StringBuilder(replacement.length() + 16);
        while (token.find()) {
            String value = captured.get(token.group(1));
            // Un comodin que el reemplazo usa pero la regla no capturo se queda vacio en vez
            // de dejar "%3%" crudo a la vista.
            token.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? "" : MM.escape(value)));
        }
        token.appendTail(out);
        return out.toString();
    }
}
