package dev.chatalyst.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Keeps storage keys within what the backend can actually store.
 *
 * <p>The SQL {@code id} column is {@code VARCHAR(120)}. A key longer than that is not a
 * cosmetic problem: MySQL in strict mode refuses the write (a paid purchase is rejected),
 * and in non-strict mode it silently truncates — at which point two different transactions
 * can collapse onto the same key and the second one is mistaken for a duplicate and never
 * delivered. Escaping makes this easy to hit: a transaction may be 128 characters and every
 * dot becomes three, so a fully dotted id reaches 384.
 */
public final class StoreKeys {

    /** Headroom under the column width for namespace/network prefixes added elsewhere. */
    public static final int MAX_KEY_LENGTH = 100;
    private static final int DIGEST_HEX_CHARS = 16;

    private StoreKeys() {
    }

    /**
     * Returns {@code key} unchanged when it fits, otherwise a deterministic shortened form.
     *
     * <p>Short keys — every realistic transaction id — are untouched, so existing rows keep
     * resolving exactly as before. Only keys that could not be stored safely in the first
     * place change shape, and they stay stable across restarts and backends because the
     * digest is computed from the full original.
     */
    public static String bounded(String key) {
        if (key == null) return null;
        if (key.length() <= MAX_KEY_LENGTH) return key;
        String digest = digest(key);
        int keep = MAX_KEY_LENGTH - DIGEST_HEX_CHARS - 1;
        return key.substring(0, keep) + "#" + digest;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_HEX_CHARS);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JRE ships SHA-256; falling back to a weaker identity would risk collisions
            // between two real purchases, so this must fail loudly instead.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
