package dev.chatalyst.network;

import dev.chatalyst.proxy.NetworkPacketCodec;
import dev.chatalyst.util.TimeUtil;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Bounded, deduplicated in-memory storage for network chat observations. */
final class NetworkChatBuffer {

    private static final int DEDUPE_SOFT_CAP = 5_000;
    private static final int DEDUPE_HARD_CAP = 10_000;
    private static final int MAX_TRACKED_SERVERS = 512;
    private final Map<String, ConcurrentLinkedDeque<NetworkMonitor.ChatEntry>> history =
            new ConcurrentHashMap<>();
    // Insercion acotada y serializada: no necesitamos CHM porque dedupe+tope son una sola
    // operacion atomica. LinkedHashMap evita barridos en cada paquete una vez lleno.
    private final Map<UUID, Long> seen = new LinkedHashMap<>();
    private long lastSeenPrune;
    private long lastServerPrune;
    private volatile int historySize;
    private volatile long retentionMs;

    NetworkChatBuffer(int historySize, long retentionMs) {
        configure(historySize, retentionMs);
    }

    void configure(int historySize, long retentionMs) {
        this.historySize = Math.max(1, historySize);
        this.retentionMs = Math.max(1L, retentionMs);
        long now = System.currentTimeMillis();
        history.values().forEach(queue -> {
            prune(queue, now);
            while (queue.size() > this.historySize) queue.pollLast();
        });
        pruneSeen(now, true);
    }

    boolean accept(NetworkPacketCodec.MonitorChat packet, long now) {
        long replayWindow = TimeUtil.deadline(retentionMs, 300_000L);
        if (replayWindow == TimeUtil.PERMANENT) replayWindow = Long.MAX_VALUE;
        if (!TimeUtil.withinDistance(now, packet.timestamp(), replayWindow)) return false;
        synchronized (seen) {
            if (seen.containsKey(packet.eventId())) return false;
            // Como maximo un barrido cada 10 s. La version anterior recorria 5 000 UUIDs
            // por CADA paquete despues del soft cap: una rafaga valida se volvia O(n^2).
            if (seen.size() >= DEDUPE_SOFT_CAP
                    && now - lastSeenPrune >= 10_000L) {
                pruneSeenLocked(now);
            }
            // Una rafaga dentro de la ventana de retencion no deja nada "viejo" que podar.
            // El limite duro evita que un peer autenticado roto crezca la cache sin fin.
            if (seen.size() >= DEDUPE_HARD_CAP) return false;
            seen.put(packet.eventId(), now);
        }
        var queue = queueFor(packet.serverId(), now);
        if (queue == null) {
            synchronized (seen) {
                seen.remove(packet.eventId());
            }
            return false;
        }
        var entry = new NetworkMonitor.ChatEntry(packet.eventId(), packet.timestamp(),
                packet.serverId(), packet.channel(), packet.player(), packet.message());
        queue.addFirst(entry);
        while (queue.size() > historySize) queue.pollLast();
        pruneSeen(now, false);
        return true;
    }

    void ensureServer(String serverId) {
        queueFor(serverId, System.currentTimeMillis());
    }

    Set<String> serverIds() {
        return Set.copyOf(history.keySet());
    }

    List<NetworkMonitor.ChatEntry> entries(String serverId, long now) {
        var queue = history.get(serverId);
        if (queue == null) return List.of();
        prune(queue, now);
        return List.copyOf(queue);
    }

    private void prune(ConcurrentLinkedDeque<NetworkMonitor.ChatEntry> queue, long now) {
        long cutoff = now - retentionMs;
        while (true) {
            var tail = queue.peekLast();
            if (tail == null || tail.timestamp() >= cutoff) return;
            queue.pollLast();
        }
    }

    private ConcurrentLinkedDeque<NetworkMonitor.ChatEntry> queueFor(String serverId, long now) {
        if (serverId == null || serverId.isBlank()) return null;
        ConcurrentLinkedDeque<NetworkMonitor.ChatEntry> existing = history.get(serverId);
        if (existing != null) return existing;
        synchronized (history) {
            existing = history.get(serverId);
            if (existing != null) return existing;
            if (history.size() >= MAX_TRACKED_SERVERS
                    && now - lastServerPrune >= 10_000L) {
                history.entrySet().removeIf(entry -> {
                    prune(entry.getValue(), now);
                    return entry.getValue().isEmpty();
                });
                lastServerPrune = now;
            }
            if (history.size() >= MAX_TRACKED_SERVERS) return null;
            var created = new ConcurrentLinkedDeque<NetworkMonitor.ChatEntry>();
            history.put(serverId, created);
            return created;
        }
    }

    private void pruneSeen(long now, boolean force) {
        synchronized (seen) {
            if (!force && seen.size() < DEDUPE_SOFT_CAP) return;
            pruneSeenLocked(now);
        }
    }

    private void pruneSeenLocked(long now) {
        long cutoff = now - retentionMs - 300_000L;
        seen.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        lastSeenPrune = now;
    }

    int seenCount() {
        synchronized (seen) {
            return seen.size();
        }
    }
}
