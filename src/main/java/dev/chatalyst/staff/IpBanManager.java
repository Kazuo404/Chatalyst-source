package dev.chatalyst.staff;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

/**
 * Baneo por conexion, el complemento anti-alts del ban de cuenta. Por privacidad NUNCA se
 * guarda la IP: solo su SHA-256 (el mismo hash que usa [[AltTracker]]), suficiente para
 * reconocer la conexion al volver. Al detectarla se bloquea el login con la misma pantalla
 * fancy del ban y, con {@code Ip_Ban.Ban_New_Accounts} activo, la cuenta nueva queda
 * baneada tambien de forma durable: cambiar de IP despues ya no la salva. Cada intento
 * avisa al staff y queda en consola.
 *
 * <p>Nota honesta: en Minecraft Java no existe el "hardware ban" (el cliente no envia
 * ningun identificador de hardware); esto es lo maximo que un servidor puede hacer.
 */
public final class IpBanManager implements Listener {

    private static final String FIELD = "\u001f";

    /**
     * Un baneo de conexion y la CUENTA por la que se puso.
     *
     * <p>{@code account} es quien lo provoco; {@code playerName} es solo como se llamaba
     * entonces, para poder enseñar algo cuando el UUID no resuelve. La diferencia importa: si
     * se compara por nombre, el dia que ese jugador se cambia el nombre deja de reconocerse a
     * si mismo y entra por la puerta de los alts — le cae un ban nuevo "por alt de si mismo" y
     * el staff recibe una alerta que no es verdad.
     */
    public record IpBan(java.util.UUID account, String playerName, long until,
                        String reason, String staff) {

        boolean expired(long now) {
            return until != TimeUtil.PERMANENT && until <= now;
        }

        /** Si esta conexion es la del propio baneado, se llame hoy como se llame. */
        boolean isTheBannedAccount(org.bukkit.entity.Player player) {
            if (player == null) return false;
            // Registros anteriores al UUID solo pueden compararse por nombre; es lo que hay,
            // y ese jugador deja de ser confundible con un alt en cuanto se rebanea.
            if (account == null) {
                return playerName != null && player.getName().equalsIgnoreCase(playerName);
            }
            return account.equals(player.getUniqueId());
        }

        String encode() {
            return until + FIELD + playerName + FIELD + staff + FIELD + reason
                    + FIELD + (account == null ? "" : account);
        }

        static IpBan decode(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            String[] fields = raw.split(FIELD, 5);
            if (fields.length < 4) return null;
            try {
                // El 5o campo no existia antes: su ausencia es un registro legacy, no un error.
                java.util.UUID account = fields.length < 5 ? null : parseUuid(fields[4]);
                return new IpBan(account, fields[1], Long.parseLong(fields[0]),
                        fields[3], fields[2]);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static java.util.UUID parseUuid(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return java.util.UUID.fromString(raw.trim());
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    private final StaffModerationModule module;
    private final Store store;

    IpBanManager(StaffModerationModule module, StorageManager storage) {
        this.module = module;
        this.store = storage.store("staff_ipbans");
    }

    /** Registra el baneo de la conexion; el ban de cuenta lo aplica el comando aparte. */
    public boolean ban(String ipHash, java.util.UUID account, String playerName, long duration,
                       String reason, String staff) {
        long until = TimeUtil.deadline(duration);
        return store.putDurably(ipHash, new IpBan(account, clean(playerName), until,
                clean(reason), clean(staff)).encode());
    }

    public boolean unban(String ipHash) {
        return store.removeDurably(ipHash);
    }

    /**
     * Retira los bans de conexion que provoco esta cuenta. Desbanear la cuenta y dejar su
     * conexion baneada era un desbaneo a medias: la pantalla de ban seguia saliendo, y el
     * guardia de alts la volvia a banear en el siguiente login. Devuelve cuantos retiro.
     */
    public int unbanAccount(java.util.UUID account) {
        if (account == null) return 0;
        int removed = 0;
        for (var entry : java.util.Map.copyOf(store.all()).entrySet()) {
            IpBan ban = IpBan.decode(entry.getValue());
            if (ban != null && account.equals(ban.account()) && store.removeDurably(entry.getKey())) {
                removed++;
            }
        }
        return removed;
    }

    /** Baneo activo de esa conexion, o null; los expirados se retiran solos. */
    public IpBan get(String ipHash) {
        if (ipHash == null) return null;
        String raw = store.get(ipHash);
        IpBan ban = IpBan.decode(raw);
        if (ban == null) return null;
        if (!ban.expired(System.currentTimeMillis())) return ban;
        store.removeDurably(ipHash);
        return null;
    }

    public boolean has(String ipHash) {
        return get(ipHash) != null;
    }

    /**
     * El login corre con el jugador ya resuelto (mismo patron que BanManager), asi la
     * pantalla sale localizada. Prioridad LOWEST: si ademas procede el auto-ban de la
     * cuenta, BanManager (HIGHEST) la ve ya baneada en este mismo evento.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (event.getAddress() == null) return;
        String hash = AltTracker.hashIp(event.getAddress().getHostAddress());
        IpBan ban = get(hash);
        if (ban == null) return;
        boolean knownAccount = ban.isTheBannedAccount(player);
        if (!applies(player, knownAccount)) return;
        // El nombre que se enseña sale del UUID, no del que se guardó: si el baneado se
        // cambió el nombre, el staff debe leer el de ahora, que es por el que preguntará.
        String bannedName = currentName(ban);
        // El numero y la fecha son los del ban de la cuenta que provoco este baneo de
        // conexion: es el castigo que el jugador tendra que citar si apela.
        BanManager.BanStamp stamp = ban.account() == null ? null
                : module.bans().stamp(ban.account());
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                module.formatter().screen(player, ModerationFormatter.Screen.BAN,
                        player.getName(), ban.staff(), ban.until(), ban.reason(),
                        stamp == null ? 0L : stamp.id(), stamp == null ? 0L : stamp.since()));

        if (!knownAccount) {
            boolean autoBanned = false;
            if (module.plugin().getConfig()
                    .getBoolean("Staff_Moderation.Ip_Ban.Ban_New_Accounts", true)
                    && module.bans().get(player.getUniqueId()) == null) {
                try {
                    long remaining = ban.until() == TimeUtil.PERMANENT
                            ? TimeUtil.PERMANENT : ban.until() - System.currentTimeMillis();
                    module.bans().ban(player, remaining, ban.reason(), "IpGuard");
                    module.history().record(player.getUniqueId(), "ipban-alt", "IpGuard",
                            module.formatter().plainReason(ban.reason())
                                    + " (alt: " + bannedName + ")");
                    autoBanned = true;
                } catch (RuntimeException failure) {
                    module.plugin().getLogger().warning("Could not auto-ban the alt "
                            + player.getName() + ": " + failure.getMessage());
                }
            }
            notifyStaff(autoBanned ? "staff-moderation.ipban-alt-banned"
                    : "staff-moderation.ipban-blocked-alert", player.getName(),
                    bannedName);
        }
        module.plugin().getLogger().info("[IpGuard] Blocked " + player.getName()
                + " (banned connection of " + bannedName + ").");
    }

    /** Exenciones del ban de conexion: las mismas al entrar y al expulsar desde la red. */
    private static boolean applies(Player player, boolean knownAccount) {
        boolean exemptOp = player.isOp() && StaffAccess.exemptsOps();
        return applies(exemptOp, knownAccount,
                !exemptOp && !knownAccount && player.hasPermission("chatalyst.bypass.ban"));
    }

    /**
     * Quien esta protegido contra el ban de cuenta tampoco cae por compartir conexion. Un
     * /banip con IP literal no pasa por la proteccion del objetivo (no hay cuenta que
     * comprobar), asi que sin esto bastaba conocer la IP de un admin para dejarlo fuera y,
     * con Ban_New_Accounts, baneada su cuenta al intentar volver. La cuenta que provoco el
     * ban si queda bloqueada: esa ya paso la comprobacion al banearla.
     */
    static boolean applies(boolean exemptOp, boolean knownAccount, boolean bypass) {
        if (exemptOp) return false;
        return knownAccount || !bypass;
    }

    /**
     * Expulsion entre servidores: un /banip puesto en otro backend llega por el sondeo del
     * Store. Solo se recorren los conectados cuando cambia una fila de ban de conexion (no en
     * cada tick), y el hash de cada uno se calcula en su propia region.
     */
    void enforceRemoteBans() {
        store.addInvalidationListener(this::kickRemotelyBanned);
    }

    private void kickRemotelyBanned(java.util.Collection<String> hashes) {
        // Hilo del sondeo (async): get() puede retirar expirados en la base.
        java.util.Set<String> active = new java.util.HashSet<>();
        for (String hash : hashes) {
            if (get(hash) != null) active.add(hash);
        }
        if (active.isEmpty()) return;
        FoliaTasks.onlinePlayers(module.plugin(), player -> {
            java.net.InetSocketAddress address = player.getAddress();
            if (address == null || address.getAddress() == null) return;
            String hash = AltTracker.hashIp(address.getAddress().getHostAddress());
            if (!active.contains(hash)) return;
            // Relectura del cache, sin E/S en la region: un unban reciente gana.
            IpBan ban = IpBan.decode(store.get(hash));
            if (ban == null || ban.expired(System.currentTimeMillis())) return;
            if (!applies(player, ban.isTheBannedAccount(player))) return;
            BanManager.BanStamp stamp = ban.account() == null ? null
                    : module.bans().stamp(ban.account());
            player.kick(module.formatter().screen(player, ModerationFormatter.Screen.BAN,
                    player.getName(), ban.staff(), ban.until(), ban.reason(),
                    stamp == null ? 0L : stamp.id(), stamp == null ? 0L : stamp.since()));
            module.plugin().getLogger().info("[IpGuard] Kicked " + player.getName()
                    + " (connection banned on another server).");
        });
    }

    /**
     * Como se llama HOY la cuenta que provoco el baneo.
     *
     * <p>Se pregunta por el UUID y solo se cae al nombre guardado cuando no hay respuesta
     * (registro legacy sin UUID, o una cuenta que el servidor no ha visto nunca). Ninguna de
     * las dos consultas sale a la red: el jugador conectado primero, y si no la cache del
     * servidor.
     */
    private String currentName(IpBan ban) {
        if (ban.account() != null) {
            org.bukkit.entity.Player online =
                    module.plugin().getServer().getPlayer(ban.account());
            if (online != null) return online.getName();
            String cached = module.plugin().getServer()
                    .getOfflinePlayer(ban.account()).getName();
            if (cached != null && !cached.isBlank()) return cached;
        }
        return ban.playerName();
    }

    private void notifyStaff(String key, String attempted, String banned) {
        FoliaTasks.onlinePlayers(module.plugin(), viewer -> {
            if (!StaffAccess.has(viewer, "chatalyst.command.ban")) return;
            viewer.sendMessage(Lang.get(viewer, key,
                    "player", MM.escape(attempted), "banned", MM.escape(banned)));
        });
        module.audit(module.plugin().getServer().getConsoleSender(), "ipban block",
                attempted + " (connection of " + banned + ")");
    }

    void shutdown() {
        store.flushNow();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(FIELD, " ");
    }
}
