package dev.chatalyst.staff.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Doble paso para lo que no se deshace (/staffrollback, /prunehistory): primero se enseña que
 * se va a hacer, y solo un "confirmar" de la MISMA persona, para la MISMA accion y en el
 * minuto siguiente lo ejecuta. Un "confirmar" escrito a ciegas, sin haber visto el resumen, o
 * el de otra accion distinta, no hace nada: borrar el historial de alguien por un comando
 * copiado del chat de otro no puede pasar.
 */
public final class ConfirmationGate {

    public static final long DEFAULT_TTL_MILLIS = 60_000L;
    private static final int SOFT_CAP = 256;

    private record Pending(String action, long expiresAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ConfirmationGate() {
        this(DEFAULT_TTL_MILLIS);
    }

    public ConfirmationGate(long ttlMillis) {
        this.ttlMillis = Math.max(1_000L, ttlMillis);
    }

    /** Apunta que {@code who} ha visto el resumen de {@code action}. */
    public void request(String who, String action, long now) {
        if (pending.size() >= SOFT_CAP) pending.values().removeIf(p -> p.expiresAt() <= now);
        pending.put(key(who), new Pending(action, now + ttlMillis));
    }

    /** True (y se consume) si {@code who} vio el resumen de ESTA accion hace menos del plazo. */
    public boolean confirm(String who, String action, long now) {
        Pending found = pending.get(key(who));
        if (found == null || found.expiresAt() <= now || !found.action().equals(action)) {
            return false;
        }
        return pending.remove(key(who), found);
    }

    private static String key(String who) {
        return who == null ? "" : who.toLowerCase(java.util.Locale.ROOT);
    }
}
