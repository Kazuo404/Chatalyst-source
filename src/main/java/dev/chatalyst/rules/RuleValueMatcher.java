package dev.chatalyst.rules;

/** Comparison language used by placeholder and persistent-data rule conditions. */
final class RuleValueMatcher {

    private RuleValueMatcher() {
    }

    static boolean matches(String actualValue, String rawExpression) {
        return dev.chatalyst.condition.ValueMatcher.matches(actualValue, rawExpression);
    }
}
