package dev.chatalyst.network;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Pure bounded subscription registry used by the live network monitor. */
final class NetworkWatchRegistry {

    private final Map<UUID, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private volatile int limit;

    NetworkWatchRegistry(int limit) {
        configure(limit);
    }

    void configure(int nextLimit) {
        limit = Math.max(1, nextLimit);
        subscriptions.values().forEach(set -> trim(set, limit));
    }

    int limit() {
        return limit;
    }

    int count(UUID viewer) {
        Set<String> set = subscriptions.get(viewer);
        return set == null ? 0 : set.size();
    }

    boolean isWatching(UUID viewer, String serverId) {
        Set<String> set = subscriptions.get(viewer);
        return set != null && set.contains(serverId);
    }

    Set<String> watched(UUID viewer) {
        Set<String> set = subscriptions.get(viewer);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    NetworkWatchManager.ToggleResult toggle(UUID viewer, String serverId, boolean allowed) {
        if (!allowed) return NetworkWatchManager.ToggleResult.DENIED;
        Set<String> set = subscriptions.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet());
        if (set.remove(serverId)) {
            if (set.isEmpty()) subscriptions.remove(viewer, set);
            return NetworkWatchManager.ToggleResult.DISABLED;
        }
        if (set.size() >= limit) return NetworkWatchManager.ToggleResult.LIMIT_REACHED;
        set.add(serverId);
        return NetworkWatchManager.ToggleResult.ENABLED;
    }

    boolean isEmpty() {
        return subscriptions.isEmpty();
    }

    void remove(UUID viewer) {
        subscriptions.remove(viewer);
    }

    private static void trim(Set<String> set, int limit) {
        if (set.size() <= limit) return;
        for (String value : new LinkedHashSet<>(set)) {
            if (set.size() <= limit) return;
            set.remove(value);
        }
    }
}
