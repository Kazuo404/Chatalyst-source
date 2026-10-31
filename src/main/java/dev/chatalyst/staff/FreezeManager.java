package dev.chatalyst.staff;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import dev.chatalyst.db.StorageManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent review-freeze state. Event enforcement lives in {@link FreezeListener}. */
public final class FreezeManager {

    public enum RemovalResult { REMOVED, NOT_FOUND, STORAGE_FAILED }

    private static final String CONSUMED = "freeze-consumed";
    private final Store store;
    private final Map<UUID, FreezeRecord> frozen = new ConcurrentHashMap<>();

    public FreezeManager(StorageManager storage) {
        this(storage.store("staff_freezes"));
    }

    FreezeManager(Store store) {
        this.store = store;
    }

    public void load() {
        frozen.clear();
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                FreezeRecord record = FreezeRecord.decode(entry.getValue());
                if (record == null) store.compareAndRemoveDurably(entry.getKey(), entry.getValue());
                else frozen.put(id, record);
            } catch (RuntimeException ex) {
                store.compareAndRemoveDurably(entry.getKey(), entry.getValue());
            }
        }
    }

    public FreezeRecord get(UUID id) {
        String key = id.toString();
        for (int attempt = 0; attempt < 5; attempt++) {
            String raw = store.get(key);
            if (raw == null || CONSUMED.equals(raw)) {
                frozen.remove(id);
                return null;
            }
            FreezeRecord record = FreezeRecord.decode(raw);
            if (record != null) {
                frozen.put(id, record);
                return record;
            }
            // Retire corrupt data conditionally. An unconditional remove could erase a valid
            // freeze written by another backend after our read.
            if (store.compareAndPutDurably(key, raw, CONSUMED)) {
                frozen.remove(id);
                return null;
            }
        }
        return null;
    }

    public boolean isFrozen(UUID id) {
        return get(id) != null;
    }

    public void freeze(UUID id, String reason, String staff) {
        freeze(id, reason, staff, "");
    }

    public void freeze(UUID id, String reason, String staff, String staffUuid) {
        FreezeRecord record = new FreezeRecord(reason, staff, staffUuid);
        if (!StoreCas.updateDurably(store, id.toString(), ignored -> record.encode())) {
            throw new IllegalStateException("could not durably persist the freeze");
        }
        frozen.put(id, record);
        // A freeze has no clock, so it takes the widest tier there is: it lasts until a
        // moderator ends it, which is exactly what "no time left to count" means here.
        showOnDiscord(id, true);
    }

    public boolean unfreeze(UUID id) {
        return unfreezeResult(id) == RemovalResult.REMOVED;
    }

    public RemovalResult unfreezeResult(UUID id) {
        if (get(id) == null) return RemovalResult.NOT_FOUND;
        RemovalResult result = consume(id) != null
                ? RemovalResult.REMOVED : RemovalResult.STORAGE_FAILED;
        if (result == RemovalResult.REMOVED) showOnDiscord(id, false);
        return result;
    }

    /**
     * Mirrors the freeze onto Discord as a role, after it is durably stored here.
     *
     * <p>Best-effort on purpose: a Discord outage must never undo a freeze that already
     * applied, so a failure here is swallowed and the freeze stands.
     */
    private void showOnDiscord(UUID id, boolean frozenNow) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        var roles = plugin == null ? null : plugin.getPunishmentRoles();
        if (roles == null) return;
        try {
            if (frozenNow) {
                roles.mark(id, dev.chatalyst.discord.DiscordPunishmentRoles.State.FREEZE, -1L);
            } else {
                roles.clear(id, dev.chatalyst.discord.DiscordPunishmentRoles.State.FREEZE);
            }
        } catch (RuntimeException ignored) {
            // The freeze stands either way; the role is only how it is shown.
        }
    }

    /**
     * Atomically claims and removes a freeze. Exactly one backend can turn a disconnect
     * into a punishment; concurrent /unfreeze or network listeners safely lose the claim.
     */
    public FreezeRecord consume(UUID id) {
        String key = id.toString();
        for (int attempt = 0; attempt < 5; attempt++) {
            String raw = store.get(key);
            if (raw == null || CONSUMED.equals(raw)) {
                frozen.remove(id);
                return null;
            }
            FreezeRecord record = FreezeRecord.decode(raw);
            if (record == null) {
                frozen.remove(id);
                if (!store.compareAndPutDurably(key, raw, CONSUMED)) continue;
                return null;
            }
            if (!store.compareAndPutDurably(key, raw, CONSUMED)) continue;
            frozen.remove(id);
            // Keep the tombstone until the next load() cleanup. Removing it here would race
            // with a new freeze and could delete that newer record after it was written.
            return record;
        }
        return null;
    }

    /**
     * Vista en memoria de los congelados conocidos por este backend, sin tocar storage.
     * Para decisiones por-mensaje (p. ej. enrutar el chat del staff); los candidatos deben
     * confirmarse con {@link #get(UUID)} antes de actuar.
     */
    public Map<UUID, FreezeRecord> active() {
        return Map.copyOf(frozen);
    }

    public Map<UUID, FreezeRecord> snapshot() {
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                get(UUID.fromString(entry.getKey()));
            } catch (RuntimeException ignored) {
                store.compareAndRemoveDurably(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(frozen);
    }

    public void shutdown() {
        store.flushNow();
    }
}
