package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import org.bukkit.configuration.ConfigurationSection;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Persistent one-to-one Minecraft/Discord account links.
 *
 * <p>Link codes are stored in the shared storage backend instead of only in this JVM. This
 * matters on networks: whichever backend receives {@code !link} first can complete the same
 * code safely, and a restart during the ten-minute linking window does not invalidate it.
 * All durable writes run on Paper's async scheduler, never on a chat, JDA or region thread.
 */
public final class DiscordLinkManager {

    private static final char US = '\u001F';
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final long REQUEST_COOLDOWN_MILLIS = 15_000L;
    private static final int MAX_TRACKED_REQUESTS = 512;

    private final ChatalystPlugin plugin;
    private final Store links;
    private final Store codes;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, String>> observers = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<UUID, String>> unlinkObservers = new CopyOnWriteArrayList<>();

    public DiscordLinkManager(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.links = storage.store("discord_links");
        this.codes = storage.store("discord_link_codes");
    }

    /**
     * Observes durable link changes: the Discord ID is the new partner, or {@code null} when
     * the account was unlinked. Observers run on the async scheduler thread that persisted the
     * change, so they must hop to a region thread before touching player APIs.
     */
    public void observe(BiConsumer<UUID, String> observer) {
        if (observer != null) observers.add(observer);
    }

    /**
     * Told when a link is broken, with the Discord account it USED to point at.
     *
     * <p>{@link #observe} reports an unlink as a null id, which is right for "there is no
     * link any more" but useless to anyone who has to undo something on that account: by the
     * time they hear about it, the row is gone and there is nothing left to look up.
     */
    public void observeUnlink(BiConsumer<UUID, String> observer) {
        if (observer != null) unlinkObservers.add(observer);
    }

    private void notifyObservers(UUID minecraftId, String discordId) {
        for (BiConsumer<UUID, String> observer : observers) {
            try {
                observer.accept(minecraftId, discordId);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("A Discord link observer failed: " + failure);
            }
        }
    }

    /** Seconds a freshly issued code stays valid, already clamped to the supported range. */
    public long codeExpirySeconds() {
        return configuredExpiryMillis() / 1_000L;
    }

    /** Drops the throttling entry of a player who left; the map is per-session bookkeeping. */
    public void forget(UUID minecraftId) {
        if (minecraftId != null) lastRequests.remove(minecraftId);
    }

    public String discordId(UUID minecraftId) {
        if (minecraftId == null) return null;
        String stored = links.get(minecraftKey(minecraftId));
        if (DiscordBot.isSnowflake(stored)) return stored;
        return manualLinks().get(minecraftId);
    }

    public UUID minecraftId(String discordId) {
        if (!DiscordBot.isSnowflake(discordId)) return null;
        UUID stored = parseUuid(links.get(discordKey(discordId)));
        if (stored != null) return stored;
        for (var entry : manualLinks().entrySet()) {
            if (discordId.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    /** Snapshot used for a bounded synchronization pass when the Discord gateway becomes ready. */
    public Map<UUID, String> allLinks() {
        Map<UUID, String> result = new LinkedHashMap<>(manualLinks());
        for (var entry : links.all().entrySet()) {
            if (!entry.getKey().startsWith("mc:")) continue;
            UUID minecraftId = parseUuid(entry.getKey().substring(3));
            if (minecraftId != null && DiscordBot.isSnowflake(entry.getValue())) {
                result.put(minecraftId, entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    public CompletableFuture<LinkCode> createCode(UUID minecraftId, String playerName) {
        if (minecraftId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("missing Minecraft UUID"));
        }
        long now = System.currentTimeMillis();
        if (!claimRequestSlot(minecraftId, now)) {
            return CompletableFuture.completedFuture(
                    new LinkCode("", 0L, Status.RATE_LIMITED));
        }
        return async(() -> {
            purgeExpiredCodes(System.currentTimeMillis(), 64);
            long expires = System.currentTimeMillis() + configuredExpiryMillis();
            String value = minecraftId + String.valueOf(US)
                    + cleanName(playerName) + US + expires;
            for (int attempt = 0; attempt < 24; attempt++) {
                String code = randomCode();
                if (codes.compareAndPutDurably(code, null, value)) {
                    return new LinkCode(code, expires, Status.SUCCESS);
                }
            }
            throw new IllegalStateException("could not reserve a unique Discord link code");
        });
    }

    /**
     * Consumes a code and establishes the one-to-one mapping. A code is claimed with CAS, so
     * two bot sessions cannot both complete it on a shared database.
     */
    public CompletableFuture<LinkResult> complete(String rawCode, String discordId,
                                                   String discordName) {
        if (!DiscordBot.isSnowflake(discordId)) {
            return CompletableFuture.completedFuture(
                    new LinkResult(Status.INVALID, null, ""));
        }
        String code = normalizeCode(rawCode);
        if (code.length() != CODE_LENGTH) {
            return CompletableFuture.completedFuture(
                    new LinkResult(Status.INVALID, null, ""));
        }
        return async(() -> {
            String encoded = codes.get(code);
            Pending pending = decodePending(encoded);
            if (pending == null) return new LinkResult(Status.INVALID, null, "");
            if (pending.expiresAt() <= System.currentTimeMillis()) {
                codes.compareAndRemoveDurably(code, encoded);
                return new LinkResult(Status.EXPIRED, pending.minecraftId(), pending.playerName());
            }
            if (!codes.compareAndRemoveDurably(code, encoded)) {
                return new LinkResult(Status.ALREADY_USED, pending.minecraftId(),
                        pending.playerName());
            }
            Linked linked = linkDurably(pending.minecraftId(), discordId);
            if (!linked.ok()) {
                // Restore the still-valid code when persistence failed so the player can retry.
                codes.compareAndPutDurably(code, null, encoded);
                return new LinkResult(Status.STORAGE_FAILED, pending.minecraftId(),
                        pending.playerName());
            }
            plugin.getLogger().info("Linked Minecraft account " + pending.minecraftId()
                    + " to Discord user " + safeDiscordName(discordName)
                    + " (" + discordId + ").");
            // Re-vincular a OTRO Discord es desvincular el anterior: sin este aviso la cuenta
            // vieja se quedaba con el rol de vinculado y los roles de rango para siempre.
            if (linked.replacedDiscord() != null) {
                notifyUnlinked(pending.minecraftId(), linked.replacedDiscord());
            }
            notifyObservers(pending.minecraftId(), discordId);
            return new LinkResult(Status.SUCCESS, pending.minecraftId(), pending.playerName());
        });
    }

    /**
     * Links without a code, for a trusted caller that already proved BOTH sides: the web store
     * verifies the Discord account with OAuth and the Minecraft account with a code typed in
     * game, then asks for this through the console. Same storage path and same observers as
     * {@link #complete}, so the linked role, the rank sync and the link reward all follow.
     *
     * @return {@code SUCCESS}, or {@code INVALID} / {@code STORAGE_FAILED}
     */
    public CompletableFuture<Status> forceLink(UUID minecraftId, String discordId,
                                               String source) {
        if (minecraftId == null || !DiscordBot.isSnowflake(discordId)) {
            return CompletableFuture.completedFuture(Status.INVALID);
        }
        return async(() -> {
            if (discordId.equals(links.get(minecraftKey(minecraftId)))) {
                return Status.SUCCESS; // already exactly this link: nothing to announce again
            }
            Linked linked = linkDurably(minecraftId, discordId);
            if (!linked.ok()) return Status.STORAGE_FAILED;
            plugin.getLogger().info("Linked Minecraft account " + minecraftId + " to Discord "
                    + discordId + " (verified by " + safeDiscordName(source) + ").");
            if (linked.replacedDiscord() != null) {
                notifyUnlinked(minecraftId, linked.replacedDiscord());
            }
            notifyObservers(minecraftId, discordId);
            return Status.SUCCESS;
        });
    }

    public CompletableFuture<Boolean> unlinkMinecraft(UUID minecraftId) {
        if (minecraftId == null) return CompletableFuture.completedFuture(false);
        return async(() -> {
            String mcKey = minecraftKey(minecraftId);
            String discordId = links.get(mcKey);
            if (!DiscordBot.isSnowflake(discordId)) return false;
            boolean first = links.compareAndRemoveDurably(mcKey, discordId);
            String dcKey = discordKey(discordId);
            String reverse = links.get(dcKey);
            boolean second = reverse == null || !minecraftId.toString().equals(reverse)
                    || links.compareAndRemoveDurably(dcKey, reverse);
            if (first && second) {
                notifyObservers(minecraftId, null);
                notifyUnlinked(minecraftId, discordId);
            }
            return first && second;
        });
    }

    /** Tells the unlink observers this Discord account no longer belongs to the player. */
    private void notifyUnlinked(UUID minecraftId, String discordId) {
        for (BiConsumer<UUID, String> observer : unlinkObservers) {
            try {
                observer.accept(minecraftId, discordId);
            } catch (RuntimeException failure) {
                // An observer that breaks must not undo an unlink already stored.
                plugin.getLogger().warning("An unlink observer failed: " + failure);
            }
        }
    }

    /**
     * @param replacedDiscord the Discord account this link displaced, or null when the player
     *                        had none (or it was the same one)
     */
    private record Linked(boolean ok, String replacedDiscord) {
        static final Linked FAILED = new Linked(false, null);
    }

    /**
     * Atomically reserves this player's request slot. A throttled attempt must NOT overwrite
     * the stored timestamp: doing so let a player who kept spamming the command push their own
     * cooldown forward forever, so the code they were waiting for never became obtainable.
     */
    private boolean claimRequestSlot(UUID minecraftId, long now) {
        Long previous = lastRequests.get(minecraftId);
        if (previous != null && now - previous < REQUEST_COOLDOWN_MILLIS) return false;
        boolean claimed = previous == null
                ? lastRequests.putIfAbsent(minecraftId, now) == null
                : lastRequests.replace(minecraftId, previous, now);
        if (claimed && lastRequests.size() > MAX_TRACKED_REQUESTS) {
            lastRequests.entrySet().removeIf(entry ->
                    now - entry.getValue() >= REQUEST_COOLDOWN_MILLIS);
        }
        return claimed;
    }

    private Linked linkDurably(UUID minecraftId, String discordId) {
        synchronized (this) {
            String mcKey = minecraftKey(minecraftId);
            String dcKey = discordKey(discordId);
            String previousDiscord = links.get(mcKey);
            String previousMinecraft = links.get(dcKey);
            if (discordId.equals(previousDiscord)
                    && minecraftId.toString().equals(previousMinecraft)) {
                return new Linked(true, null);
            }
            String replaced = DiscordBot.isSnowflake(previousDiscord)
                    && !discordId.equals(previousDiscord) ? previousDiscord : null;

            // Detach both previous partners first. Conditional removals never erase a newer
            // mapping installed concurrently by another backend.
            if (DiscordBot.isSnowflake(previousDiscord)) {
                String oldReverseKey = discordKey(previousDiscord);
                String oldReverse = links.get(oldReverseKey);
                if (minecraftId.toString().equals(oldReverse)) {
                    links.compareAndRemoveDurably(oldReverseKey, oldReverse);
                }
            }
            UUID previousUuid = parseUuid(previousMinecraft);
            if (previousUuid != null) {
                String oldForwardKey = minecraftKey(previousUuid);
                String oldForward = links.get(oldForwardKey);
                if (discordId.equals(oldForward)) {
                    links.compareAndRemoveDurably(oldForwardKey, oldForward);
                }
            }

            if (!replaceDurably(mcKey, previousDiscord, discordId)) return Linked.FAILED;
            if (replaceDurably(dcKey, previousMinecraft, minecraftId.toString())) {
                return new Linked(true, replaced);
            }

            // Best-effort rollback keeps one-sided links out of the normal lookup path.
            String installed = links.get(mcKey);
            if (discordId.equals(installed)) {
                if (previousDiscord == null) links.compareAndRemoveDurably(mcKey, installed);
                else links.compareAndPutDurably(mcKey, installed, previousDiscord);
            }
            return Linked.FAILED;
        }
    }

    /**
     * Conditional on the value this link decision was actually taken from.
     *
     * <p>Retrying against a freshly re-read value defeated the point: {@code synchronized}
     * only serialises one JVM, so two backends completing codes for the same account both
     * read "nothing linked", and the loser would happily overwrite the winner. Both then
     * believed they had succeeded and two Discord ids resolved to one player. Losing the CAS
     * must fail, so the caller restores the code and the player simply retries.
     */
    private boolean replaceDurably(String key, String observed, String value) {
        String current = links.get(key);
        if (value.equals(current)) return true; // already exactly what we wanted
        return links.compareAndPutDurably(key, observed, value);
    }

    private <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                future.complete(operation.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void purgeExpiredCodes(long now, int limit) {
        int inspected = 0;
        for (var entry : codes.all().entrySet()) {
            if (inspected++ >= limit) break;
            Pending pending = decodePending(entry.getValue());
            if (pending == null || pending.expiresAt() <= now) {
                codes.compareAndRemoveDurably(entry.getKey(), entry.getValue());
            }
        }
    }

    private long configuredExpiryMillis() {
        long seconds = Math.max(60L, Math.min(3_600L,
                plugin.getConfig().getLong("Discord.Linking.Code_Expires_Seconds", 600L)));
        return seconds * 1_000L;
    }

    private Map<UUID, String> manualLinks() {
        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("Discord.Linked_Accounts");
        if (section == null) return Map.of();
        Map<UUID, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            UUID uuid = parseUuid(key);
            String discordId = section.getString(key, "").trim();
            if (uuid != null && DiscordBot.isSnowflake(discordId)) {
                result.put(uuid, discordId);
            }
        }
        return result;
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    static String normalizeCode(String raw) {
        return raw == null ? "" : raw.trim().replace("-", "")
                .toUpperCase(Locale.ROOT);
    }

    private static Pending decodePending(String value) {
        if (value == null) return null;
        String[] fields = value.split(String.valueOf(US), -1);
        if (fields.length != 3) return null;
        UUID uuid = parseUuid(fields[0]);
        if (uuid == null) return null;
        try {
            return new Pending(uuid, fields[1], Long.parseLong(fields[2]));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() == 32) {
            normalized = normalized.substring(0, 8) + "-" + normalized.substring(8, 12)
                    + "-" + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-" + normalized.substring(20);
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String minecraftKey(UUID uuid) {
        return "mc:" + uuid;
    }

    private static String discordKey(String id) {
        return "dc:" + id;
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        return name.replace(US, ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String safeDiscordName(String name) {
        String safe = cleanName(name);
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    public enum Status {
        SUCCESS, INVALID, EXPIRED, ALREADY_USED, RATE_LIMITED, STORAGE_FAILED
    }

    public record LinkCode(String code, long expiresAt, Status status) {
    }

    public record LinkResult(Status status, UUID minecraftId, String playerName) {
    }

    private record Pending(UUID minecraftId, String playerName, long expiresAt) {
    }
}
