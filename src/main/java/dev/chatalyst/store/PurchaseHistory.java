package dev.chatalyst.store;

import dev.chatalyst.db.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a player ever got through the store, newest first: one-shot goods from the claim
 * ledger ({@code store_claims}) and ranks from the Tebex receipts ({@code tebex_rank_receipts}).
 *
 * <p>Read-only on purpose. Both ledgers keep their rows after delivery, which is what makes a
 * history possible at all; the entitlement records are NOT used because they are deleted once
 * a rank ends, so an expired rank would vanish from the player's past.
 */
public final class PurchaseHistory {

    private static final String US = String.valueOf((char) 31);

    /** Where an entry came from, read from the transaction id each channel writes. */
    public enum Origin {
        WEB, REWARD, GIFT, STORE;

        static Origin of(String transaction) {
            String tx = transaction == null ? "" : transaction.toLowerCase(Locale.ROOT);
            if (tx.startsWith("mp-") || tx.startsWith("prueba")) return WEB;
            if (tx.startsWith("vinculo-")) return REWARD;
            if (tx.startsWith("regalo-")) return GIFT;
            return STORE;
        }
    }

    public enum Kind { GOODS, RANK, RANK_REMOVED }

    /**
     * @param claim     the store claim behind a GOODS entry, null for ranks
     * @param rank      rank id for RANK entries, null for goods
     * @param expiresAt rank expiry in millis, 0 = permanent (ranks only)
     */
    public record Entry(long time, Kind kind, Origin origin, boolean pending,
                        StoreClaims.Claim claim, String rank, long expiresAt) {
    }

    private PurchaseHistory() {
    }

    public static List<Entry> of(UUID owner, List<StoreClaims.Claim> claims,
                                 Map<String, String> rankReceipts) {
        List<Entry> entries = new ArrayList<>();
        for (StoreClaims.Claim claim : claims) {
            entries.add(new Entry(claim.createdAt(), Kind.GOODS, Origin.of(claim.transaction()),
                    !claim.delivered(), claim, null, 0L));
        }
        if (owner != null && rankReceipts != null) {
            String uuid = owner.toString();
            for (var row : rankReceipts.entrySet()) {
                Entry rank = rankEntry(row.getKey(), row.getValue(), uuid);
                if (rank != null) entries.add(rank);
            }
        }
        entries.sort(Comparator.comparingLong(Entry::time).reversed());
        return List.copyOf(entries);
    }

    /** {@code verb:rank:tx} -> {@code DONE␟time␟uuid␟rank␟expires}; anything else is skipped. */
    static Entry rankEntry(String key, String value, String uuid) {
        if (key == null || value == null) return null;
        String[] id = key.split(":", 3);
        String[] fields = value.split(US, -1);
        if (id.length != 3 || fields.length < 5 || !fields[0].equals("DONE")
                || !fields[2].equalsIgnoreCase(uuid)) {
            return null;
        }
        try {
            long time = Long.parseLong(fields[1]);
            long expires = Long.parseLong(fields[4]);
            boolean grant = id[0].equals("grant");
            if (!grant && !id[0].equals("revoke")) return null;
            return new Entry(time, grant ? Kind.RANK : Kind.RANK_REMOVED, Origin.of(id[2]), false,
                    null, fields[3], grant ? expires : 0L);
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    /** "vip-survival" -> "Vip (Survival)", "mvp" -> "Mvp". */
    public static String rankName(String rank) {
        if (rank == null || rank.isBlank()) return "?";
        String base = rank;
        String suffix = "";
        int dash = rank.lastIndexOf('-');
        if (dash > 0) {
            base = rank.substring(0, dash);
            suffix = " (" + capitalize(rank.substring(dash + 1)) + ")";
        }
        return capitalize(base) + suffix;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Reads a whole receipts store; small (a row per paid rank operation). */
    static Map<String, String> receipts(Store store) {
        return store == null ? Map.of() : store.all();
    }
}
