package dev.chatalyst.rules;

import dev.chatalyst.book.BookManager;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.log.LogManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.regex.Pattern;

/** Evaluates v2 conditions and executes stateful/audited actions outside the core matcher. */
final class RuleAdvancedEngine {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");

    private final JavaPlugin plugin;
    private final RuleDataStore data;
    private volatile RuleScriptEvaluator scripts = (script, player, message, context) -> false;
    private volatile LogManager log;
    private volatile BookManager books;

    RuleAdvancedEngine(JavaPlugin plugin, RuleDataStore data) {
        this.plugin = plugin;
        this.data = data;
    }

    void bindScripts(RuleScriptEvaluator evaluator) {
        scripts = evaluator != null ? evaluator : (script, player, message, context) -> false;
    }

    void bindLog(LogManager manager) {
        log = manager;
    }

    void bindBooks(BookManager manager) {
        books = manager;
    }

    boolean passes(Player player, Rule rule, String message, RuleContext context) {
        Rule.Advanced advanced = rule.advanced;
        Map<String, String> extra = runtimeVariables(rule, message, context);
        return allVariables(player, advanced.requireVariables(), extra)
                && !anyVariable(player, advanced.ignoreVariables(), extra)
                && allData(player, advanced.requireData(), extra)
                && !anyData(player, advanced.ignoreData(), extra)
                && allScripts(advanced.requireScripts(), player, message, context)
                && !anyScript(advanced.ignoreScripts(), player, message, context);
    }

    void execute(Player player, Rule rule, String message, RuleContext context) {
        Rule.Advanced advanced = rule.advanced;
        Map<String, String> extra = runtimeVariables(rule, message, context);
        advanced.setData().forEach((key, value) ->
                data.put(player.getUniqueId(), key, Placeholders.apply(player, value, extra)));
        advanced.removeData().forEach(key -> data.remove(player.getUniqueId(), key));
        // Un comando SENSIBLE (/login <pass>, /register...) no debe acabar en el log ni en
        // Discord aunque una regla amplia (anti-publicidad con regex de dominios/IPs, etc.) lo
        // cace: se redactan sus argumentos, igual que hace el filtro de groserias en
        // RuleContentListener. El resto de la accion (setData, openBook) no se ve afectado.
        String auditMessage = redactSensitive(message, context);
        if (advanced.audit() && log != null) {
            log.log("RULE", player.getName(), auditText(rule, auditMessage, context));
        }
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (advanced.discordStaff() && chatalyst != null
                && chatalyst.getStaffNotifier() != null) {
            chatalyst.getStaffNotifier().alert(auditText(rule, auditMessage, context));
        }
        if (!advanced.openBook().isBlank() && books != null) {
            String name = Placeholders.apply(player, advanced.openBook(), extra);
            FoliaTasks.entity(plugin, player, target -> books.open(target, name, false));
        }
    }

    private boolean allVariables(Player player, Map<String, String> conditions,
            Map<String, String> extra) {
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String actual = Placeholders.apply(player, entry.getKey(), extra);
            String expected = Placeholders.apply(player, entry.getValue(), extra);
            if (!RuleValueMatcher.matches(actual, expected)) return false;
        }
        return true;
    }

    private boolean anyVariable(Player player, Map<String, String> conditions,
            Map<String, String> extra) {
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String actual = Placeholders.apply(player, entry.getKey(), extra);
            String expected = Placeholders.apply(player, entry.getValue(), extra);
            if (RuleValueMatcher.matches(actual, expected)) return true;
        }
        return false;
    }

    private boolean allData(Player player, Map<String, String> conditions,
            Map<String, String> extra) {
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String expected = Placeholders.apply(player, entry.getValue(), extra);
            if (!RuleValueMatcher.matches(data.get(player.getUniqueId(), entry.getKey()), expected)) {
                return false;
            }
        }
        return true;
    }

    private boolean anyData(Player player, Map<String, String> conditions,
            Map<String, String> extra) {
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            String expected = Placeholders.apply(player, entry.getValue(), extra);
            if (RuleValueMatcher.matches(data.get(player.getUniqueId(), entry.getKey()), expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean allScripts(java.util.List<String> conditions, Player player,
            String message, RuleContext context) {
        for (String condition : conditions) {
            if (!scripts.test(condition, player, message, context)) return false;
        }
        return true;
    }

    private boolean anyScript(java.util.List<String> conditions, Player player,
            String message, RuleContext context) {
        for (String condition : conditions) {
            if (scripts.test(condition, player, message, context)) return true;
        }
        return false;
    }

    private static Map<String, String> runtimeVariables(Rule rule, String message,
            RuleContext context) {
        String safeMessage = message != null ? message : "";
        return Map.of("message", safeMessage, "original_message", safeMessage, "rule", rule.name,
                "context", context != null ? context.name().toLowerCase(java.util.Locale.ROOT) : "chat");
    }

    /**
     * Redacta los argumentos de un comando sensible antes de auditarlo (deja solo el nombre,
     * p. ej. "/login &lt;redacted&gt;"). Fuera del contexto COMMAND, o si no es sensible, se
     * devuelve el mensaje tal cual. Usa la misma deteccion que el resto del logging.
     */
    private String redactSensitive(String message, RuleContext context) {
        if (context != RuleContext.COMMAND || message == null || message.isBlank()) return message;
        LogManager current = log;
        return current != null
                ? current.redactSensitiveCommand(message)
                : LogManager.redactAlwaysSensitiveCommand(message);
    }

    private static String auditText(Rule rule, String message, RuleContext context) {
        String clean = message == null ? "" : CONTROL_CHARACTERS.matcher(message).replaceAll(" ");
        if (clean.length() > 256) clean = clean.substring(0, 256);
        return "Rule " + rule.name + " [" + (context != null ? context : RuleContext.CHAT)
                + "] matched: " + clean;
    }
}
