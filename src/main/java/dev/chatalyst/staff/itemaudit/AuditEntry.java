package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Una fila del registro de objetos y gamemodes.
 *
 * <p>{@code actor} es siempre el staff responsable (quien saco el objeto, o el dueño de la
 * marca en una {@link AuditType#TRANSFER}); {@code target} es quien lo recibio o, en las
 * filas de gamemode, vacio. {@code detail} lleva lo legible (encantamientos, nombre, modo
 * "SURVIVAL>CREATIVE") y {@code source} el como (comando escrito, causa de Paper, cofre...).
 *
 * <p>Se guarda como una linea de campos separados por tabulador: legible con un editor,
 * {@code grep} funciona sobre el archivo, y no hace falta ninguna libreria para leerlo.
 */
public record AuditEntry(long time, String id, AuditType type,
                         UUID actorId, String actorName, String actorRank,
                         UUID targetId, String targetName,
                         String item, int amount, String detail, String source,
                         String world, int x, int y, int z, boolean valuable) {

    private static final int FIELDS = 17;
    private static final String NONE = "-";

    public AuditEntry {
        id = clean(id);
        actorName = clean(actorName);
        actorRank = clean(actorRank);
        targetName = clean(targetName);
        item = clean(item);
        detail = clean(detail);
        source = clean(source);
        world = clean(world);
        amount = Math.max(0, amount);
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    /** True si la fila tiene destinatario distinto del propio staff. */
    public boolean hasTarget() {
        return !targetName.isEmpty();
    }

    public String encode() {
        List<String> fields = List.of(
                Long.toString(time), id, type.name(),
                actorId == null ? NONE : actorId.toString(), actorName, actorRank,
                targetId == null ? NONE : targetId.toString(), targetName,
                item, Integer.toString(amount), detail, source,
                world, Integer.toString(x), Integer.toString(y), Integer.toString(z),
                valuable ? "1" : "0");
        StringBuilder out = new StringBuilder(128);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append('\t');
            escape(out, fields.get(i));
        }
        return out.toString();
    }

    /** Null si la linea esta corrupta o es de un tipo desconocido: se salta, no rompe nada. */
    public static AuditEntry decode(String line) {
        if (line == null || line.isBlank()) return null;
        List<String> f = split(line);
        if (f.size() < FIELDS) return null;
        AuditType type = AuditType.parse(f.get(2));
        if (type == null) return null;
        try {
            return new AuditEntry(Long.parseLong(f.get(0)), f.get(1), type,
                    uuid(f.get(3)), f.get(4), f.get(5), uuid(f.get(6)), f.get(7),
                    f.get(8), Integer.parseInt(f.get(9)), f.get(10), f.get(11),
                    f.get(12), Integer.parseInt(f.get(13)), Integer.parseInt(f.get(14)),
                    Integer.parseInt(f.get(15)), "1".equals(f.get(16)));
        } catch (IllegalArgumentException corrupt) {
            return null;
        }
    }

    private static UUID uuid(String raw) {
        return raw.isEmpty() || NONE.equals(raw) ? null : UUID.fromString(raw);
    }

    // Un nombre de yunque puede llevar tabuladores o saltos de linea: sin escapar, una sola
    // fila partiria el archivo y desplazaria los campos de todas las siguientes.
    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>(FIELDS);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                out.add(current.toString());
                current.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(++i);
                current.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
