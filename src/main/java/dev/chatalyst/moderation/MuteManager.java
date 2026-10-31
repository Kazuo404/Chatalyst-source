package dev.chatalyst.moderation;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.hook.VoiceIntegration;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Silenciamientos de jugadores (mute global). Estado en memoria + persistencia via
 * {@link Store} (YAML o base de datos). Cada mute se serializa como "until|reason|by".
 */
public final class MuteManager {

    public enum RemovalResult { REMOVED, NOT_FOUND, STORAGE_FAILED }

    private static final String SEP = ""; // separador improbable en texto normal

    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Store store;
    /** Silencios por CONEXION (/ipmute): clave = hash SHA-256 de la IP, jamas la IP. */
    private final Store ipStore;
    private VoiceIntegration voiceIntegration;

    public MuteManager(StorageManager storage) {
        this(storage.store("mutes"), storage.store("mutes_ip"));
    }

    /** Para tests y stores a medida: mismo comportamiento con un Store directo. */
    public MuteManager(Store store) {
        this(store, null);
    }

    /** Tests de /ipmute: mutes de cuenta y de conexion en stores separados. */
    public MuteManager(Store store, Store ipStore) {
        this.store = store;
        this.ipStore = ipStore;
    }

    // ---- Silencio por conexion (/ipmute) ---------------------------------------------------

    /**
     * Un silencio puesto a una CONEXION: lo cumplen todas las cuentas que entraron desde ella
     * y las que entren despues. Mismo formato que un mute de cuenta.
     */
    public record IpMute(long until, String reason, String by) {

        public boolean isPermanent() {
            return until == TimeUtil.PERMANENT;
        }

        public boolean expired(long now) {
            return !isPermanent() && now >= until;
        }

        /** Lo que le queda, en milisegundos, para aplicarlo a una cuenta que entra ahora. */
        public long remainingFrom(long now) {
            return isPermanent() ? TimeUtil.PERMANENT : Math.max(1L, until - now);
        }
    }

    /** Guarda el silencio de la conexion. False si el almacenamiento no lo acepto. */
    public boolean ipMute(String hash, long durationMillis, String reason, String by) {
        if (ipStore == null || hash == null || hash.isBlank()) return false;
        Mute shape = new Mute(TimeUtil.deadline(durationMillis), reason, by);
        return ipStore.putDurably(hash, encode(shape));
    }

    /** Silencio activo de la conexion, o null. Los caducados se retiran al leerlos. */
    public IpMute ipMuteOf(String hash) {
        if (ipStore == null || hash == null) return null;
        String raw = ipStore.get(hash);
        Mute mute = decode(raw);
        if (mute == null) return null;
        if (mute.isExpired()) {
            ipStore.compareAndRemoveDurably(hash, raw);
            return null;
        }
        return new IpMute(mute.until, mute.reason, mute.by);
    }

    /** Retira el silencio de la conexion; devuelve el que habia, o null si no habia ninguno. */
    public IpMute removeIpMute(String hash) {
        IpMute current = ipMuteOf(hash);
        if (current == null) return null;
        String raw = ipStore.get(hash);
        return raw != null && ipStore.compareAndRemoveDurably(hash, raw) ? current : null;
    }

    /** Todos los silencios de conexion activos (hash -> silencio). */
    public Map<String, IpMute> ipMutes() {
        Map<String, IpMute> out = new java.util.LinkedHashMap<>();
        if (ipStore == null) return out;
        for (Map.Entry<String, String> e : ipStore.all().entrySet()) {
            Mute mute = decode(e.getValue());
            if (mute != null && !mute.isExpired()) {
                out.put(e.getKey(), new IpMute(mute.until, mute.reason, mute.by));
            }
        }
        return out;
    }

    public void load() {
        mutes.clear();
        boolean expired = false;
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            try {
                UUID id = UUID.fromString(e.getKey());
                Mute mute = decode(e.getValue());
                if (mute == null) continue;
                if (mute.isExpired()) {
                    // Un solo guardado al final: uno por caducado reescribe el archivo N veces.
                    expired |= store.compareAndRemove(e.getKey(), e.getValue());
                    continue;
                }
                mutes.put(id, mute);
            } catch (RuntimeException ignore) {
                // clave/valor corrupto: se ignora
            }
        }
        if (expired) store.flushNow();
    }

    public void setVoiceIntegration(VoiceIntegration voiceIntegration) {
        this.voiceIntegration = voiceIntegration;
    }

    /** Mute activo del jugador, o null si no esta muteado o expiro. */
    public Mute getMute(UUID id) {
        String key = id.toString();
        String observed = store.get(key);
        Mute mute = decode(observed);
        if (mute == null) return null;
        mutes.put(id, mute);
        if (mute.isExpired()) {
            mutes.remove(id, mute);
            if (observed != null && store.compareAndRemoveDurably(key, observed)) {
                // Una sancion local nueva puede entrar justo despues del CAS. No desmutear
                // la voz si el cache/store ya contienen su reemplazo.
                if (voiceIntegration != null && mutes.get(id) == null && store.get(key) == null) {
                    voiceIntegration.unmute(Bukkit.getOfflinePlayer(id));
                }
                return null;
            }
            // El CAS fallo: compareAndRemove refresco el cache con una sancion concurrente.
            Mute replacement = decode(store.get(key));
            if (replacement != null && !replacement.isExpired()) {
                mutes.put(id, replacement);
                return replacement;
            }
            return null;
        }
        return mute;
    }

    public boolean isMuted(UUID id) {
        return getMute(id) != null;
    }

    /** Instantanea de los mutes activos (lista del staff). Lee el store autoritativo. */
    public Map<UUID, Mute> snapshot() {
        Map<UUID, Mute> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            try {
                Mute mute = decode(e.getValue());
                if (mute != null && !mute.isExpired()) {
                    out.put(UUID.fromString(e.getKey()), mute);
                }
            } catch (RuntimeException ignore) {
                // clave/valor corrupto: se ignora, igual que en load()
            }
        }
        return out;
    }

    public boolean mute(OfflinePlayer target, long durationMillis, String reason, String by) {
        return mute(target, durationMillis, reason, by, false);
    }

    public boolean mute(OfflinePlayer target, long durationMillis, String reason, String by, boolean soft) {
        if (!mute(target.getUniqueId(), durationMillis, reason, by, soft)) return false;
        // El soft mute no se propaga a la voz: silenciar la voz delataria el shadow mute.
        if (voiceIntegration != null && !soft) {
            voiceIntegration.mute(target, durationMillis, reason, by);
        }
        return true;
    }

    /** Nucleo por UUID (sin integracion de voz), tambien usable desde tests. */
    public boolean mute(UUID id, long durationMillis, String reason, String by, boolean soft) {
        return muteUntil(id, TimeUtil.deadline(durationMillis), reason, by, soft);
    }

    /**
     * Mute que acaba en un instante FIJO ({@code until}, o {@link TimeUtil#PERMANENT}). Lo usa
     * /ipmute: cada cuenta de la conexion termina exactamente cuando el silencio de la
     * conexion. Con una duracion relativa ("lo que le queda") el final salia unos ms distinto,
     * /unipmute ya no reconocia esos mutes como suyos y cada reentrada volvia a silenciar.
     *
     * <p>Sin E/S de Bukkit: se puede llamar fuera del hilo del servidor (la voz se despacha
     * sola al hilo global).
     */
    public boolean muteUntil(OfflinePlayer target, long until, String reason, String by) {
        if (!muteUntil(target.getUniqueId(), until, reason, by, false)) return false;
        if (voiceIntegration != null) {
            long remaining = until == TimeUtil.PERMANENT ? TimeUtil.PERMANENT
                    : Math.max(1L, until - System.currentTimeMillis());
            voiceIntegration.mute(target, remaining, reason, by);
        }
        return true;
    }

    /** Nucleo de {@link #muteUntil(OfflinePlayer, long, String, String)}, sin voz. */
    public boolean muteUntil(UUID id, long until, String reason, String by, boolean soft) {
        Mute mute = new Mute(until, reason, by, soft);
        if (!store.putDurably(id.toString(), encode(mute))) {
            Mute authoritative = decode(store.get(id.toString()));
            if (authoritative == null) mutes.remove(id);
            else mutes.put(id, authoritative);
            return false;
        }
        mutes.put(id, mute);
        showOnDiscord(id, until);
        return true;
    }

    /**
     * Mirrors the mute onto Discord as a role, once it is durably stored here.
     *
     * <p>Best-effort and deliberately last: a Discord outage must never make a mute that is
     * already saved and enforced report failure.
     */
    private void showOnDiscord(UUID id, long until) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        var roles = plugin == null ? null : plugin.getPunishmentRoles();
        if (roles == null) return;
        try {
            roles.mark(id, dev.chatalyst.discord.DiscordPunishmentRoles.State.MUTE,
                    until == TimeUtil.PERMANENT ? -1L : until - System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // The mute stands either way; the role is only how it is shown.
        }
    }

    private void hideOnDiscord(UUID id) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        var roles = plugin == null ? null : plugin.getPunishmentRoles();
        if (roles == null) return;
        try {
            roles.clear(id, dev.chatalyst.discord.DiscordPunishmentRoles.State.MUTE);
        } catch (RuntimeException ignored) {
        }
    }

    /** True si el jugador tiene un shadow mute activo (ve sus mensajes, nadie mas). */
    public boolean isSoftMuted(UUID id) {
        Mute mute = getMute(id);
        return mute != null && mute.soft;
    }

    /**
     * Si el jugador tiene un mute normal activo, le avisa con el mensaje estandar y devuelve
     * true. Los soft mutes devuelven false: el shadow mute nunca se delata al muteado.
     */
    public boolean notifyIfMuted(org.bukkit.entity.Player player) {
        if (player.isOp() && dev.chatalyst.staff.StaffAccess.exemptsOps()) return false;
        Mute mute = getMute(player.getUniqueId());
        if (mute == null || mute.soft) return false;
        // Texto plano del motivo, escapado: tras quitarle el formato puede quedar un "<...>"
        // literal que, pegado en el mensaje, se volveria a leer como etiqueta.
        String reason = dev.chatalyst.util.MM.escape(shownReason(player, mute.reason));
        player.sendMessage(mute.isPermanent()
                ? dev.chatalyst.lang.Lang.get(player, "mute.silenced-perm", "reason", reason)
                : dev.chatalyst.lang.Lang.get(player, "mute.silenced", "duration", mute.remaining(), "reason", reason));
        return true;
    }

    /** Motivo legible en el idioma del muteado (resuelve el token default y quita formato). */
    private static String shownReason(org.bukkit.entity.Player player, String stored) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin != null && plugin.getStaffModeration() != null
                && plugin.getStaffModeration().formatter() != null) {
            return plugin.getStaffModeration().formatter().plainReason(player, stored);
        }
        return stored;
    }

    public boolean unmute(UUID id) {
        return unmuteResult(id) == RemovalResult.REMOVED;
    }

    public RemovalResult unmuteResult(UUID id) {
        String key = id.toString();
        String observed = store.get(key);
        if (observed == null) {
            boolean hadCached = mutes.remove(id) != null;
            if (hadCached && voiceIntegration != null) {
                voiceIntegration.unmute(Bukkit.getOfflinePlayer(id));
            }
            return hadCached ? RemovalResult.REMOVED : RemovalResult.NOT_FOUND;
        }
        if (!store.compareAndRemoveDurably(key, observed)) {
            Mute replacement = decode(store.get(key));
            if (replacement != null) mutes.put(id, replacement);
            return RemovalResult.STORAGE_FAILED;
        }
        // Solo retirar del cache el mismo valor reclamado; mute() concurrente queda intacto.
        mutes.computeIfPresent(id,
                (ignored, current) -> encode(current).equals(observed) ? null : current);
        if (voiceIntegration != null && mutes.get(id) == null && store.get(key) == null) {
            voiceIntegration.unmute(Bukkit.getOfflinePlayer(id));
        }
        hideOnDiscord(id);
        return RemovalResult.REMOVED;
    }

    public void shutdown() {
        store.flushNow();
        if (ipStore != null) ipStore.flushNow();
    }

    private static String encode(Mute mute) {
        return mute.until + SEP + mute.reason + SEP + mute.by + (mute.soft ? SEP + "soft" : "");
    }

    private static Mute decode(String raw) {
        if (raw == null) return null;
        try {
            String[] parts = raw.split(SEP, 4);
            long until = Long.parseLong(parts[0]);
            return new Mute(until,
                    parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "",
                    parts.length > 3 && "soft".equals(parts[3]));
        } catch (RuntimeException corrupt) {
            return null;
        }
    }
}
