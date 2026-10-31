package dev.chatalyst.staff;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.TimeUtil;
import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Profile bans persisted by Chatalyst and mirrored to Paper's native profile ban list. */
public final class BanManager implements Listener {

    public enum RemovalResult { REMOVED, NOT_FOUND, STORAGE_FAILED, NATIVE_FAILED }

    /**
     * Told after a ban really landed, so a ban can reach further than Minecraft.
     *
     * <p>A listener, not a direct call into Discord: this class must keep working — and keep
     * being testable — on a server with no bot at all.
     */
    public interface Observer {

        void onBanned(UUID target, long until, String reason, String staff);

        void onUnbanned(UUID target);
    }

    private final Store store;
    /**
     * Numero y fecha de cada ban ("#1532", "puesto el 02/10/2026"), en una tabla APARTE del
     * ban. El registro del ban no cambia de formato a proposito: Lobby y Survival leen la
     * misma tabla, y un servidor con un jar anterior trataria un ban con un campo de mas como
     * corrupto — y lo BORRARIA al arrancar. Sin numero, el ban sigue valiendo igual.
     */
    private final Store stamps;
    private final dev.chatalyst.staff.tools.PunishmentIds ids;
    private final ModerationFormatter formatter;
    private final Map<UUID, BanRecord> bans = new ConcurrentHashMap<>();
    private final java.util.List<Observer> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void observe(Observer observer) {
        if (observer != null) observers.add(observer);
    }

    private void announceBan(UUID target, long until, String reason, String staff) {
        for (Observer observer : observers) {
            try {
                observer.onBanned(target, until, reason, staff);
            } catch (RuntimeException failure) {
                // A mirror that breaks must never undo a ban that already applied here.
                Bukkit.getLogger().warning("A ban observer failed: " + failure);
            }
        }
    }

    private void announceUnban(UUID target) {
        for (Observer observer : observers) {
            try {
                observer.onUnbanned(target);
            } catch (RuntimeException failure) {
                Bukkit.getLogger().warning("An unban observer failed: " + failure);
            }
        }
    }

    public BanManager(StorageManager storage, ModerationFormatter formatter) {
        this(storage.store("staff_bans"), storage.store("staff_ban_ids"),
                new dev.chatalyst.staff.tools.PunishmentIds(
                        storage.store("staff_punishment_ids")), formatter);
    }

    BanManager(Store store, ModerationFormatter formatter) {
        this(store, null, null, formatter);
    }

    BanManager(Store store, Store stamps, dev.chatalyst.staff.tools.PunishmentIds ids,
               ModerationFormatter formatter) {
        this.store = store;
        this.stamps = stamps;
        this.ids = ids;
        this.formatter = formatter;
        // Un ban o /unban hecho en otro servidor de la red tambien debe verse en el
        // autocompletado de aqui.
        store.addInvalidationListener(this::reindex);
    }

    /**
     * Copia decodificada de las filas del Store, para el autocompletado de /unban: antes
     * cada tecla copiaba el Store entero y decodificaba todos los bans en el hilo principal.
     * Se rehace en {@link #load()} y se actualiza tras cada escritura de esta clase y cada
     * invalidacion de red; los caducados se filtran al leer, asi que no hace falta barrerlos.
     */
    private final Map<UUID, BanRecord> nameIndex = new ConcurrentHashMap<>();

    /** Vuelve a leer del cache del Store (sin E/S) las filas indicadas. */
    private void reindex(java.util.Collection<String> keys) {
        for (String key : keys) reindex(key);
    }

    private void reindex(String key) {
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException notAPlayer) {
            return;
        }
        BanRecord ban = BanRecord.decode(store.get(key));
        if (ban == null) nameIndex.remove(id);
        else nameIndex.put(id, ban);
    }

    /** Numero de castigo y momento en que se puso un ban; {@code id} 0 = sin numero. */
    public record BanStamp(long id, long since) {

        String encode() {
            return id + ":" + since;
        }

        static BanStamp decode(String raw) {
            if (raw == null) return null;
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) return null;
            try {
                return new BanStamp(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (NumberFormatException corrupt) {
                return null;
            }
        }
    }

    /** Numero y fecha del ban actual de {@code id}, o null si no se registraron. */
    public BanStamp stamp(UUID id) {
        return stamps == null || id == null ? null : BanStamp.decode(stamps.get(id.toString()));
    }

    // Un ban corrupto es fail-open (el jugador entra): sin este log, nadie sabria jamas que
    // una sancion dejo de aplicar. Una sola vez por clave para no inundar la consola.
    private final java.util.Set<String> warnedCorrupt = ConcurrentHashMap.newKeySet();

    private void warnCorrupt(String key, String raw) {
        if (raw != null && !raw.isEmpty() && warnedCorrupt.add(key)) {
            java.util.logging.Logger.getLogger("Chatalyst").severe(
                    "Corrupt ban record for '" + key + "' no longer enforces (fail-open)."
                            + " Re-ban the player if needed. Raw value: " + raw);
        }
    }

    public void load() {
        bans.clear();
        long now = System.currentTimeMillis();
        boolean cleaned = false;
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                BanRecord ban = BanRecord.decode(entry.getValue());
                if (ban == null || ban.expired(now)) {
                    if (ban == null) warnCorrupt(entry.getKey(), entry.getValue());
                    // Un solo guardado al final: uno por caducado reescribe el archivo N veces.
                    if (store.compareAndRemove(entry.getKey(), entry.getValue())) {
                        cleaned = true;
                    } else {
                        BanRecord replacement = BanRecord.decode(store.get(entry.getKey()));
                        if (replacement != null && !replacement.expired(now)) bans.put(id, replacement);
                    }
                    continue;
                }
                bans.put(id, ban);
            } catch (RuntimeException ex) {
                warnCorrupt(entry.getKey(), entry.getValue());
                cleaned |= store.compareAndRemove(entry.getKey(), entry.getValue());
            }
        }
        if (cleaned) store.flushNow();
        nameIndex.clear();
        reindex(store.all().keySet());
    }

    public BanRecord get(UUID id) {
        String raw = store.get(id.toString());
        BanRecord ban = BanRecord.decode(raw);
        if (ban == null) {
            warnCorrupt(id.toString(), raw);
            bans.remove(id);
            return null;
        }
        bans.put(id, ban);
        if (!ban.expired(System.currentTimeMillis())) return ban;
        bans.remove(id, ban);
        boolean removed = store.compareAndRemoveDurably(id.toString(), raw);
        reindex(id.toString());
        if (!removed) {
            BanRecord replacement = BanRecord.decode(store.get(id.toString()));
            if (replacement != null && !replacement.expired(System.currentTimeMillis())) {
                bans.put(id, replacement);
                return replacement;
            }
        }
        return null;
    }

    /**
     * Bans a player, then tells whoever is listening — deliberately in that order, and
     * deliberately with the lock already released.
     *
     * <p>Observers mirror the ban onto other systems: they read the account link (which may be
     * a database round trip) and hand work to Discord. Doing that while still holding this
     * monitor would keep the ban lock for the length of an I/O call, and every login checks
     * this same lock — so one slow ban would stall people joining the server. The ban is fully
     * persisted and enforced before the first observer hears about it, so nothing is lost by
     * announcing it a moment later, outside.
     */
    public long ban(OfflinePlayer target, long duration, String reason, String staff) {
        long until = TimeUtil.deadline(duration);
        long number = ids == null ? dev.chatalyst.staff.tools.PunishmentIds.NONE : ids.next();
        String plainReason = formatter.plainReason(reason);
        synchronized (this) {
            String name = target.getName() == null
                    ? target.getUniqueId().toString() : target.getName();
            BanRecord record = new BanRecord(name, until, reason, staff);
            String key = target.getUniqueId().toString();
            if (!StoreCas.updateDurably(store, key, ignored -> record.encode())) {
                reindex(key);
                throw new IllegalStateException("could not durably persist the ban");
            }
            bans.put(target.getUniqueId(), record);
            reindex(key);
            // Despues del ban, nunca antes: si esto falla el ban vale igual, solo sin numero.
            // Y se escribe SIEMPRE (tambien sin numero) para no enseñar el de un ban anterior.
            if (stamps != null) {
                stamps.put(key, new BanStamp(number, System.currentTimeMillis()).encode());
            }

            ProfileBanList nativeBans = Bukkit.getBanList(BanListType.PROFILE);
            PlayerProfile profile = target.getPlayerProfile();
            // El source marca la copia como nuestra: onLogin solo retira copias huerfanas
            // de Chatalyst, nunca un ban nativo de la consola u otro plugin.
            String source = nativeSource(staff);
            if (duration == TimeUtil.PERMANENT) {
                nativeBans.addBan(profile, plainReason, (Date) null, source);
            } else {
                nativeBans.addBan(profile, plainReason,
                        Date.from(Instant.ofEpochMilli(until)), source);
            }
        }
        announceBan(target.getUniqueId(), until, plainReason, staff);
        return number;
    }

    public boolean unban(OfflinePlayer target) {
        return unbanResult(target) == RemovalResult.REMOVED;
    }

    /** Same shape as {@link #ban}: the removal holds the lock, the announcement does not. */
    public RemovalResult unbanResult(OfflinePlayer target) {
        RemovalResult result = removeLocked(target);
        if (result == RemovalResult.REMOVED) {
            if (stamps != null) stamps.remove(target.getUniqueId().toString());
            announceUnban(target.getUniqueId());
        }
        return result;
    }

    private synchronized RemovalResult removeLocked(OfflinePlayer target) {
        String key = target.getUniqueId().toString();
        try {
            return removeLockedUnindexed(target);
        } finally {
            // Cualquier salida (quitado, fallo, restaurado) deja el indice como el Store.
            reindex(key);
        }
    }

    private RemovalResult removeLockedUnindexed(OfflinePlayer target) {
        UUID id = target.getUniqueId();
        String key = id.toString();
        String observed = store.get(key);
        boolean existed = observed != null || bans.containsKey(id);
        if (observed != null && !store.compareAndRemoveDurably(key, observed)) {
            BanRecord replacement = BanRecord.decode(store.get(key));
            if (replacement != null) bans.put(id, replacement);
            return RemovalResult.STORAGE_FAILED;
        }
        bans.computeIfPresent(id,
                (ignored, current) -> observed == null || current.encode().equals(observed)
                        ? null : current);
        try {
            ProfileBanList nativeBans = Bukkit.getBanList(BanListType.PROFILE);
            PlayerProfile profile = target.getPlayerProfile();
            // No perdonar el ban nativo si un ban() local ya reemplazo el valor observado.
            if (store.get(key) == null && bans.get(id) == null && nativeBans.isBanned(profile)) {
                nativeBans.pardon(profile);
                existed = true;
            }
        } catch (RuntimeException nativeFailure) {
            // Si retiramos primero el registro durable pero Paper no pudo retirar su espejo,
            // restaura solo sobre ausencia: nunca pisa un ban concurrente mas nuevo.
            if (observed != null && store.compareAndPutDurably(key, null, observed)) {
                BanRecord restored = BanRecord.decode(observed);
                if (restored != null) bans.put(id, restored);
            }
            return RemovalResult.NATIVE_FAILED;
        }
        return existed ? RemovalResult.REMOVED : RemovalResult.NOT_FOUND;
    }

    /**
     * Nombres de los baneados vigentes, SOLO leyendo la cache del Store: para el autocompletado
     * de /unban, que corre en el hilo principal a cada tecla. {@link #snapshot()} retira los
     * caducados con escrituras durables (en MySQL, un viaje a la base por cada uno), asi que
     * llamarlo al autocompletar podia congelar el servidor mientras el staff escribia.
     */
    public java.util.List<String> cachedBannedNames() {
        return activeNames(nameIndex.values(), System.currentTimeMillis());
    }

    /** Nombres vigentes de un conjunto ya decodificado (los caducados se saltan). */
    static java.util.List<String> activeNames(java.util.Collection<BanRecord> bans, long now) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (BanRecord ban : bans) {
            if (ban == null || ban.expired(now)) continue;
            String name = ban.playerName();
            if (name != null && !name.isBlank()) out.add(name);
        }
        return out;
    }

    static java.util.List<String> activeNames(Map<String, String> raw, long now) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String value : raw.values()) {
            BanRecord ban = BanRecord.decode(value);
            if (ban == null || ban.expired(now)) continue;
            String name = ban.playerName();
            if (name != null && !name.isBlank()) out.add(name);
        }
        return out;
    }

    public Map<UUID, BanRecord> snapshot() {
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                get(UUID.fromString(entry.getKey()));
            } catch (RuntimeException ignored) {
                store.compareAndRemoveDurably(entry.getKey(), entry.getValue());
                reindex(entry.getKey());
            }
        }
        for (UUID id : java.util.List.copyOf(bans.keySet())) get(id);
        return Map.copyOf(bans);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        // Solo con Exempt_Ops: si el servidor permite moderar a los OP, su ban tambien vale.
        boolean exemptOp = player.isOp() && StaffAccess.exemptsOps();
        BanRecord ban = get(player.getUniqueId());
        if (event.getResult() == PlayerLoginEvent.Result.KICK_BANNED) {
            reconcileNativeBan(event, player, ban, exemptOp);
        }
        // El OP exento entra sin borrar la fila: el ban es de toda la red y en los demas
        // servidores (o si deja de ser OP) debe seguir valiendo.
        if (ban == null || exemptOp) return;
        BanStamp stamp = stamp(player.getUniqueId());
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                formatter.screen(player, ModerationFormatter.Screen.BAN, player.getName(),
                        ban.staff(), ban.until(), ban.reason(),
                        stamp == null ? 0L : stamp.id(), stamp == null ? 0L : stamp.since()));
    }

    /** Prefijo del source de las copias nativas que escribe Chatalyst. */
    static final String NATIVE_SOURCE_PREFIX = "Chatalyst/";

    static String nativeSource(String staff) {
        return NATIVE_SOURCE_PREFIX + (staff == null ? "" : staff);
    }

    static boolean isOwnNativeSource(String source) {
        return source != null && source.startsWith(NATIVE_SOURCE_PREFIX);
    }

    /** Que hacer con la copia nativa cuando Paper ya nego la entrada por ella. */
    enum NativeBanAction { NONE, ALLOW, PARDON, PARDON_AND_ALLOW }

    /**
     * Decision pura. El Store compartido es la unica fuente de verdad: la lista nativa solo
     * la escribe el servidor que puso el ban y un /unban desde otro no la limpia.
     *
     * @param nativeSource source de la entrada nativa del perfil, o null si no hay
     * @param activeBan    ban activo en el Store, o null
     * @param nativeIpBanned la IP tambien esta en la lista nativa de IPs (eso no es nuestro)
     */
    static NativeBanAction nativeBanAction(String nativeSource, BanRecord activeBan,
                                           boolean exemptOp, boolean nativeIpBanned) {
        if (nativeSource == null) return NativeBanAction.NONE;
        if (activeBan != null) {
            // La copia sigue siendo valida; solo el OP exento pasa. Una copia anterior a la
            // marca llevaba de source el nombre del staff del propio ban.
            boolean ours = isOwnNativeSource(nativeSource)
                    || nativeSource.equals(activeBan.staff());
            return exemptOp && ours && !nativeIpBanned ? NativeBanAction.ALLOW
                    : NativeBanAction.NONE;
        }
        // Sin ban en el Store, solo se retira una copia NUESTRA: un ban nativo de la consola
        // vanilla u otro plugin no se toca jamas.
        if (!isOwnNativeSource(nativeSource)) return NativeBanAction.NONE;
        return nativeIpBanned ? NativeBanAction.PARDON : NativeBanAction.PARDON_AND_ALLOW;
    }

    private void reconcileNativeBan(PlayerLoginEvent event, Player player, BanRecord ban,
                                    boolean exemptOp) {
        try {
            ProfileBanList nativeBans = Bukkit.getBanList(BanListType.PROFILE);
            PlayerProfile profile = player.getPlayerProfile();
            org.bukkit.BanEntry<PlayerProfile> entry = nativeBans.getBanEntry(profile);
            boolean ipBanned = event.getAddress() != null
                    && Bukkit.getBanList(BanListType.IP).isBanned(event.getAddress());
            switch (nativeBanAction(entry == null ? null : entry.getSource(), ban, exemptOp,
                    ipBanned)) {
                case NONE -> {
                }
                case ALLOW -> event.allow();
                case PARDON -> nativeBans.pardon(profile);
                case PARDON_AND_ALLOW -> {
                    nativeBans.pardon(profile);
                    event.allow();
                }
            }
        } catch (RuntimeException failure) {
            // Si Paper falla aqui se queda su decision (fuera): mejor que dejar pasar a ciegas.
            Bukkit.getLogger().warning("Could not reconcile the native ban of "
                    + player.getName() + ": " + failure);
        }
    }

    /**
     * Expulsion entre servidores. Sin Redis, el ban puesto en otro backend llega por el
     * sondeo de Shared_Refresh_Seconds: el Store avisa de las filas que cambiaron y solo se
     * mira a esos jugadores, nunca a todos. YAML no avisa (un solo servidor).
     */
    public void enforceRemoteBans(org.bukkit.plugin.java.JavaPlugin plugin) {
        store.addInvalidationListener(ids -> kickRemotelyBanned(plugin, ids));
    }

    private void kickRemotelyBanned(org.bukkit.plugin.java.JavaPlugin plugin,
                                    java.util.Collection<String> ids) {
        // Hilo del sondeo (async): aqui si puede tocarse la base (get() limpia expirados).
        java.util.List<UUID> banned = new java.util.ArrayList<>();
        for (String raw : ids) {
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException notAPlayer) {
                continue;
            }
            if (get(id) != null) banned.add(id);
        }
        if (banned.isEmpty()) return;
        FoliaTasks.global(plugin, () -> {
            for (UUID id : banned) {
                Player online = plugin.getServer().getPlayer(id);
                if (online == null) continue;
                FoliaTasks.entity(plugin, online, player -> {
                    // Se relee del cache (sin E/S en el hilo de la region): un /unban que
                    // llego mientras tanto gana.
                    BanRecord ban = BanRecord.decode(store.get(id.toString()));
                    boolean exemptOp = player.isOp() && StaffAccess.exemptsOps();
                    if (!shouldKickForRemoteBan(ban, System.currentTimeMillis(), exemptOp)) {
                        return;
                    }
                    BanStamp stamp = stamp(id);
                    player.kick(formatter.screen(player, ModerationFormatter.Screen.BAN,
                            player.getName(), ban.staff(), ban.until(), ban.reason(),
                            stamp == null ? 0L : stamp.id(),
                            stamp == null ? 0L : stamp.since()));
                });
            }
        });
    }

    /** Las mismas exenciones que {@link #onLogin}: solo el OP con Exempt_Ops se libra. */
    static boolean shouldKickForRemoteBan(BanRecord ban, long now, boolean exemptOp) {
        return ban != null && !ban.expired(now) && !exemptOp;
    }

    public void shutdown() {
        store.flushNow();
        if (stamps != null) stamps.flushNow();
    }
}
