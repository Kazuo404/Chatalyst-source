package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reconoce, en una linea de comando, si es un comando que DA objetos y saca de ella el
 * destinatario, el objeto y la cantidad. No ejecuta nada ni toca el servidor: solo lee.
 */
final class GiveCommandParser {

    /** Tope de cantidad: un "/give x diamond 99999999" no debe desbordar ningun total. */
    static final int MAX_AMOUNT = 1_000_000;

    /**
     * Lo que se entendio del comando.
     *
     * @param recipient el destinatario tal cual se escribio, o null si es quien lo escribe
     * @param itemKey   {@code minecraft:diamond} o null si el comando no dice que objeto es
     * @param amount    0 si el comando no lo dice (un kit, una tabla de botin...)
     */
    record GiveCommand(String label, String pattern, String recipient, String itemRaw,
                       String itemKey, int amount, String line) {

        boolean selfTarget() {
            return recipient == null;
        }
    }

    private GiveCommandParser() {
    }

    static Optional<GiveCommand> match(String line, List<GivePattern> patterns) {
        if (line == null) return Optional.empty();
        String text = line.strip();
        if (text.startsWith("/")) text = text.substring(1);
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return Optional.empty();
        String label = tokens.getFirst().toLowerCase(Locale.ROOT);
        String base = label.substring(label.indexOf(':') + 1);
        for (GivePattern pattern : patterns) {
            String wanted = pattern.label();
            boolean labelOk = wanted.indexOf(':') >= 0 ? wanted.equals(label) : wanted.equals(base);
            if (!labelOk) continue;
            GiveCommand parsed = apply(pattern, tokens, line.strip());
            if (parsed != null) return Optional.of(parsed);
        }
        return Optional.empty();
    }

    private static GiveCommand apply(GivePattern pattern, List<String> tokens, String line) {
        String recipient = null;
        String item = "";
        int amount = 0;
        int index = 1;
        List<GivePattern.Slot> slots = pattern.slots();
        for (int s = 1; s < slots.size(); s++) {
            GivePattern.Slot slot = slots.get(s);
            if (index >= tokens.size()) {
                if (slot.optional() || slot.kind() == GivePattern.Kind.REST) break;
                return null; // falta un argumento obligatorio: no es una entrega
            }
            String token = tokens.get(index);
            switch (slot.kind()) {
                case LITERAL -> {
                    if (!token.equalsIgnoreCase(slot.literal())) return null;
                }
                case PLAYER -> recipient = token;
                case ITEM -> item = token;
                case AMOUNT -> amount = amount(token);
                case ANY -> {
                    // una palabra que no interesa (el nombre del kit, "loot"...)
                }
                case REST -> index = tokens.size() - 1;
            }
            index++;
        }
        String key = itemKey(item);
        return new GiveCommand(tokens.getFirst().toLowerCase(Locale.ROOT), pattern.source(),
                isSelf(recipient) ? null : recipient, item, key, amount, line);
    }

    /** {@code @s} siempre es quien lo escribe; el resto de selectores se resuelve fuera. */
    private static boolean isSelf(String recipient) {
        return recipient == null || recipient.equalsIgnoreCase("@s");
    }

    /** "64" -&gt; 64; algo que no es numero (metadatos de Essentials) -&gt; 0, "no se sabe". */
    static int amount(String token) {
        try {
            return Math.max(0, Math.min(MAX_AMOUNT, Integer.parseInt(token.trim())));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * {@code minecraft:diamond_sword[enchantments={...}]} -&gt; {@code minecraft:diamond_sword};
     * {@code DIAMOND} -&gt; {@code minecraft:diamond}. Los alias propios de Essentials
     * ("dsword") salen tal cual: el inventario del destinatario dira luego lo que llego.
     */
    static String itemKey(String raw) {
        if (raw == null) return null;
        String name = raw.strip();
        int cut = name.length();
        for (char stop : new char[]{'[', '{'}) {
            int at = name.indexOf(stop);
            if (at >= 0) cut = Math.min(cut, at);
        }
        name = name.substring(0, cut).toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !name.matches("[a-z0-9_.:/-]+")) return null;
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    /**
     * Parte los argumentos por espacios SIN romper los componentes de 1.21:
     * {@code diamond_sword[custom_name='"Hoja rota"']} es un solo argumento aunque lleve
     * espacios dentro de comillas, corchetes o llaves.
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '"' || c == '\'') && depth > 0) {
                quote = c;
                current.append(c);
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
                current.append(c);
            } else if (c == ']' || c == '}' || c == ')') {
                depth = Math.max(0, depth - 1);
                current.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out;
    }
}
