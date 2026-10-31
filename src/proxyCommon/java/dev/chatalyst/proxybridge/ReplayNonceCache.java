package dev.chatalyst.proxybridge;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Bounded replay window that rejects new entries while all retained nonces remain live. */
final class ReplayNonceCache {

    static final int MAX_ENTRIES = 10_000;
    private static final long PRUNE_INTERVAL_MS = 1_000L;

    private final long replayWindowMs;
    private final Map<String, Long> nonces = new HashMap<>();
    private long nextPruneAt;

    ReplayNonceCache(long replayWindowMs) {
        this.replayWindowMs = replayWindowMs;
    }

    synchronized boolean remember(byte[] nonce, long timestamp, long nowMs) {
        pruneIfDue(nowMs);
        String key = Base64.getEncoder().encodeToString(nonce);
        if (nonces.containsKey(key) || nonces.size() >= MAX_ENTRIES) return false;
        nonces.put(key, timestamp);
        return true;
    }

    synchronized int size() {
        return nonces.size();
    }

    private void pruneIfDue(long nowMs) {
        if (nowMs < nextPruneAt) return;
        long cutoff = nowMs - replayWindowMs;
        nonces.values().removeIf(value -> value < cutoff);
        nextPruneAt = nowMs + PRUNE_INTERVAL_MS;
    }
}
