package dev.chatalyst.staff;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Orders network presence/offline packets and exposes reconnect generations. */
final class NetworkPresenceTracker {

    /** Reconnect generation of one player, plus when its presence was last refreshed. */
    private record Revision(long value, long touchedAt) { }

    private final Map<UUID, Long> offlineAt = new HashMap<>();
    private final Map<UUID, Revision> revisions = new HashMap<>();
    // Monotono durante toda la vida del tracker, y a proposito NO se reinicia en clear(): un
    // guard de freeze puede llevar en la mano una revision emitida antes del clear, y si la
    // numeracion volviera a empezar esa revision vieja dejaria de ser menor que la nueva —
    // el jugador reconectaria y el guard lo baneria igual.
    private long sequence;

    /** Returns false for a delayed presence packet older than the latest known departure. */
    synchronized boolean presence(UUID playerId, long timestamp) {
        Long offline = offlineAt.get(playerId);
        if (offline != null && timestamp <= offline) return false;
        revisions.put(playerId, new Revision(++sequence, timestamp));
        return true;
    }

    synchronized void offline(UUID playerId, long timestamp) {
        offlineAt.merge(playerId, timestamp, Math::max);
    }

    synchronized long revision(UUID playerId) {
        Revision current = revisions.get(playerId);
        return current == null ? 0L : current.value();
    }

    synchronized boolean newerThan(UUID playerId, long revision) {
        return revision(playerId) > revision;
    }

    /**
     * Olvida la salida y la generacion de reconexion de quien no da senales desde {@code
     * cutoff}. Sin esto {@code revisions} guardaba una entrada por cada UUID visto desde el
     * arranque y solo se vaciaba al parar el directorio.
     *
     * <p>Podar es seguro porque {@link #sequence} es monotono: un guard que sostiene la
     * revision R sigue viendo un valor estrictamente mayor si el jugador vuelve, y una
     * entrada ausente se lee como 0, que nunca es "mas nueva" que una revision real. Lo peor
     * que puede provocar un reloj desviado entre backends (el {@code touchedAt} viene del
     * emisor y el corte del reloj local) es reclamar memoria antes o despues, nunca un
     * veredicto equivocado.
     */
    synchronized void pruneBefore(long cutoff) {
        offlineAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        revisions.entrySet().removeIf(entry -> entry.getValue().touchedAt() < cutoff);
    }

    synchronized void clear() {
        offlineAt.clear();
        revisions.clear();
    }
}
