package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Un comando de dar objetos, escrito en la config como {@code "give <player> <item> [amount]"}.
 *
 * <p>Las primeras palabras son literales (la etiqueta y, si hay, subcomandos como
 * {@code "ak give"}). Despues vienen huecos: {@code <player>} el destinatario,
 * {@code <item>} el objeto, {@code <amount>} la cantidad, {@code <any>} una palabra que no
 * interesa y {@code <rest>} todo lo que quede. Entre corchetes son opcionales y solo pueden
 * ir al final. Sin {@code <player>}, el destinatario es quien lo escribe.
 *
 * <p>La etiqueta casa con y sin espacio de nombres ({@code give} cubre
 * {@code /minecraft:give} y {@code /essentials:give}); si la config la escribe con espacio
 * de nombres, solo casa esa exacta.
 */
record GivePattern(String source, List<Slot> slots) {

    enum Kind { LITERAL, PLAYER, ITEM, AMOUNT, ANY, REST }

    record Slot(Kind kind, String literal, boolean optional) {
    }

    /** Null (con el motivo en {@code problem}) si el patron no se puede usar. */
    static GivePattern parse(String raw, java.util.function.Consumer<String> problem) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        List<Slot> slots = new ArrayList<>();
        boolean sawOptional = false;
        boolean sawHole = false;
        for (String token : text.split("\\s+")) {
            Slot slot = slot(token);
            if (slot == null) {
                problem.accept("'" + raw + "': unknown placeholder " + token);
                return null;
            }
            if (slot.kind() == Kind.LITERAL) {
                // "give <player> to <item>" no tiene sentido: los literales van delante.
                if (sawHole) {
                    problem.accept("'" + raw + "': words must come before the placeholders");
                    return null;
                }
            } else {
                sawHole = true;
            }
            if (sawOptional && !slot.optional()) {
                problem.accept("'" + raw + "': optional placeholders must be the last ones");
                return null;
            }
            if (!slots.isEmpty() && slots.getLast().kind() == Kind.REST) {
                problem.accept("'" + raw + "': nothing can follow <rest>");
                return null;
            }
            sawOptional |= slot.optional();
            slots.add(slot);
        }
        if (slots.isEmpty() || slots.getFirst().kind() != Kind.LITERAL) {
            problem.accept("'" + raw + "': it must start with the command label");
            return null;
        }
        return new GivePattern(raw.trim(), List.copyOf(slots));
    }

    private static Slot slot(String token) {
        boolean required = token.startsWith("<") && token.endsWith(">");
        boolean optional = token.startsWith("[") && token.endsWith("]");
        if (!required && !optional) {
            return new Slot(Kind.LITERAL, token.toLowerCase(Locale.ROOT), false);
        }
        String name = token.substring(1, token.length() - 1).toLowerCase(Locale.ROOT);
        Kind kind = switch (name) {
            case "player", "target" -> Kind.PLAYER;
            case "item" -> Kind.ITEM;
            case "amount", "count" -> Kind.AMOUNT;
            case "any" -> Kind.ANY;
            case "rest" -> Kind.REST;
            default -> null;
        };
        return kind == null ? null : new Slot(kind, "", optional);
    }

    String label() {
        return slots.getFirst().literal();
    }

    boolean hasPlayer() {
        return slots.stream().anyMatch(slot -> slot.kind() == Kind.PLAYER);
    }
}
