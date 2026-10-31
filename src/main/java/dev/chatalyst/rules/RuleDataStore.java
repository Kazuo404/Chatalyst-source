package dev.chatalyst.rules;

import dev.chatalyst.db.Store;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent, network-safe key/value state owned by rules for each player. */
final class RuleDataStore {

    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final int MAX_VALUE_LENGTH = 512;

    private final Store store;

    RuleDataStore(Store store) {
        this.store = store;
    }

    String get(UUID playerId, String key) {
        String normalized = normalizeKey(key);
        return normalized == null ? null : store.get(storageKey(playerId, normalized));
    }

    boolean put(UUID playerId, String key, String value) {
        String normalized = normalizeKey(key);
        if (normalized == null || value == null || value.length() > MAX_VALUE_LENGTH) return false;
        return store.putDurably(storageKey(playerId, normalized), clean(value));
    }

    boolean remove(UUID playerId, String key) {
        String normalized = normalizeKey(key);
        if (normalized == null) return false;
        return store.removeDurably(storageKey(playerId, normalized));
    }

    static String normalizeKey(String key) {
        if (key == null) return null;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return SAFE_KEY.matcher(normalized).matches() ? normalized : null;
    }

    private static String storageKey(UUID playerId, String key) {
        // Bukkit's YAML API treats dots as path separators. '~' is not accepted by
        // SAFE_KEY, so this encoding is collision-free and behaves identically in SQL.
        return playerId + ":" + key.replace('.', '~');
    }

    private static String clean(String value) {
        return value.replace('\u001e', ' ').replace('\u001f', ' ');
    }
}
