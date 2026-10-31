package dev.chatalyst.discord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte una linea escrita en el juego en texto listo para Discord, en una sola pasada.
 *
 * <p>Las URL se copian tal cual, porque escapar un punto o un guion rompe el enlace. Las etiquetas
 * de estilo solo se consumen si el jugador puede usarlas: para quien no tiene permiso de color
 * son texto, igual que en el juego. El color en curso decide que emoji corresponde a un simbolo,
 * asi el mismo caracter puede ser un emoji verde o uno rojo.
 */
final class DiscordChatText {

    /** Texto ya escapado y los IDs de usuario de Discord que pueden recibir aviso. */
    record Result(String text, List<String> mentions) {
    }

    private static final Pattern STYLE = Pattern.compile(
            "(?i)</?(#[0-9a-f]{6}|[a-z_]+(?::[^<>\\s]*)?)>|&#([0-9a-f]{6})|&([0-9a-fk-or])");
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_.]{2,32})");
    private static final Pattern COLOR_PREFIX = Pattern.compile(
            "(?i)^(?:<(#[0-9a-f]{6}|[a-z_]+)>|&#([0-9a-f]{6})|&([0-9a-f]))");

    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA),
            Map.entry("dark_green", 0x00AA00), Map.entry("dark_aqua", 0x00AAAA),
            Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA),
            Map.entry("dark_gray", 0x555555), Map.entry("blue", 0x5555FF),
            Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF),
            Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));
    private static final String[] LEGACY_COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final Set<String> DECORATIONS = Set.of("bold", "b", "italic", "i", "em",
            "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    private DiscordChatText() {
    }

    /** Tabla de simbolos del juego a emojis de Discord, con variantes por color. */
    static final class EmojiTable {

        static final EmojiTable EMPTY = new EmojiTable(Map.of());

        private final Map<String, Map<String, String>> byGlyph;
        private final List<String> glyphs;

        private EmojiTable(Map<String, Map<String, String>> byGlyph) {
            this.byGlyph = byGlyph;
            List<String> sorted = new ArrayList<>(byGlyph.keySet());
            sorted.sort(Comparator.comparingInt(String::length).reversed());
            this.glyphs = List.copyOf(sorted);
        }

        /** Claves {@code simbolo} o {@code <color>simbolo}, con color MiniMessage, & o hex. */
        static EmojiTable parse(Map<String, String> entries) {
            if (entries == null || entries.isEmpty()) return EMPTY;
            Map<String, Map<String, String>> table = new HashMap<>();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey();
                String color = "";
                Matcher prefix = COLOR_PREFIX.matcher(key);
                if (prefix.find()) {
                    String parsed = colorOf(prefix.group(1) != null ? prefix.group(1)
                            : prefix.group(2) != null ? "#" + prefix.group(2)
                            : legacyName(prefix.group(3).charAt(0)));
                    if (parsed != null) {
                        color = parsed;
                        key = key.substring(prefix.end());
                    }
                }
                if (key.isEmpty() || entry.getValue() == null) continue;
                table.computeIfAbsent(key, ignored -> new HashMap<>()).put(color, entry.getValue());
            }
            return table.isEmpty() ? EMPTY : new EmojiTable(Map.copyOf(table));
        }

        private String match(String text, int at) {
            for (String glyph : glyphs) {
                if (text.startsWith(glyph, at)) return glyph;
            }
            return null;
        }

        private String emoji(String glyph, String color) {
            Map<String, String> variants = byGlyph.get(glyph);
            String exact = color == null ? null : variants.get(color);
            return exact != null ? exact : variants.get("");
        }
    }

    /**
     * @param styled       si el jugador puede usar colores: entonces sus etiquetas se consumen
     * @param mentionId    nombre de Discord a ID de usuario, o null si no puede mencionar
     * @param maxMentions  tope de avisos por mensaje
     */
    static Result render(String raw, boolean styled, EmojiTable table,
                         Function<String, String> mentionId, int maxMentions) {
        if (raw == null || raw.isEmpty()) return new Result("", List.of());
        StringBuilder out = new StringBuilder(raw.length() + 16);
        List<String> mentions = new ArrayList<>(0);
        Matcher style = STYLE.matcher(raw);
        Matcher mention = MENTION.matcher(raw);
        String color = null;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (styled && (c == '<' || c == '&') && style.region(i, raw.length()).lookingAt()) {
                Style parsed = readStyle(style);
                if (parsed != null) {
                    if (parsed.changesColor()) color = parsed.color();
                    i = style.end();
                    continue;
                }
            }
            if (startsUrl(raw, i)) {
                int end = wordEnd(raw, i);
                out.append(raw, i, end);
                i = end;
                continue;
            }
            String glyph = table.match(raw, i);
            if (glyph != null) {
                out.append(table.emoji(glyph, color));
                i += glyph.length();
                continue;
            }
            if (c == '@' && mentionId != null && mentions.size() < maxMentions
                    && (i == 0 || !Character.isLetterOrDigit(raw.charAt(i - 1)))
                    && mention.region(i, raw.length()).lookingAt()) {
                String id = mentionId.apply(mention.group(1));
                if (id != null) {
                    if (!mentions.contains(id)) mentions.add(id);
                    out.append("<@").append(id).append('>');
                    i = mention.end();
                    continue;
                }
            }
            if (DiscordBot.MARKDOWN_CHARS.indexOf(c) >= 0) out.append('\\');
            out.append(c);
            i++;
        }
        return new Result(out.toString(), List.copyOf(mentions));
    }

    private record Style(boolean changesColor, String color) {
    }

    /** Lee la etiqueta casada, o null si no es de estilo y debe quedarse como texto. */
    private static Style readStyle(Matcher style) {
        if (style.group(3) != null) {
            char code = Character.toLowerCase(style.group(3).charAt(0));
            String name = legacyName(code);
            if (name != null) return new Style(true, name);
            return new Style(code == 'r', null);
        }
        if (style.group(2) != null) return new Style(true, colorOf("#" + style.group(2)));
        String tag = style.group(1).toLowerCase(Locale.ROOT);
        boolean closing = style.group().startsWith("</");
        int colon = tag.indexOf(':');
        String name = colon < 0 ? tag : tag.substring(0, colon);
        if (name.equals("reset")) return new Style(true, null);
        if (DECORATIONS.contains(name)) return new Style(false, null);
        if (name.equals("gradient") || name.equals("rainbow")) return new Style(true, null);
        String parsed = name.equals("color") || name.equals("colour") || name.equals("c")
                ? (colon < 0 ? null : colorOf(tag.substring(colon + 1))) : colorOf(tag);
        if (parsed == null) return null;
        return new Style(true, closing ? null : parsed);
    }

    private static String legacyName(char code) {
        int index = Character.digit(code, 16);
        return index < 0 ? null : LEGACY_COLORS[index];
    }

    /** Nombre del color de chat: el propio, o el mas cercano si viene en hex. */
    static String colorOf(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT).replace("grey", "gray");
        if (NAMED.containsKey(lower)) return lower;
        if (!lower.matches("#[0-9a-f]{6}")) return null;
        int rgb = Integer.parseInt(lower.substring(1), 16);
        String best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> named : NAMED.entrySet()) {
            int r = (rgb >> 16 & 255) - (named.getValue() >> 16 & 255);
            int g = (rgb >> 8 & 255) - (named.getValue() >> 8 & 255);
            int b = (rgb & 255) - (named.getValue() & 255);
            long distance = 2L * r * r + 4L * g * g + 3L * b * b;
            boolean sooner = distance == bestDistance && named.getKey().compareTo(best) < 0;
            if (distance < bestDistance || sooner) {
                bestDistance = distance;
                best = named.getKey();
            }
        }
        return best;
    }

    private static boolean startsUrl(String text, int at) {
        return (at == 0 || Character.isWhitespace(text.charAt(at - 1)))
                && (text.regionMatches(true, at, "https://", 0, 8)
                || text.regionMatches(true, at, "http://", 0, 7));
    }

    private static int wordEnd(String text, int from) {
        int i = from;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }
}
