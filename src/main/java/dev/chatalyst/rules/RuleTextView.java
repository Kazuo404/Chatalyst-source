package dev.chatalyst.rules;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search-only view of a rule message. Transformations may make a pattern easier to match, but
 * every transformed boundary remains mapped to the original text so replacements never fold or
 * erase unrelated player content.
 */
final class RuleTextView {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
                    + "|\\b[0-9a-fA-F]{32}\\b");

    private final String original;
    private final String searchable;
    private final int[] originalBoundaries;

    private RuleTextView(String original, String searchable, int[] originalBoundaries) {
        this.original = original;
        this.searchable = searchable;
        this.originalBoundaries = originalBoundaries;
    }

    static RuleTextView create(String original, boolean stripAccents, boolean ignoreUuid) {
        String source = original == null ? "" : original;
        if (!stripAccents && !ignoreUuid) return identity(source);

        boolean[] masked = ignoreUuid ? uuidMask(source) : null;
        StringBuilder search = new StringBuilder(source.length());
        IntBoundaries boundaries = new IntBoundaries(source.length() + 1);
        boundaries.add(0);

        for (int offset = 0; offset < source.length();) {
            int cp = source.codePointAt(offset);
            int width = Character.charCount(cp);
            int end = offset + width;

            if (masked != null && masked[offset]) {
                for (int i = offset; i < end; i++) {
                    search.append(' ');
                    boundaries.add(i + 1);
                }
                offset = end;
                continue;
            }

            String piece = new String(Character.toChars(cp));
            String folded = stripAccents ? foldCodePoint(piece) : piece;
            if (folded.isEmpty()) {
                // A combining mark typed as a separate code point belongs to the preceding
                // searchable character. Extend that boundary so replacing the match removes
                // the complete grapheme instead of leaving an orphan accent behind.
                boundaries.replaceLast(end);
            } else {
                search.append(folded);
                for (int i = 0; i < folded.length(); i++) {
                    boundaries.add(i == folded.length() - 1 ? end : offset);
                }
            }
            offset = end;
        }
        return new RuleTextView(source, search.toString(), boundaries.toArray());
    }

    String original() {
        return original;
    }

    String searchable() {
        return searchable;
    }

    boolean transformed() {
        return original != searchable;
    }

    int originalBoundary(int searchableBoundary) {
        if (searchableBoundary < 0 || searchableBoundary >= originalBoundaries.length) {
            throw new IndexOutOfBoundsException("Invalid searchable boundary: " + searchableBoundary);
        }
        return originalBoundaries[searchableBoundary];
    }

    String originalGroup(Matcher matcher, int group) {
        int start = matcher.start(group);
        return start < 0 ? "" : original.substring(originalBoundary(start),
                originalBoundary(matcher.end(group)));
    }

    String originalGroup(Matcher matcher, String group) {
        int start = matcher.start(group);
        return start < 0 ? "" : original.substring(originalBoundary(start),
                originalBoundary(matcher.end(group)));
    }

    private static RuleTextView identity(String source) {
        int[] boundaries = new int[source.length() + 1];
        for (int i = 0; i <= source.length(); i++) boundaries[i] = i;
        return new RuleTextView(source, source, boundaries);
    }

    private static boolean[] uuidMask(String source) {
        boolean[] masked = new boolean[source.length()];
        Matcher matcher = UUID_PATTERN.matcher(source);
        while (matcher.find()) {
            java.util.Arrays.fill(masked, matcher.start(), matcher.end(), true);
        }
        return masked;
    }

    private static String foldCodePoint(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(cp -> {
                    int type = Character.getType(cp);
                    return type != Character.NON_SPACING_MARK
                            && type != Character.COMBINING_SPACING_MARK
                            && type != Character.ENCLOSING_MARK;
                })
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static final class IntBoundaries {
        private int[] values;
        private int size;

        private IntBoundaries(int capacity) {
            values = new int[Math.max(1, capacity)];
        }

        private void add(int value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        private void replaceLast(int value) {
            values[size - 1] = value;
        }

        private int[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
