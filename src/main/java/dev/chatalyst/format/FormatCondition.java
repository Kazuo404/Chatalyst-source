package dev.chatalyst.format;

import dev.chatalyst.condition.ValueMatcher;
import dev.chatalyst.hook.Placeholders;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/** Sender/receiver gate for one format part. Empty gates always match. */
public final class FormatCondition {

    @FunctionalInterface
    public interface ScriptEvaluator {
        boolean test(String script, Player subject, Player sender, Map<String, String> context);
    }

    private static volatile ScriptEvaluator scriptEvaluator = (script, subject, sender, context) -> false;

    private final String permission;
    private final String variable;
    private final String script;

    public FormatCondition(String permission, String variable, String script) {
        this.permission = safe(permission);
        this.variable = safe(variable);
        this.script = safe(script);
    }

    public static void setScriptEvaluator(ScriptEvaluator evaluator) {
        scriptEvaluator = evaluator != null ? evaluator
                : (script, subject, sender, context) -> false;
    }

    public boolean isEmpty() {
        return permission.isEmpty() && variable.isEmpty() && script.isEmpty();
    }

    public boolean matches(Player subject, Player sender, Map<String, String> extra) {
        if (subject == null) return isEmpty();
        Map<String, String> context = context(sender, extra);
        context.put("receiver", subject.getName());
        context.put("receiver_uuid", subject.getUniqueId().toString());
        if (!permission.isEmpty() && !matchesPermission(subject,
                Placeholders.apply(subject, permission, context))) return false;
        if (!variable.isEmpty() && !matchesVariable(subject, variable, context)) return false;
        return script.isEmpty() || scriptEvaluator.test(script, subject, sender, context);
    }

    static boolean matchesPermission(Player subject, String expression) {
        String checked = safe(expression);
        boolean negated = checked.startsWith("!");
        String node = negated ? checked.substring(1).trim() : checked;
        if (node.isEmpty()) return false;
        boolean has = subject.hasPermission(node);
        return negated ? !has : has;
    }

    static boolean matchesResolvedVariable(String actual, String expected) {
        String expression = safe(expected);
        if (expression.startsWith("!") && !expression.startsWith("!=")) {
            expression = "!=" + expression.substring(1);
        }
        return ValueMatcher.matches(actual, expression);
    }

    private static boolean matchesVariable(Player subject, String specification,
            Map<String, String> context) {
        String resolved = Placeholders.apply(subject, specification, context).trim();
        int separator = firstWhitespace(resolved);
        if (separator < 1) return false;
        String actual = resolved.substring(0, separator).trim();
        String expected = resolved.substring(separator).trim();
        return !expected.isEmpty() && matchesResolvedVariable(actual, expected);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    private static Map<String, String> context(Player sender, Map<String, String> extra) {
        Map<String, String> context = new HashMap<>();
        if (extra != null) context.putAll(extra);
        if (sender != null) {
            context.put("sender", sender.getName());
            context.put("sender_name", sender.getName());
            context.put("sender_uuid", sender.getUniqueId().toString());
        }
        return context;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
