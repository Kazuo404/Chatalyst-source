package dev.chatalyst.store;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Durable record of goods that are owed but not handed over yet.
 *
 * <p>Goods are the mirror image of ranks: a rank is a state that is repaired forever, a good
 * is an event that must happen <b>at most once</b>. So a claim is written before anything is
 * given, survives restarts while the buyer is offline or their inventory is full, and is
 * consumed the instant it is handed over — after which no resent transaction, double click or
 * second backend on the same database can ever deliver it again.
 */
public final class StoreClaims {

    private static final char US = '';

    private final ChatalystPlugin plugin;
    private final Store claims;

    public StoreClaims(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.claims = storage.store("store_claims");
    }

    /**
     * Reserves a purchase. Uses a conditional write keyed on the Tebex transaction, so a
     * resent command finds the key taken and cannot create a second claim.
     *
     * @return the outcome; only {@link Reservation#CREATED} means something is now owed.
     */
    public Reservation reserve(UUID owner, String productId, String transaction,
                               String snapshot) {
        String key = key(transaction, productId);
        if (key == null || owner == null) return Reservation.INVALID;
        String existing = claims.get(key);
        if (existing != null) {
            Claim decoded = decode(key, existing);
            if (decoded == null) return Reservation.INVALID;
            return decoded.delivered() ? Reservation.ALREADY_DELIVERED
                    : Reservation.ALREADY_PENDING;
        }
        Claim claim = new Claim(transaction, owner, productId,
                System.currentTimeMillis(), false, snapshot == null ? "" : snapshot);
        if (claims.compareAndPutDurably(key, null, encode(claim))) return Reservation.CREATED;
        // Lost the race with another backend, which now owns the delivery.
        return claims.get(key) == null ? Reservation.STORAGE_FAILED
                : Reservation.ALREADY_PENDING;
    }

    /**
     * Marks a claim handed over. Conditional on it still being pending, so two servers
     * delivering at the same instant cannot both succeed.
     *
     * @return true when this caller is the one that consumed it and may give the goods.
     */
    public boolean consume(Claim claim) {
        String key = key(claim.transaction(), claim.productId());
        if (key == null) return false;
        String current = claims.get(key);
        if (current == null) return false;
        Claim stored = decode(key, current);
        if (stored == null || stored.delivered()) return false;
        return claims.compareAndPutDurably(key, current, encode(stored.asDelivered()));
    }

    /** Everything still owed to this player. */
    public List<Claim> pendingFor(UUID owner) {
        List<Claim> pending = new ArrayList<>();
        if (owner == null) return pending;
        for (var entry : claims.all().entrySet()) {
            Claim claim = decode(entry.getKey(), entry.getValue());
            if (claim != null && !claim.delivered() && owner.equals(claim.owner())) {
                pending.add(claim);
            }
        }
        return List.copyOf(pending);
    }

    /** Every claim of this player, delivered or not: the raw material of the purchase history. */
    public List<Claim> allFor(UUID owner) {
        List<Claim> all = new ArrayList<>();
        if (owner == null) return all;
        for (var entry : claims.all().entrySet()) {
            Claim claim = decode(entry.getKey(), entry.getValue());
            if (claim != null && owner.equals(claim.owner())) all.add(claim);
        }
        return List.copyOf(all);
    }

    /** Owners with something still owed, so the retry pass can skip everyone else. */
    public List<UUID> ownersWithPending() {
        java.util.Set<UUID> owners = new java.util.LinkedHashSet<>();
        for (var entry : claims.all().entrySet()) {
            Claim claim = decode(entry.getKey(), entry.getValue());
            if (claim != null && !claim.delivered()) owners.add(claim.owner());
        }
        return List.copyOf(owners);
    }

    public int pendingCount() {
        int count = 0;
        for (var entry : claims.all().entrySet()) {
            Claim claim = decode(entry.getKey(), entry.getValue());
            if (claim != null && !claim.delivered()) count++;
        }
        return count;
    }

    /** Puts a consumed claim back after the handover itself failed. */
    public void restore(Claim claim) {
        String key = key(claim.transaction(), claim.productId());
        if (key == null) return;
        String current = claims.get(key);
        if (current == null) return;
        Claim stored = decode(key, current);
        if (stored == null || !stored.delivered()) return;
        if (!claims.compareAndPutDurably(key, current, encode(stored.asPending()))) {
            plugin.getLogger().severe("Could not reopen store claim " + claim.transaction()
                    + " after a failed handover. Deliver " + claim.productId()
                    + " to " + claim.owner() + " manually.");
        }
    }

    // ---- encoding -----------------------------------------------------------

    /**
     * One key per PACKAGE, not per payment.
     *
     * <p>Tebex's {@code {transaction}} identifies the payment, and a single payment can carry
     * several packages — a cart with two products runs two commands with the same id. Keyed on
     * the transaction alone, the second product found the key taken, was reported as an
     * already-handled duplicate, and was never delivered.
     */
    static String key(String transaction, String productId) {
        if (transaction == null) return null;
        String normalized = transaction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._:@\\-]{3,128}")) return null;
        String product = productId == null ? "" : productId.trim().toLowerCase(Locale.ROOT);
        if (!product.matches("[a-z0-9_\\-]{1,64}")) return null;
        // Bukkit YAML treats dots as path separators, so one transaction must not be split
        // across nested keys or two purchases could collapse into one. Escaping can triple
        // the length, so the result is also bounded to what the SQL id column can hold.
        return dev.chatalyst.db.StoreKeys.bounded(
                normalized.replace(".", "%2E") + ":" + product);
    }

    static String encode(Claim claim) {
        return String.join(String.valueOf(US), claim.delivered() ? "DONE" : "PENDING",
                claim.owner().toString(), claim.productId(),
                Long.toString(claim.createdAt()), claim.transaction(),
                claim.snapshot() == null ? "" : claim.snapshot());
    }

    static Claim decode(String storageKey, String encoded) {
        if (storageKey == null || encoded == null) return null;
        String[] fields = encoded.split(String.valueOf(US), -1);
        // Five fields is the pre-snapshot layout; those claims fall back to the catalog.
        if (fields.length != 5 && fields.length != 6) return null;
        try {
            String state = fields[0];
            if (!state.equals("PENDING") && !state.equals("DONE")) return null;
            return new Claim(fields[4], UUID.fromString(fields[1]), fields[2],
                    Long.parseLong(fields[3]), state.equals("DONE"),
                    fields.length == 6 ? fields[5] : "");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public enum Reservation {
        CREATED, ALREADY_PENDING, ALREADY_DELIVERED, STORAGE_FAILED, INVALID
    }

    /**
     * One owed (or already handed over) purchase.
     *
     * <p>{@code snapshot} freezes what was actually sold, so editing or reusing a catalog id
     * while the buyer is offline cannot change what they receive. Empty on claims written
     * before snapshots existed; those still resolve through the catalog.
     */
    public record Claim(String transaction, UUID owner, String productId, long createdAt,
                        boolean delivered, String snapshot) {

        Claim asDelivered() {
            return new Claim(transaction, owner, productId, createdAt, true, snapshot);
        }

        Claim asPending() {
            return new Claim(transaction, owner, productId, createdAt, false, snapshot);
        }
    }
}
