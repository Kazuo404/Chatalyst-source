package dev.chatalyst.channel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure matcher for configured staff-group mentions. */
final class StaffMentionMatcher {

    private static final Pattern VALID_GROUP = Pattern.compile("[\\p{L}\\p{N}_-]{1,32}");
    private static final Pattern NON_CONTENT = Pattern.compile("[\\p{Punct}\\p{Z}¿¡]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private final Pattern pattern;
    private final List<String> intents;
    private final boolean requireAt;

    StaffMentionMatcher(List<String> configuredGroups, boolean requireAt,
            List<String> configuredIntents) {
        this.requireAt = requireAt;
        this.intents = normalizeIntents(configuredIntents);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String configured : configuredGroups == null ? List.<String>of() : configuredGroups) {
            String group = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
            if (VALID_GROUP.matcher(group).matches()) normalized.add(group);
        }
        List<String> alternatives = new ArrayList<>(normalized);
        alternatives.sort((left, right) -> Integer.compare(right.length(), left.length()));
        if (alternatives.isEmpty()) {
            pattern = null;
            return;
        }
        String prefix = requireAt ? "@" : "@?";
        String expression = alternatives.stream().map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right).orElse("");
        pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}._@-])" + prefix + "(" + expression
                + ")(?![\\p{L}\\p{N}_.@-])");
    }

    String find(String message) {
        if (pattern == null || message == null || message.isBlank()) return "";
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) return "";
        String group = matcher.group(1).toLowerCase(Locale.ROOT);
        if (requireAt || hasNaturalHelpIntent(message, matcher.start(), matcher.end())) return group;
        return "";
    }

    private boolean hasNaturalHelpIntent(String message, int groupStart, int groupEnd) {
        String withoutGroup = message.substring(0, groupStart) + message.substring(groupEnd);
        if (NON_CONTENT.matcher(withoutGroup).replaceAll("").isBlank()) return true;
        String normalizedMessage = normalize(message);
        for (String intent : intents) {
            if (containsPhrase(normalizedMessage, intent)) return true;
        }
        return false;
    }

    private static List<String> normalizeIntents(List<String> configured) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : configured == null ? List.<String>of() : configured) {
            String normalized = normalize(value == null ? "" : value).trim();
            if (!normalized.isEmpty() && normalized.length() <= 48) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .transform(normalized -> DIACRITICS.matcher(normalized).replaceAll(""))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsPhrase(String message, String phrase) {
        int from = 0;
        while ((from = message.indexOf(phrase, from)) >= 0) {
            int end = from + phrase.length();
            boolean left = from == 0 || !Character.isLetterOrDigit(message.charAt(from - 1));
            boolean right = end == message.length() || !Character.isLetterOrDigit(message.charAt(end));
            if (left && right) return true;
            from++;
        }
        return false;
    }
}
