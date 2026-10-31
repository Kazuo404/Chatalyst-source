package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable ledger of what each player actually owns.
 *
 * <p>This is the source of truth for paid ranks. The LuckPerms group and the Discord role are
 * <em>derived</em> from it by {@link TebexReconciler}, never the other way round — so a rank
 * that drifted away for any reason (a manual edit, a Discord role sync, a restored backup, a
 * delivery that arrived while LuckPerms had not loaded) is repaired from this record instead
 * of being lost.
 *
 * <p>An ended entitlement is kept until its group has actually been removed. That is what
 * lets the reconciler know which groups it is allowed to take away: only ones it granted.
 */
public final class TebexEntitlements {

    private static final char US = '';

    private final ChatalystPlugin plugin;
    private final Store store;

    public TebexEntitlements(ChatalystPlugin plugin, StorageManager storage) {
        this(plugin, storage.store("tebex_entitlements"));
    }

    /** Test seam: the ledger never needs more of the server than its own store. */
    TebexEntitlements(ChatalystPlugin plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
    }

    /**
     * Records a purchase. Written durably before anything else happens, because everything
     * else can be rebuilt from it and it cannot be rebuilt from anything else.
     *
     * @return false when it could not be persisted; the caller must not report success.
     */
    public boolean grant(UUID owner, String rankId, String group, long expiresAt,
                         String transaction, String duration) {
        if (owner == null || !isSafeId(rankId) || group == null || group.isBlank()) return false;
        Entitlement entitlement = new Entitlement(owner, normalize(rankId), normalize(group),
                System.currentTimeMillis(), expiresAt, transaction == null ? "" : transaction,
                duration == null ? "" : duration, null, null);
        // One row PER PURCHASE, not per rank: a renewal is its own record, so a late refund of
        // the older payment cannot end the newer one. desiredGroups() already merges rows of
        // the same group by keeping the most generous expiry, so holding several is correct.
        if (store.putDurably(keyOf(entitlement), encode(entitlement))) return true;
        plugin.getLogger().severe("Could not persist the Tebex purchase " + rankId + " for "
                + owner + ". The rank was NOT recorded; deliver it manually.");
        return false;
    }

    /**
     * Ends the purchase made by {@code transaction}, leaving other purchases of the same rank
     * alone. The record survives until the reconciler confirms the group is gone, otherwise a
     * refund would leave the group behind forever.
     *
     * <p>Tying it to the transaction is what stops a late chargeback for last month's payment
     * from revoking the renewal the player already paid for.
     *
     * <p>Las renovaciones que se apilaron DETRAS de la compra revocada se adelantan el tiempo
     * que esa compra ya no cubre. Una renovacion empieza a contar donde acababa la anterior:
     * VIP 30d el dia 0 (A, hasta el dia 30) y otra el dia 1 (B, del 30 al 60). Reembolsar A
     * el dia 5 sin tocar B le dejaba el rango hasta el dia 60 habiendo pagado solo B; ahora B
     * pasa a acabar el dia 35, los 30 dias que si pago contados desde hoy.
     */
    public boolean revoke(UUID owner, String rankId, String transaction) {
        if (owner == null || !isSafeId(rankId)) return false;
        boolean allOk = true;
        boolean matchedAny = false;
        long now = System.currentTimeMillis();
        List<Entitlement> owned = of(owner);
        List<Entitlement> ended = new ArrayList<>();
        for (Entitlement current : owned) {
            if (!current.rankId().equals(normalize(rankId))) continue;
            // An empty transaction (legacy call sites, manual admin revoke) ends every
            // purchase of that rank, which is the old behaviour and still the safe default.
            if (transaction != null && !transaction.isBlank()
                    && !transaction.equalsIgnoreCase(current.transaction())) {
                continue;
            }
            matchedAny = true;
            if (!current.active(now)) continue;
            if (!store.compareAndPutDurably(current.storageKey(), current.encoded(),
                    encode(current.endedNow()))) {
                plugin.getLogger().severe("Could not persist the Tebex revoke of " + rankId
                        + " for " + owner + "; the rank stays active until it succeeds.");
                allOk = false;
                continue;
            }
            ended.add(current);
        }
        if (!ended.isEmpty()) pullStackedRenewals(owned, ended, now);
        // Nothing matching is not a failure: a revoke of something never delivered is
        // trivially satisfied, and refusing it would make Tebex look like it failed.
        return allOk || !matchedAny;
    }

    /**
     * Adelanta las compras que empezaban a contar despues de las que se acaban de revocar.
     *
     * <p>Solo las temporales del MISMO grupo que siguen vivas y cuyo inicio cae en o detras
     * del final de la revocada: una compra paralela (otro grupo, o que no se apilo) no se
     * toca. Si la escritura falla se queda como estaba, que es el comportamiento de antes:
     * nunca se le quita a nadie tiempo de mas.
     */
    static List<Entitlement> shiftedRenewals(List<Entitlement> owned, List<Entitlement> ended,
                                             long now) {
        List<Entitlement> shifted = new ArrayList<>();
        for (Entitlement renewal : owned) {
            if (renewal.expiresAt() == 0L || !renewal.active(now)) continue;
            if (ended.stream().anyMatch(e -> e.storageKey() != null
                    && e.storageKey().equals(renewal.storageKey()))) {
                continue;
            }
            long start = renewal.startsAt();
            long pull = 0L;
            for (Entitlement gone : ended) {
                if (gone.expiresAt() == 0L || !gone.group().equals(renewal.group())) continue;
                // Un segundo de margen: LuckPerms y los recibos redondean a segundos.
                if (start + 1_000L < gone.expiresAt()) continue;
                pull += Math.max(0L, gone.expiresAt() - Math.max(now, gone.startsAt()));
            }
            if (pull > 0L) shifted.add(renewal.withExpiry(renewal.expiresAt() - pull));
        }
        return shifted;
    }

    private void pullStackedRenewals(List<Entitlement> owned, List<Entitlement> ended,
                                     long now) {
        for (Entitlement renewal : shiftedRenewals(owned, ended, now)) {
            if (!store.compareAndPutDurably(renewal.storageKey(), renewal.encoded(),
                    encode(renewal))) {
                plugin.getLogger().warning("Could not shorten the Tebex renewal "
                        + renewal.transaction() + " of " + renewal.owner() + " after a refund"
                        + " of the purchase it was stacked on; it keeps its old expiry.");
            }
        }
    }

    /** Everything ever delivered to this player that has not been fully cleaned up yet. */
    public List<Entitlement> of(UUID owner) {
        if (owner == null) return List.of();
        String prefix = owner + ":";
        List<Entitlement> result = new ArrayList<>();
        for (var entry : store.all().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Entitlement decoded = decode(owner,
                    rankIdOf(entry.getKey().substring(prefix.length())), entry.getValue());
            if (decoded != null) {
                result.add(decoded.stored(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Owners whose ledger holds something that has run out of paid time and has not been
     * cleaned up yet. The sweep serves these first: LuckPerms drops the permissions at the
     * exact second on its own, but the Discord role and the record only go away when the
     * reconciler gets to them, and a rank ending on time is the whole point of a duration.
     */
    public List<UUID> ownersWithEndedTime(long now) {
        Set<UUID> owners = new LinkedHashSet<>();
        for (var entry : store.all().entrySet()) {
            int separator = entry.getKey().indexOf(':');
            if (separator <= 0) continue;
            UUID owner = parseUuid(entry.getKey().substring(0, separator));
            if (owner == null || owners.contains(owner)) continue;
            Entitlement decoded = decode(owner,
                    rankIdOf(entry.getKey().substring(separator + 1)), entry.getValue());
            if (decoded != null && !decoded.active(now)) owners.add(owner);
        }
        return List.copyOf(owners);
    }

    /** Owners with at least one stored entitlement, for the reconciliation sweep. */
    public List<UUID> owners() {
        Set<UUID> owners = new LinkedHashSet<>();
        for (String storageKey : store.all().keySet()) {
            int separator = storageKey.indexOf(':');
            if (separator <= 0) continue;
            UUID owner = parseUuid(storageKey.substring(0, separator));
            if (owner != null) owners.add(owner);
        }
        return List.copyOf(owners);
    }

    /**
     * Drops an ended record once its group has really been removed from the player.
     *
     * <p>Conditional on the exact value that was read. A reconciliation pass works from a
     * snapshot, and a renewal can land on the same row while it runs; removing by key alone
     * would delete the purchase that just arrived instead of the expired one it was told to
     * clean up.
     */
    public void forget(Entitlement entitlement) {
        if (entitlement == null || entitlement.storageKey() == null
                || entitlement.encoded() == null) {
            return;
        }
        store.compareAndRemoveDurably(entitlement.storageKey(), entitlement.encoded());
    }

    public int size() {
        return store.all().size();
    }

    /** Groups this player should hold right now, mapped to their absolute expiry. */
    public static Map<String, Long> desiredGroups(List<Entitlement> entitlements, long now) {
        Map<String, Long> desired = new LinkedHashMap<>();
        for (Entitlement entitlement : entitlements) {
            if (!entitlement.active(now)) continue;
            desired.merge(entitlement.group(), entitlement.expiresAt(),
                    (a, b) -> a == 0L || b == 0L ? 0L : Math.max(a, b));
        }
        return desired;
    }

    /**
     * Groups the reconciler is allowed to remove from this player: exactly the ones Chatalyst
     * delivered to them. A group an administrator granted by hand is never in this set, so it
     * is never mistaken for drift.
     */
    public static Set<String> managedGroups(List<Entitlement> entitlements) {
        Set<String> managed = new LinkedHashSet<>();
        for (Entitlement entitlement : entitlements) managed.add(entitlement.group());
        return Set.copyOf(managed);
    }

    /**
     * Grupos que Chatalyst entrego alguna vez SIN caducidad a este jugador. Solo sobre esos
     * puede la reconciliacion quitar o sustituir un nodo permanente; cualquier otro
     * permanente lo puso un admin. Cuenta tambien la compra permanente ya revocada (su
     * expiresAt deja de ser 0 al revocarse, pero la duracion guardada sigue diciendo
     * "permanent"), porque es justo la que hay que retirar.
     */
    public static Set<String> permanentlyManagedGroups(List<Entitlement> entitlements) {
        Set<String> permanent = new LinkedHashSet<>();
        for (Entitlement entitlement : entitlements) {
            if (entitlement.expiresAt() == 0L
                    || dev.chatalyst.util.TimeUtil.parseDuration(entitlement.duration())
                            == dev.chatalyst.util.TimeUtil.PERMANENT) {
                permanent.add(entitlement.group());
            }
        }
        return Set.copyOf(permanent);
    }

    // ---- encoding -----------------------------------------------------------

    /** One row per purchase: owner, rank and the payment that bought it. */
    private static String keyOf(Entitlement entitlement) {
        String transaction = entitlement.transaction() == null
                ? "" : entitlement.transaction().toLowerCase(Locale.ROOT).replace(".", "%2E");
        String base = entitlement.owner() + ":" + entitlement.rankId();
        return dev.chatalyst.db.StoreKeys.bounded(
                transaction.isBlank() ? base : base + ":" + transaction);
    }

    static String encode(Entitlement entitlement) {
        return String.join(String.valueOf(US), entitlement.group(),
                Long.toString(entitlement.grantedAt()), Long.toString(entitlement.expiresAt()),
                entitlement.transaction(), entitlement.duration());
    }

    static Entitlement decode(UUID owner, String rankId, String encoded) {
        if (owner == null || encoded == null || !isSafeId(rankId)) return null;
        String[] fields = encoded.split(String.valueOf(US), -1);
        if (fields.length != 5) return null;
        try {
            return new Entitlement(owner, normalize(rankId), fields[0],
                    Long.parseLong(fields[1]), Long.parseLong(fields[2]), fields[3], fields[4],
                    null, null);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * Rank id of a stored row, given everything after the owner.
     *
     * <p>A purchase is keyed {@code owner:rank:transaction}, so the remainder still carries
     * the payment that bought it. Handing that whole remainder to {@link #decode} makes it
     * fail {@link #isSafeId} on the colon and the row silently disappears — which is exactly
     * what used to hide every real purchase from the expiry sweep. Rows written before
     * per-transaction keying have no third segment and resolve unchanged.
     */
    static String rankIdOf(String remainder) {
        if (remainder == null) return "";
        int separator = remainder.indexOf(':');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }

    private static boolean isSafeId(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).matches("[a-z0-9_\\-]{1,64}");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * One thing a player owns. {@code expiresAt} 0 means permanent.
     *
     * <p>{@code storageKey} and {@code encoded} are the row this was read from; they are null
     * on a freshly built record and let every later write be conditional on exactly what was
     * observed, instead of on the key alone.
     */
    public record Entitlement(UUID owner, String rankId, String group, long grantedAt,
                              long expiresAt, String transaction, String duration,
                              String storageKey, String encoded) {

        public boolean active(long now) {
            return expiresAt == 0L || expiresAt > now;
        }

        Entitlement stored(String storageKey, String encoded) {
            return new Entitlement(owner, rankId, group, grantedAt, expiresAt, transaction,
                    duration, storageKey, encoded);
        }

        /**
         * Desde cuando cuenta: la caducidad menos lo comprado. Una renovacion se guarda con
         * su final ya extendido sobre la anterior, asi que su inicio es el final de aquella,
         * no el momento del pago. Sin duracion legible, el momento del pago.
         */
        long startsAt() {
            long bought = dev.chatalyst.util.TimeUtil.parseDuration(duration);
            if (expiresAt == 0L || bought <= 0L || bought == dev.chatalyst.util.TimeUtil.PERMANENT
                    || bought > expiresAt) {
                return grantedAt;
            }
            return Math.max(grantedAt, expiresAt - bought);
        }

        Entitlement withExpiry(long newExpiresAt) {
            return new Entitlement(owner, rankId, group, grantedAt, newExpiresAt, transaction,
                    duration, storageKey, encoded);
        }

        Entitlement endedNow() {
            // One millisecond in the past: already inactive, and still distinguishable from
            // "permanent" (0) so the reconciler knows to take the group away.
            return new Entitlement(owner, rankId, group, grantedAt,
                    System.currentTimeMillis() - 1L, transaction, duration,
                    storageKey, encoded);
        }
    }
}
