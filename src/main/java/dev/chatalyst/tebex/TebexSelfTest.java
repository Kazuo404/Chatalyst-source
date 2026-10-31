package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.util.MM;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Buys a rank for real, checks every stage of the delivery, and gives it back.
 *
 * <p>The purchase pipeline can only be trusted end to end, and until now the only way to see
 * it work was to sell something to somebody. This walks the same path a Tebex payment takes —
 * ledger, reconciliation, LuckPerms, the storefront card — and says which stage answered and
 * which did not, instead of leaving an owner to guess from a rank that did or did not appear.
 *
 * <p>Two things keep it from being dangerous. The transaction id carries a reserved prefix, so
 * it can never collide with a real payment nor be mistaken for one in the ledger. And the
 * grant is revoked at the end whatever happened in between, including when a stage fails —
 * the report says so rather than leaving a rank behind.
 */
public final class TebexSelfTest {

    /** Nothing real can start with this: Tebex ids are digits and dashes. */
    static final String TRANSACTION_PREFIX = "chatalyst-selftest-";
    private static final long TEST_DURATION_MILLIS = 60_000L;
    private static final long STAGE_TIMEOUT_SECONDS = 10L;

    private final ChatalystPlugin plugin;
    private final TebexEntitlements entitlements;
    private final TebexReconciler reconciler;
    private final TebexShopService shop;

    public TebexSelfTest(ChatalystPlugin plugin, TebexEntitlements entitlements,
                         TebexReconciler reconciler, TebexShopService shop) {
        this.plugin = plugin;
        this.entitlements = entitlements;
        this.reconciler = reconciler;
        this.shop = shop;
    }

    /** One line of the report. */
    private record Step(String name, boolean ok, boolean skipped, String detail) {
        static Step pass(String name, String detail) {
            return new Step(name, true, false, detail);
        }

        static Step fail(String name, String detail) {
            return new Step(name, false, false, detail);
        }

        static Step skip(String name, String detail) {
            return new Step(name, false, true, detail);
        }
    }

    /**
     * Runs the whole thing off the server thread and reports back to {@code sender}.
     *
     * @param target the account to deliver to; the rank is returned before this finishes
     */
    public void run(CommandSender sender, UUID target, String targetName, String requestedRank) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
            List<Step> steps = new ArrayList<>();
            String transaction = TRANSACTION_PREFIX + UUID.randomUUID();
            String rankId = null;
            String group = null;
            try {
                rankId = pickRank(requestedRank, steps);
                if (rankId != null) {
                    group = groupOf(rankId);
                    checkLuckPerms(group, steps);
                    checkStorefront(rankId, targetName, target, steps);
                    if (alreadyHolds(target, group, steps)) {
                        // Ni se entrega ni se deshace: el undo le quitaria lo que ya tenia.
                        rankId = null;
                    } else if (deliver(target, rankId, group, transaction, steps)) {
                        checkApplied(target, group, steps);
                    }
                    checkDiscord(steps);
                }
            } catch (RuntimeException failure) {
                steps.add(Step.fail("unexpected", "the check itself failed: " + failure));
            } finally {
                if (rankId != null) undo(target, rankId, group, transaction, steps);
            }
            report(sender, targetName, steps);
        });
    }

    private String pickRank(String requested, List<Step> steps) {
        var catalog = plugin.getConfig().getConfigurationSection("Rank_Catalog");
        // Solo los rangos que se venden de verdad. Antes elegia el primero del catalogo por
        // orden alfabetico, y ese podia ser 'admin' u 'owner'.
        List<String> ids = catalog == null ? List.of()
                : catalog.getKeys(false).stream()
                        .filter(id -> catalog.getBoolean(id + ".Tebex_Enabled", false))
                        .map(id -> id.toLowerCase(Locale.ROOT)).sorted().toList();
        if (ids.isEmpty()) {
            steps.add(Step.fail("catalog",
                    "no rank in Rank_Catalog has Tebex_Enabled: true"));
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            String wanted = requested.trim().toLowerCase(Locale.ROOT);
            if (!ids.contains(wanted)) {
                steps.add(Step.fail("catalog",
                        "'" + wanted + "' is not a Tebex_Enabled rank in Rank_Catalog; those are "
                                + ids));
                return null;
            }
            steps.add(Step.pass("catalog", "using " + wanted));
            return wanted;
        }
        steps.add(Step.pass("catalog", ids.size() + " rank(s); testing with " + ids.get(0)));
        return ids.get(0);
    }

    private String groupOf(String rankId) {
        String group = plugin.getConfig()
                .getString("Rank_Catalog." + rankId + ".LuckPerms_Group", rankId);
        return group == null ? rankId : group.trim().toLowerCase(Locale.ROOT);
    }

    private void checkLuckPerms(String group, List<Step> steps) {
        if (!LuckPermsHook.isAvailable()) {
            steps.add(Step.fail("luckperms", "LuckPerms is not available"));
            return;
        }
        if (!LuckPermsHook.groupExists(group)) {
            steps.add(Step.fail("luckperms",
                    "the group '" + group + "' does not exist in LuckPerms"));
            return;
        }
        steps.add(Step.pass("luckperms", "the group '" + group + "' exists"));
    }

    private void checkStorefront(String rankId, String name, UUID id, List<Step> steps) {
        var entry = TebexShopEntry.resolve(plugin.getConfig(), null, rankId);
        if (entry.isEmpty()) {
            steps.add(Step.fail("storefront",
                    "the card is not shown: check Visible and Purchase_Url"));
            return;
        }
        if (entry.get().purchaseUrl(name, id).isEmpty()) {
            steps.add(Step.fail("storefront", "the purchase link does not resolve to https"));
            return;
        }
        steps.add(Step.pass("storefront",
                "the card reads '" + MM.strip(RankNames.of(entry.get().ownedGroup(), rankId))
                        + "' and its link resolves"));
    }

    /**
     * Si el jugador ya lleva el grupo, la prueba no se hace sobre el. Entregar y deshacer
     * encima de un grupo que ya tenia (puesto a mano, o comprado) podia acabar quitandoselo.
     */
    private boolean alreadyHolds(UUID target, String group, List<Step> steps) {
        Map<String, Long> held = await(LuckPermsHook.ourGroups(target), "groups");
        if (held == null) {
            steps.add(Step.fail("ledger", "the target's current groups could not be read;"
                    + " nothing was delivered"));
            return true;
        }
        if (!held.containsKey(group)) return false;
        steps.add(Step.fail("ledger", "the target already has '" + group + "'; run the test"
                + " on an account without it (nothing was delivered or removed)"));
        return true;
    }

    private boolean deliver(UUID target, String rankId, String group, String transaction,
                            List<Step> steps) {
        long expiry = System.currentTimeMillis() + TEST_DURATION_MILLIS;
        if (!entitlements.grant(target, rankId, group, expiry, transaction, "1m")) {
            steps.add(Step.fail("ledger", "the purchase could not be written to storage"));
            return false;
        }
        boolean stored = entitlements.of(target).stream()
                .anyMatch(e -> transaction.equals(e.transaction()));
        if (!stored) {
            steps.add(Step.fail("ledger", "the purchase was written but cannot be read back"));
            return false;
        }
        steps.add(Step.pass("ledger", "the purchase is recorded and owed"));
        return true;
    }

    private void checkApplied(UUID target, String group, List<Step> steps) {
        Boolean reconciled = await(reconciler.reconcile(target), "reconcile");
        if (reconciled == null) {
            steps.add(Step.fail("reconcile", "it did not finish within "
                    + STAGE_TIMEOUT_SECONDS + "s"));
            return;
        }
        Map<String, Long> held = await(LuckPermsHook.ourGroups(target), "groups");
        if (held == null) {
            steps.add(Step.fail("reconcile", "the groups could not be read back"));
            return;
        }
        if (!held.containsKey(group)) {
            steps.add(Step.fail("reconcile",
                    "'" + group + "' was not applied; LuckPerms holds " + held.keySet()));
            return;
        }
        long expiry = held.get(group);
        steps.add(Step.pass("reconcile", "'" + group + "' applied"
                + (expiry == 0L ? " permanently" : " with its expiry set")));
    }

    private void checkDiscord(List<Step> steps) {
        var bot = plugin.getDiscordBot();
        if (bot == null || !bot.linkingAvailable()) {
            steps.add(Step.skip("discord", "the bot is not connected; roles were not checked"));
            return;
        }
        steps.add(Step.pass("discord", "the bot is connected and can deliver roles"));
    }

    private void undo(UUID target, String rankId, String group, String transaction,
                      List<Step> steps) {
        boolean revoked = entitlements.revoke(target, rankId, transaction);
        Boolean reconciled = await(reconciler.reconcile(target), "undo");
        Map<String, Long> held = await(LuckPermsHook.ourGroups(target), "groups");
        boolean gone = held != null && !held.containsKey(group);
        if (revoked && reconciled != null && gone) {
            steps.add(Step.pass("undo", "the test rank was taken back"));
            return;
        }
        // Anything else has to be said out loud: a rank left behind is the one way this
        // check could cost the owner something.
        steps.add(Step.fail("undo", "the test rank may still be granted — remove '"
                + group + "' by hand if it is. Transaction " + transaction));
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future, String what) {
        try {
            return future.get(STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            plugin.getLogger().warning("Tebex self-test: " + what + " failed: " + failure);
            return null;
        }
    }

    private void report(CommandSender sender, String targetName, List<Step> steps) {
        long failed = steps.stream().filter(step -> !step.ok() && !step.skipped()).count();
        send(sender, "<#7B68A0>» <#C0B3D9>Prueba de compra sobre <#FBF2FF>" + targetName
                + "<#C0B3D9>:");
        for (Step step : steps) {
            String mark = step.skipped() ? "<#7B68A0>»" : step.ok() ? "<#3DFFA8>✔" : "<#FF4D6D>✖";
            send(sender, "  " + mark + " <#E0AAFF>" + step.name() + " <#7B68A0>· <#C0B3D9>"
                    + step.detail());
        }
        send(sender, failed == 0
                ? "<#3DFFA8>✔ <#C0B3D9>La compra funciona de principio a fin sin tocar Tebex."
                : "<#FF4D6D>✖ <#C0B3D9>" + failed + " etapa(s) fallaron: una compra real"
                        + " haria lo mismo.");
    }

    private void send(CommandSender sender, String message) {
        dev.chatalyst.util.FoliaTasks.global(plugin, () -> sender.sendMessage(MM.full(message)));
    }
}
