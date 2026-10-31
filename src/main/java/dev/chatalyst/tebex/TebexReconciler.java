package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.discord.DiscordRankSync;
import dev.chatalyst.hook.LuckPermsHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps reality matching {@link TebexEntitlements}.
 *
 * <p>Delivery is not modelled as a queue of orders to retry, but as a desired state to
 * converge on: the ledger says what a player owns, this recomputes the LuckPerms groups and
 * the Discord role that follow from it, and repairs any difference. A failed delivery is
 * simply drift, so it needs no separate retry bookkeeping — and so is drift from every other
 * cause, which a retry queue could never have noticed: a manual group edit, a Discord role
 * sync overwriting a paid rank, a restored database, or an expiry that has just come due.
 *
 * <p>It only ever touches groups Chatalyst itself delivered to that player, so a rank an
 * administrator granted by hand is never removed as drift.
 */
public final class TebexReconciler implements Listener {

    private static final long JOIN_DELAY_TICKS = 40L;
    private static final long SWEEP_START_SECONDS = 20L;

    private final ChatalystPlugin plugin;
    private final TebexEntitlements entitlements;
    private final DiscordRankSync rankSync;
    private final AtomicInteger cursor = new AtomicInteger();

    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask sweep;

    public TebexReconciler(ChatalystPlugin plugin, TebexEntitlements entitlements,
                           DiscordRankSync rankSync) {
        this.plugin = plugin;
        this.entitlements = entitlements;
        this.rankSync = rankSync;
    }

    // ---- lifecycle ----------------------------------------------------------

    public synchronized void start() {
        if (sweep != null) return;
        try {
            sweep = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                    ignored -> sweep(), SWEEP_START_SECONDS, intervalSeconds(),
                    TimeUnit.SECONDS);
        } catch (RuntimeException rejected) {
            sweep = null; // disabled between the check and the enqueue
        }
    }

    public synchronized void stop() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask current = sweep;
        sweep = null;
        if (current != null) current.cancel();
    }

    /**
     * Whether this player has paid for something whose Discord side cannot be delivered.
     *
     * <p>Only true when Discord rank sync is actually switched on and they still hold an
     * active entitlement: a purchase that already ran out is nobody's pending delivery, and a
     * server that does not sync ranks to Discord owes nothing there at all.
     */
    public boolean owesDiscordDelivery(UUID owner) {
        if (owner == null) return false;
        if (!plugin.getConfig().getBoolean("Discord.Rank_Sync.Enabled", false)) return false;
        long now = System.currentTimeMillis();
        return !TebexEntitlements.desiredGroups(entitlements.of(owner), now).isEmpty();
    }

    public int trackedEntitlements() {
        return entitlements.size();
    }

    // ---- triggers -----------------------------------------------------------

    /** Joining is the moment a missing rank is most visible, so it is repaired first. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, ignored -> reconcile(owner),
                JOIN_DELAY_TICKS * 50L, TimeUnit.MILLISECONDS);
    }

    /** Called after a purchase, a revoke, or when a buyer finally links their Discord. */
    public void reconcileSoon(UUID owner) {
        if (owner == null) return;
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> reconcile(owner));
        } catch (RuntimeException rejected) {
            // Shutting down: the ledger is on disk and the next startup sweep converges.
        }
    }

    // ---- the reconciliation itself ------------------------------------------

    /**
     * Converges one player. Runs off the main thread; every step is idempotent, so repeating
     * it costs nothing and interleaving with another pass cannot corrupt anything.
     *
     * @return true when reality now matches the ledger, Discord included.
     */
    public CompletableFuture<Boolean> reconcile(UUID owner) {
        if (owner == null) return CompletableFuture.completedFuture(false);
        List<TebexEntitlements.Entitlement> owned = entitlements.of(owner);
        if (owned.isEmpty()) return CompletableFuture.completedFuture(true);
        if (!LuckPermsHook.isAvailable()) {
            // Not an error: the ledger holds, and the sweep converges once LuckPerms loads.
            return CompletableFuture.completedFuture(false);
        }

        long now = System.currentTimeMillis();
        Map<String, Long> desired = TebexEntitlements.desiredGroups(owned, now);
        Set<String> managed = TebexEntitlements.managedGroups(owned);
        Set<String> permanent = TebexEntitlements.permanentlyManagedGroups(owned);

        return LuckPermsHook.reconcileGroupsAsync(owner, desired, managed, permanent)
                .thenCompose(applied -> {
                    if (!Boolean.TRUE.equals(applied)) {
                        plugin.getLogger().warning("Could not fully apply the ranks owned by "
                                + owner + "; check that every Rank_Catalog LuckPerms_Group"
                                + " exists. Retrying on the next pass.");
                        return CompletableFuture.completedFuture(false);
                    }
                    // The groups are right, so an ended entitlement has served its purpose
                    // and can stop being remembered. This happens even when its group is
                    // still held through another, still-paid purchase: that active record
                    // keeps the group both desired and managed. Keeping the ended one would
                    // make this player look permanently due for cleanup and starve the
                    // expiry half of the sweep.
                    for (TebexEntitlements.Entitlement entitlement : owned) {
                        if (!entitlement.active(now)) entitlements.forget(entitlement);
                    }
                    return pushToDiscord(owner);
                })
                .exceptionally(failure -> {
                    plugin.getLogger().warning("Rank reconciliation failed for " + owner
                            + ": " + failure + ". It will be retried.");
                    return false;
                });
    }

    private CompletableFuture<Boolean> pushToDiscord(UUID owner) {
        if (rankSync == null
                || !plugin.getConfig().getBoolean("Discord.Rank_Sync.Enabled", false)) {
            return CompletableFuture.completedFuture(true); // nothing owed on Discord
        }
        // deliverToDiscordNow reports whether the role really landed on the member, so an
        // offline bot or an account that is not linked yet simply converges on a later pass.
        return rankSync.deliverToDiscordNow(owner);
    }

    // ---- background sweep ----------------------------------------------------

    /**
     * Expiries first, then a rotation for everything else.
     *
     * <p>A rank is meant to end when the time bought in Tebex runs out, so an entitlement
     * whose time is up jumps the queue: LuckPerms already stopped applying the group at the
     * exact second (the expiry lives on the node itself), but the Discord role and the record
     * only go away here. Blind rotation could have left an expired buyer wearing their Discord
     * role for hours on a busy store.
     */
    private void sweep() {
        int budget = batchSize();
        long now = System.currentTimeMillis();

        List<UUID> ended = entitlements.ownersWithEndedTime(now);
        // Half the budget at most: if a cleanup keeps failing (a missing group, LuckPerms
        // down) it must not starve the drift rotation forever.
        int endedBudget = Math.max(1, budget / 2);
        int served = 0;
        for (UUID owner : ended) {
            if (served >= endedBudget) break;
            reconcile(owner);
            served++;
        }

        List<UUID> owners = entitlements.owners();
        if (owners.isEmpty()) return;
        for (int i = served; i < budget; i++) {
            int index = Math.floorMod(cursor.getAndIncrement(), owners.size());
            reconcile(owners.get(index));
        }
    }

    private long intervalSeconds() {
        return Math.max(30L, Math.min(3_600L,
                plugin.getConfig().getLong("Tebex.Reconcile.Interval_Seconds", 300L)));
    }

    private int batchSize() {
        return Math.max(1, Math.min(500,
                plugin.getConfig().getInt("Tebex.Reconcile.Batch_Size", 10)));
    }
}
