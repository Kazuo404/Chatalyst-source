package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Junta en UN aviso todo lo que la misma clave (staff + tipo + destinatario) produce dentro
 * de una ventana: sacar 30 stacks en 5 segundos es un aviso resumido, no 30 lineas que
 * entierran el chat del staff y que nadie lee.
 *
 * <p>La ventana empieza con la primera entrada y no se alarga: una rafaga continua de un
 * minuto sale como varios resumenes seguidos, no como un silencio de un minuto.
 */
final class BurstAggregator<K, E> {

    /** Una rafaga cerrada. {@code dropped} cuenta lo que no cupo en la lista (sigue en el log). */
    record Burst<K, E>(K key, long start, List<E> entries, int dropped) {
    }

    private static final class Open<E> {
        final long start;
        final List<E> entries = new ArrayList<>();
        int dropped;

        Open(long start) {
            this.start = start;
        }
    }

    private final long windowMillis;
    private final int maxEntries;
    private final Map<K, Open<E>> open = new LinkedHashMap<>();

    BurstAggregator(long windowMillis, int maxEntries) {
        this.windowMillis = Math.max(0L, windowMillis);
        this.maxEntries = Math.max(1, maxEntries);
    }

    synchronized void add(K key, E entry, long now) {
        Open<E> burst = open.computeIfAbsent(key, ignored -> new Open<>(now));
        if (burst.entries.size() < maxEntries) {
            burst.entries.add(entry);
        } else {
            burst.dropped++;
        }
    }

    /** Las rafagas cuya ventana ya termino, en el orden en que empezaron. */
    synchronized List<Burst<K, E>> drain(long now) {
        List<Burst<K, E>> out = new ArrayList<>();
        Iterator<Map.Entry<K, Open<E>>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, Open<E>> next = it.next();
            Open<E> burst = next.getValue();
            if (now - burst.start < windowMillis) continue;
            out.add(new Burst<>(next.getKey(), burst.start, List.copyOf(burst.entries),
                    burst.dropped));
            it.remove();
        }
        return out;
    }

    /** Todo lo pendiente, al apagar: un aviso a medias se manda, no se pierde. */
    synchronized List<Burst<K, E>> drainAll() {
        return drain(Long.MAX_VALUE);
    }

    synchronized boolean isEmpty() {
        return open.isEmpty();
    }
}
