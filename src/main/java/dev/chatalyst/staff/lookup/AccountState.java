package dev.chatalyst.staff.lookup;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Como se pinta cada cuenta en /dupeip e /ipreport:
 * [Conectado] [Desconectado] [Baneado] [Baneado por IP].
 *
 * <p>Un baneado se pinta como baneado aunque este conectado (puede estarlo un instante, o ser
 * un OP exento): lo que el staff busca al revisar multicuentas es si alguna ya cayo.
 */
public enum AccountState {
    BANNED, IP_BANNED, ONLINE, OFFLINE;

    public static AccountState of(boolean online, boolean banned, boolean ipBanned) {
        if (banned) return BANNED;
        if (ipBanned) return IP_BANNED;
        return online ? ONLINE : OFFLINE;
    }

    /** Lo que se ve de cada cuenta: estado y desde cuando no entra. */
    public record Row(UUID id, String name, AccountState state, long lastSeen) {
    }

    /**
     * Orden de la lista: la cuenta consultada primero, luego las conectadas y despues por
     * ultima vez vista (lo mas reciente arriba). Un nombre sin fecha (dato anterior al
     * esquema con fechas) va al final.
     */
    public static List<Row> sorted(List<Row> rows, UUID target) {
        return rows.stream().sorted(Comparator
                .comparing((Row row) -> !row.id().equals(target))
                .thenComparing(row -> row.state() != ONLINE)
                .thenComparing(Comparator.comparingLong(Row::lastSeen).reversed())
                .thenComparing(row -> row.name() == null ? "" : row.name(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
