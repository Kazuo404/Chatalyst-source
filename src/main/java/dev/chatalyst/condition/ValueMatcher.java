package dev.chatalyst.condition;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Bounded comparison language shared by rules and conditional formats. */
public final class ValueMatcher {

    private static final int MAX_REGEX_LENGTH = 512;
    private static final int MAX_CACHED_PATTERNS = 1024;
    private static final ConcurrentMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();
    private static final List<Prefix> PREFIXES = List.of(
            new Prefix("regex:", Operator.REGEX),
            new Prefix("contains:", Operator.CONTAINS),
            new Prefix("starts:", Operator.STARTS),
            new Prefix("ends:", Operator.ENDS),
            new Prefix("equals:", Operator.EQUALS),
            new Prefix("in:", Operator.IN),
            new Prefix("!=", Operator.NOT_EQUALS),
            new Prefix(">=", Operator.GREATER_OR_EQUAL),
            new Prefix("<=", Operator.LESS_OR_EQUAL),
            new Prefix(">", Operator.GREATER),
            new Prefix("<", Operator.LESS));

    private ValueMatcher() {
    }

    public static boolean matches(String actualValue, String rawExpression) {
        String actual = actualValue == null ? "" : actualValue;
        Expression expression = parse(rawExpression == null ? "" : rawExpression.trim());
        return switch (expression.operator()) {
            case EQUALS -> actual.equalsIgnoreCase(expression.expected());
            case NOT_EQUALS -> !actual.equalsIgnoreCase(expression.expected());
            case CONTAINS -> lower(actual).contains(lower(expression.expected()));
            case STARTS -> lower(actual).startsWith(lower(expression.expected()));
            case ENDS -> lower(actual).endsWith(lower(expression.expected()));
            case IN -> in(actual, expression.expected());
            case REGEX -> regex(actual, expression.expected());
            case GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL -> numeric(actual,
                expression.expected(), expression.operator());
        };
    }

    private static Expression parse(String raw) {
        for (Prefix prefix : PREFIXES) {
            if (raw.startsWith(prefix.token())) {
                return new Expression(prefix.operator(), raw.substring(prefix.token().length()).trim());
            }
        }
        return new Expression(Operator.EQUALS, raw);
    }

    private static boolean regex(String actual, String rawPattern) {
        if (rawPattern.isEmpty() || rawPattern.length() > MAX_REGEX_LENGTH) return false;
        try {
            Pattern cached = REGEX_CACHE.get(rawPattern);
            if (cached == null) {
                if (REGEX_CACHE.size() >= MAX_CACHED_PATTERNS) return false;
                int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                Pattern compiled = Pattern.compile(rawPattern, flags);
                Pattern previous = REGEX_CACHE.putIfAbsent(rawPattern, compiled);
                cached = previous == null ? compiled : previous;
            }
            // Presupuesto anti-ReDoS, como RuleEngine y el router: el patron es de la config
            // pero el valor comparado puede venir de un placeholder que influye el jugador
            // (nick, etc.), y esto corre en el hilo async del chat. Timeout -> no matchea.
            return dev.chatalyst.util.SafeRegex.find(cached, actual,
                    dev.chatalyst.util.SafeRegex.DEFAULT_BUDGET_NANOS);
        } catch (PatternSyntaxException | dev.chatalyst.util.SafeRegex.BudgetExceededException invalid) {
            return false;
        }
    }

    private static boolean in(String actual, String choices) {
        for (String choice : choices.split(",")) {
            if (actual.equalsIgnoreCase(choice.trim())) return true;
        }
        return false;
    }

    private static boolean numeric(String actual, String expected, Operator operator) {
        try {
            double left = Double.parseDouble(actual.trim());
            double right = Double.parseDouble(expected.trim());
            // "NaN" e "Infinity" son numeros validos para parseDouble, y Double.compare coloca
            // NaN por ENCIMA de cualquier otro: un valor "NaN" pasaba un ">=100".
            if (!Double.isFinite(left) || !Double.isFinite(right)) return false;
            int comparison = Double.compare(left, right);
            return switch (operator) {
                case GREATER_OR_EQUAL -> comparison >= 0;
                case LESS_OR_EQUAL -> comparison <= 0;
                case GREATER -> comparison > 0;
                case LESS -> comparison < 0;
                default -> false;
            };
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static String lower(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record Prefix(String token, Operator operator) {
    }

    private record Expression(Operator operator, String expected) {
    }

    private enum Operator {
        EQUALS, NOT_EQUALS, CONTAINS, STARTS, ENDS, IN, REGEX,
        GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL
    }
}
