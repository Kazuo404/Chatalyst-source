package dev.chatalyst.mail;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correo entre jugadores (tambien a jugadores desconectados). Persiste en el {@link Store}
 * "mail" (YAML o base de datos segun config), asi con MySQL el correo es cross-server.
 *
 * <p>Cada jugador guarda sus correos serializados en un unico valor: entradas separadas por
 * RS (U+001E) y campos id/fromId/from/message/time/read/anonymous por US (U+001F). Esos caracteres de control
 * nunca llegan desde el chat (el sanitizer los elimina) y aqui ademas se filtran al guardar.
 */
public final class MailManager {

    public enum SendResult {
        SENT,
        SPAM,
        BLOCKED,
        PRIVACY_DISABLED,
        COOLDOWN,
        MUTED,
        /** En el hueco de /anonymous: para el server ese jugador acaba de irse. */
        GONE,
        /**
         * El destinatario le tiene en /ignore. No se guarda ni se avisa a nadie; quien envia
         * recibe la misma respuesta que un envio normal (ignorar es silencioso).
         */
        IGNORED,
        TOO_LONG,
        STORAGE_FAILED
    }

    public enum FriendRequestResult {
        SENT,
        SELF,
        BLOCKED,
        ALREADY_FRIENDS,
        ALREADY_PENDING,
        TARGET_FULL,
        ANONYMOUS_BLOCKED,
        STORAGE_FAILED
    }

    public enum RequestActionResult { APPLIED, NOT_FOUND, ANONYMOUS_BLOCKED, STORAGE_FAILED }

    public enum BlockResult { ADDED, ALREADY_BLOCKED, INVALID, STORAGE_FAILED }

    public record MailEntry(String id, String fromId, String from, String message,
                            long time, boolean read, boolean anonymous) {
        public MailEntry(String id, String fromId, String from, String message,
                         long time, boolean read) {
            this(id, fromId, from, message, time, read, false);
        }
    }

    public record BlockEntry(String playerId, String playerName) {
    }

    public record FriendEntry(String playerId, String playerName) {
    }

    public enum RequestState { PENDING, ACCEPTING }

    public record FriendRequest(String fromId, String fromName, long time,
                                RequestState state, String ownerName) {
        public FriendRequest(String fromId, String fromName, long time) {
            this(fromId, fromName, time, RequestState.PENDING, "");
        }

        FriendRequest accepting(String acceptingOwnerName) {
            return new FriendRequest(fromId, fromName, time, RequestState.ACCEPTING,
                    acceptingOwnerName == null ? "" : acceptingOwnerName);
        }
    }

    private static final char RS = '\u001E'; // separa entradas
    private static final char US = '\u001F'; // separa campos

    private final JavaPlugin plugin;
    private final Store store;
    private final Store spamStore;
    private final Store blockStore;
    private final Store settingsStore;
    private final Store friendsStore;
    private final Store requestsStore;
    private final Store friendOpsStore;
    /** Store "ignore" de los privados; null en pruebas sin servidor. */
    private final Store ignoreStore;
    // Ultimo envio por jugador, para el cooldown propio de correo (anti-spam independiente).
    // Se poda lo ya caducado cuando crece, para no acumular una entrada por jugador historico.
    private final Map<UUID, Long> lastSend = new ConcurrentHashMap<>();
    private static final int COOLDOWN_SOFT_CAP = 5000;
    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 1024;
    private static final int HARD_MAX_MESSAGE_LENGTH = 4096;
    private static final int DEFAULT_MAX_MESSAGES = 100;
    private static final int HARD_MAX_MESSAGES = 1_000;
    static final int MAX_PENDING_FRIEND_REQUESTS = 100;
    // Opcional (se inyecta tras crear ambos managers): mutes con Mute.Block_Mail bloquean el envio.
    private volatile dev.chatalyst.moderation.MuteManager mutes;
    private volatile dev.chatalyst.log.LogManager log;

    public MailManager(JavaPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("mail");
        this.spamStore = storage.store("mail-spam");
        this.blockStore = storage.store("mail-blocks");
        this.settingsStore = storage.store("mail-settings");
        this.friendsStore = storage.store("mail-friends");
        this.requestsStore = storage.store("mail-friend-requests");
        this.friendOpsStore = storage.store("mail-friend-operations");
        this.ignoreStore = storage.store("ignore");
        migrateLegacy(plugin);
        recoverFriendOperations();
    }

    /** Constructor del nucleo persistente para pruebas sin servidor Bukkit. */
    MailManager(Store store, Store spamStore, Store blockStore, Store settingsStore,
                Store friendsStore, Store requestsStore) {
        this.plugin = null;
        this.store = store;
        this.spamStore = spamStore;
        this.blockStore = blockStore;
        this.settingsStore = settingsStore;
        this.friendsStore = friendsStore;
        this.requestsStore = requestsStore;
        // Prefijo "remove:" evita colisionar con las claves UUID de solicitudes en tests
        // antiguos que comparten el mismo FakeStore para no ampliar su constructor publico.
        this.friendOpsStore = requestsStore;
        this.ignoreStore = null;
        recoverFriendOperations();
    }

    public SendResult send(UUID to, String from, String message) {
        return send(to, "", from, message);
    }

    public SendResult send(UUID to, UUID fromId, String from, String message) {
        return send(to, fromId != null ? fromId.toString() : "", from, message);
    }

    public void setMuteManager(dev.chatalyst.moderation.MuteManager mutes) {
        this.mutes = mutes;
    }

    public void setLog(dev.chatalyst.log.LogManager log) {
        this.log = log;
    }

    public SendResult send(UUID to, String fromId, String from, String message) {
        if (message == null || message.codePointCount(0, message.length()) > maxMessageLength()) {
            return SendResult.TOO_LONG;
        }
        // Cooldown de envio propio (anti-spam), para no depender de otros plugins ni chocar con ellos.
        UUID senderId = parseUuid(fromId);
        // Punto unico de todos los caminos de envio (comando, reply, menus, compose por chat
        // o libro): un muteado no puede enviar correo. Los soft mutes no cuentan: el shadow
        // mute solo afecta al chat publico.
        if (senderId != null && mutes != null
                && plugin.getConfig().getBoolean("Mute.Block_Mail", true)) {
            var mute = mutes.getMute(senderId);
            if (mute != null && !mute.soft) {
                return SendResult.MUTED;
            }
        }
        // Y en el mismo punto unico: en el hueco de /anonymous el server ya despidio a este
        // jugador. El correo llega con el OTRO nombre y ademas avisa al instante a quien lo
        // recibe, asi que la pista que da es la misma que la de un privado.
        if (senderId != null) {
            var running = dev.chatalyst.ChatalystPlugin.get();
            if (running != null && running.getAnonymousManager() != null
                    && running.getAnonymousManager().departureAnnounced(senderId)) {
                return SendResult.GONE;
            }
        }
        if (senderId != null && sendCooldownRemaining(senderId) > 0) {
            return SendResult.COOLDOWN;
        }
        if (isReceivingDisabled(to)) {
            return SendResult.PRIVACY_DISABLED;
        }
        if (isBlocked(to, fromId, from)) {
            return SendResult.BLOCKED;
        }
        // /ignore tambien cierra el buzon. Antes solo frenaba los privados: el ignorado
        // seguia escribiendo por /inbox y cada correo ademas le saltaba al otro como aviso.
        // Silencioso, igual que en los privados: el remitente no sabe que le ignoran.
        if (senderId != null && ignores(to, senderId) && !reachesIgnoring(senderId)) {
            return SendResult.IGNORED;
        }
        // Por defecto CUALQUIERA puede escribir al inbox (no hace falta agregarse como amigo).
        // Con Mail.Require_Friend=true, los correos de no-amigos caen en la carpeta de spam.
        boolean friend = areFriends(to, fromId, from);
        boolean toInbox = friend || !plugin.getConfig().getBoolean("Mail.Require_Friend", false);
        boolean anonymous = senderId != null
                && dev.chatalyst.hook.Placeholders.isAnonymous(senderId);
        MailEntry entry = new MailEntry(UUID.randomUUID().toString(), fromId, from, message,
                System.currentTimeMillis(), false, anonymous);
        var targetStore = toInbox ? store : spamStore;
        int max = mailboxLimit();
        // Append con CAS: dos correos enviados a la vez al mismo buzon (desde servers
        // distintos de la red) se encadenan en vez de que uno pise al otro. Se capa el
        // tamaño (el mas viejo se cae) para que el buzon nunca crezca sin limite.
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(targetStore, to.toString(), raw -> {
            List<MailEntry> list = parse(raw);
            list.add(entry);
            return serialize(capOldest(list, max));
        });
        if (!applied) {
            // Nunca degradar a get+put: en MySQL esa escritura podria pisar un correo que
            // entro desde otro server tras agotar el CAS. El caller informa y puede reintentar.
            return SendResult.STORAGE_FAILED;
        }
        if (senderId != null) {
            pruneStaleCooldowns();
            lastSend.put(senderId, System.currentTimeMillis());
        }
        if (log != null && log.logsMail()) {
            String recipient = Bukkit.getOfflinePlayer(to).getName();
            log.log("MAIL", from + " -> " + (recipient != null ? recipient : to), message);
        }
        return toInbox ? SendResult.SENT : SendResult.SPAM;
    }

    /**
     * Si {@code owner} tiene a {@code sender} en /ignore. Se lee el Store "ignore" (el mismo
     * que persiste PrivateMessages, CSV de UUIDs) y no su cache en memoria: esa solo existe
     * para quien esta conectado, y el correo llega sobre todo a desconectados.
     */
    boolean ignores(UUID owner, UUID sender) {
        if (ignoreStore == null || owner == null || sender == null) return false;
        return ignoreListContains(ignoreStore.get(owner.toString()), sender);
    }

    static boolean ignoreListContains(String csv, UUID sender) {
        if (csv == null || csv.isBlank()) return false;
        String wanted = sender.toString();
        for (String part : csv.split(",")) {
            if (part.trim().equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }

    /** El staff con chatalyst.bypass.reach alcanza a quien le ignora, como en los privados. */
    private boolean reachesIgnoring(UUID senderId) {
        if (plugin == null) return false;
        Player sender = Bukkit.getPlayer(senderId);
        return sender != null
                && sender.hasPermission(dev.chatalyst.pm.PrivateMessages.REACH_PERMISSION);
    }

    public int maxMessageLength() {
        if (plugin == null) return DEFAULT_MAX_MESSAGE_LENGTH;
        return Math.max(64, Math.min(HARD_MAX_MESSAGE_LENGTH,
                plugin.getConfig().getInt("Mail.Max_Message_Length",
                        DEFAULT_MAX_MESSAGE_LENGTH)));
    }

    int mailboxLimit() {
        if (plugin == null) return DEFAULT_MAX_MESSAGES;
        return normalizeMailboxLimit(plugin.getConfig().getInt(
                "Mail.Max_Messages", DEFAULT_MAX_MESSAGES));
    }

    static int normalizeMailboxLimit(int configured) {
        // El antiguo <=0 significaba ilimitado; se conserva como un limite amplio, pero no
        // como crecimiento infinito de una sola fila SQL/YAML controlada por jugadores.
        if (configured <= 0) return HARD_MAX_MESSAGES;
        return Math.min(HARD_MAX_MESSAGES, configured);
    }

    /** Poda ultimos-envios ya fuera de la ventana de cooldown; solo actua cuando el mapa crece. */
    private void pruneStaleCooldowns() {
        if (lastSend.size() < COOLDOWN_SOFT_CAP) return;
        long windowMs = Math.max(plugin.getConfig().getInt("Mail.Send_Cooldown_Seconds", 3), 60) * 1000L;
        long cutoff = System.currentTimeMillis() - windowMs;
        lastSend.values().removeIf(t -> t < cutoff);
    }

    /** Segundos que faltan para que el jugador pueda enviar otro correo (0 = ya puede). */
    public long sendCooldownRemaining(UUID senderId) {
        int seconds = plugin.getConfig().getInt("Mail.Send_Cooldown_Seconds", 3);
        if (seconds <= 0 || senderId == null) {
            return 0;
        }
        Player p = Bukkit.getPlayer(senderId);
        if (p != null && p.hasPermission("chatalyst.bypass.mailcooldown")) {
            return 0;
        }
        Long last = lastSend.get(senderId);
        if (last == null) {
            return 0;
        }
        long remaining = seconds * 1000L - (System.currentTimeMillis() - last);
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public List<MailEntry> get(UUID owner) {
        return parse(store.get(owner.toString()));
    }

    public List<MailEntry> spam(UUID owner) {
        // Completa una mudanza inbox<-spam interrumpida entre sus dos CAS. Los IDs hacen la
        // operacion idempotente: si ya existe en inbox, la copia de spam es solo un residuo.
        java.util.Set<String> inboxIds = get(owner).stream()
                .map(MailEntry::id).collect(java.util.stream.Collectors.toSet());
        if (!inboxIds.isEmpty()) {
            dev.chatalyst.db.StoreCas.updateDurably(spamStore, owner.toString(), raw -> {
                List<MailEntry> current = parse(raw);
                boolean removed = current.removeIf(entry -> inboxIds.contains(entry.id()));
                return removed ? serialize(current) : null;
            });
        }
        return parse(spamStore.get(owner.toString()));
    }

    public int unreadCount(UUID owner) {
        return unreadCount(get(owner));
    }

    public int unreadSpamCount(UUID owner) {
        return unreadCount(spam(owner));
    }

    private int unreadCount(List<MailEntry> entries) {
        int count = 0;
        for (MailEntry m : entries) {
            if (!m.read()) count++;
        }
        return count;
    }

    // Las mutaciones del buzon van con CAS, como send(): un put plano de "marcar leido" o
    // "borrar" que se cruce con un correo entrante de OTRO server pisaria (y perderia) ese
    // correo recien añadido. Con CAS la mutacion se reintenta sobre el valor real de la DB.

    public boolean markAllRead(UUID owner) {
        return dev.chatalyst.db.StoreCas.updateDurably(store, owner.toString(), raw -> {
            List<MailEntry> list = parse(raw);
            if (list.isEmpty()) return null; // nada que marcar: abortar sin escribir
            List<MailEntry> read = new ArrayList<>(list.size());
            for (MailEntry m : list) {
                read.add(new MailEntry(m.id(), m.fromId(), m.from(), m.message(), m.time(),
                        true, m.anonymous()));
            }
            return serialize(read);
        });
    }

    public boolean markRead(UUID owner, int index) {
        return markRead(store, owner, entryAt(store, owner, index));
    }

    public boolean markSpamRead(UUID owner, int index) {
        return markRead(spamStore, owner, entryAt(spamStore, owner, index));
    }

    public boolean markRead(UUID owner, MailEntry entry) {
        return markRead(store, owner, entry);
    }

    public boolean markSpamRead(UUID owner, MailEntry entry) {
        return markRead(spamStore, owner, entry);
    }

    private boolean markRead(Store targetStore, UUID owner, MailEntry target) {
        if (target == null) return false;
        return dev.chatalyst.db.StoreCas.updateDurably(targetStore, owner.toString(), raw -> {
            List<MailEntry> list = parse(raw);
            int index = indexOf(list, target.id());
            if (index < 0) return null;
            MailEntry entry = list.get(index);
            if (entry.read()) return raw;
            list.set(index, new MailEntry(entry.id(), entry.fromId(), entry.from(),
                    entry.message(), entry.time(), true, entry.anonymous()));
            return serialize(list);
        });
    }

    public boolean delete(UUID owner, int index) {
        return delete(store, owner, entryAt(store, owner, index));
    }

    public boolean deleteSpam(UUID owner, int index) {
        return delete(spamStore, owner, entryAt(spamStore, owner, index));
    }

    public boolean delete(UUID owner, MailEntry entry) {
        return delete(store, owner, entry);
    }

    public boolean deleteSpam(UUID owner, MailEntry entry) {
        return delete(spamStore, owner, entry);
    }

    public boolean deleteById(UUID owner, String id, boolean spam) {
        Store targetStore = spam ? spamStore : store;
        return delete(targetStore, owner, find(targetStore, owner, id));
    }

    private boolean delete(Store targetStore, UUID owner, MailEntry target) {
        if (target == null) return false;
        // El buzon vacio queda como cadena vacia en vez de borrarse la fila: un remove()
        // plano tras el CAS podria cruzarse con un correo entrante de otro server y
        // borrarlo. parse("") = buzon vacio, asi que el comportamiento visible es el mismo.
        return dev.chatalyst.db.StoreCas.updateDurably(targetStore, owner.toString(), raw -> {
            List<MailEntry> list = parse(raw);
            int index = indexOf(list, target.id());
            if (index < 0) return null;
            list.remove(index);
            return serialize(list);
        });
    }

    public MailEntry latestUnread(UUID owner) {
        return latestUnread(get(owner));
    }

    public MailEntry latestUnreadSpam(UUID owner) {
        return latestUnread(spam(owner));
    }

    private MailEntry latestUnread(List<MailEntry> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            MailEntry entry = list.get(i);
            if (!entry.read()) {
                return entry;
            }
        }
        return null;
    }

    public boolean clear(UUID owner) {
        // Cadena vacia mantiene el clear dentro del CAS y no puede borrar un correo nuevo.
        return dev.chatalyst.db.StoreCas.updateDurably(store, owner.toString(), raw -> "");
    }

    public boolean clearSpam(UUID owner) {
        return dev.chatalyst.db.StoreCas.updateDurably(spamStore, owner.toString(), raw -> "");
    }

    public boolean block(UUID owner, UUID blockedId, String blockedName) {
        return blockResult(owner, blockedId, blockedName) == BlockResult.ADDED;
    }

    public BlockResult blockResult(UUID owner, UUID blockedId, String blockedName) {
        if (blockedId == null && (blockedName == null || blockedName.isBlank())) {
            return BlockResult.INVALID;
        }
        String id = blockedId != null ? blockedId.toString() : "";
        boolean[] already = {false};
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(
                blockStore, owner.toString(), raw -> {
            List<BlockEntry> entries = parseBlocks(raw);
            for (BlockEntry entry : entries) {
                if (sameBlock(entry, id, blockedName)) {
                    already[0] = true;
                    return null;
                }
            }
            entries.add(new BlockEntry(id, blockedName != null ? blockedName : ""));
            return serializeBlocks(entries);
        });
        if (already[0]) return BlockResult.ALREADY_BLOCKED;
        return applied ? BlockResult.ADDED : BlockResult.STORAGE_FAILED;
    }

    public boolean unblock(UUID owner, UUID blockedId, String blockedName) {
        String id = blockedId != null ? blockedId.toString() : "";
        return dev.chatalyst.db.StoreCas.updateDurably(blockStore, owner.toString(), raw -> {
            List<BlockEntry> entries = parseBlocks(raw);
            boolean removed = entries.removeIf(entry -> sameBlock(entry, id, blockedName));
            return removed ? serializeBlocks(entries) : null;
        });
    }

    public boolean isBlocked(UUID owner, UUID senderId, String senderName) {
        return isBlocked(owner, senderId != null ? senderId.toString() : "", senderName);
    }

    public boolean isBlocked(UUID owner, String senderId, String senderName) {
        for (BlockEntry entry : blocked(owner)) {
            if (sameBlock(entry, senderId, senderName)) {
                return true;
            }
        }
        return false;
    }

    public List<BlockEntry> blocked(UUID owner) {
        return parseBlocks(blockStore.get(owner.toString()));
    }

    public List<FriendEntry> friends(UUID owner) {
        return parseFriends(friendsStore.get(owner.toString()));
    }

    public List<FriendRequest> requests(UUID owner) {
        recoverAcceptances(owner);
        return parseRequests(requestsStore.get(owner.toString())).stream()
                .filter(request -> request.state() == RequestState.PENDING)
                .toList();
    }

    public boolean areFriends(UUID owner, UUID otherId, String otherName) {
        return areFriends(owner, otherId != null ? otherId.toString() : "", otherName);
    }

    public boolean areFriends(UUID owner, String otherId, String otherName) {
        if (owner != null && otherId != null && !otherId.isBlank()
                && owner.toString().equals(otherId)) {
            return true;
        }
        for (FriendEntry entry : friends(owner)) {
            if (sameIdentity(entry.playerId(), entry.playerName(), otherId, otherName)) {
                return true;
            }
        }
        return false;
    }

    public FriendRequestResult requestFriend(UUID target, UUID fromId, String fromName) {
        if (target == null || fromId == null || target.equals(fromId)) {
            return FriendRequestResult.SELF;
        }
        if (dev.chatalyst.hook.Placeholders.isAnonymous(fromId)
                || dev.chatalyst.hook.Placeholders.isAnonymous(target)) {
            return FriendRequestResult.ANONYMOUS_BLOCKED;
        }
        // /ignore cuenta como bloqueo: una solicitud tambien le salta al otro como aviso.
        if (isBlocked(target, fromId, fromName) || ignores(target, fromId)) {
            return FriendRequestResult.BLOCKED;
        }
        if (areFriends(target, fromId, fromName)) {
            return FriendRequestResult.ALREADY_FRIENDS;
        }
        FriendRequest newRequest = new FriendRequest(fromId.toString(), fromName, System.currentTimeMillis());
        // CAS: comprobar duplicado y anadir en la misma mutacion, contra el valor real de la
        // DB, para que dos solicitudes simultaneas (o repetidas desde otro server) no se pisen.
        boolean[] alreadyPending = {false};
        boolean[] targetFull = {false};
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(requestsStore, target.toString(), raw -> {
            List<FriendRequest> requests = parseRequests(raw);
            for (FriendRequest request : requests) {
                if (sameIdentity(request.fromId(), request.fromName(), fromId.toString(), fromName)) {
                    alreadyPending[0] = true;
                    return null; // abortar sin escribir
                }
            }
            if (requests.size() >= MAX_PENDING_FRIEND_REQUESTS) {
                targetFull[0] = true;
                return null;
            }
            requests.add(newRequest);
            return serializeRequests(requests);
        });
        if (alreadyPending[0]) {
            return FriendRequestResult.ALREADY_PENDING;
        }
        if (targetFull[0]) {
            return FriendRequestResult.TARGET_FULL;
        }
        if (!applied) {
            return FriendRequestResult.STORAGE_FAILED;
        }
        return FriendRequestResult.SENT;
    }

    public boolean acceptRequest(UUID owner, String ownerName, int index) {
        return acceptRequestResult(owner, ownerName, index) == RequestActionResult.APPLIED;
    }

    public RequestActionResult acceptRequestResult(UUID owner, String ownerName, int index) {
        List<FriendRequest> requests = requests(owner);
        if (index < 0 || index >= requests.size()) {
            return RequestActionResult.NOT_FOUND;
        }
        return acceptRequestResult(owner, ownerName, requests.get(index));
    }

    public RequestActionResult acceptRequestResult(
            UUID owner, String ownerName, FriendRequest request) {
        if (request == null) return RequestActionResult.NOT_FOUND;
        if (dev.chatalyst.hook.Placeholders.isAnonymous(owner)) {
            return RequestActionResult.ANONYMOUS_BLOCKED;
        }
        FriendRequest accepting = request.accepting(ownerName);
        boolean claimed = dev.chatalyst.db.StoreCas.updateDurably(
                requestsStore, owner.toString(), raw -> {
            List<FriendRequest> current = parseRequests(raw);
            int currentIndex = current.indexOf(request);
            if (currentIndex < 0) return null;
            current.set(currentIndex, accepting);
            return serializeRequests(current);
        });
        if (!claimed) {
            boolean stillPending = parseRequests(requestsStore.get(owner.toString()))
                    .contains(request);
            return stillPending ? RequestActionResult.STORAGE_FAILED
                    : RequestActionResult.NOT_FOUND;
        }
        if (!finishAcceptance(owner, accepting)) return RequestActionResult.STORAGE_FAILED;
        moveSpamToInboxFrom(owner, request.fromId(), request.fromName());
        return RequestActionResult.APPLIED;
    }

    public boolean denyRequest(UUID owner, int index) {
        return denyRequestResult(owner, index) == RequestActionResult.APPLIED;
    }

    public RequestActionResult denyRequestResult(UUID owner, int index) {
        List<FriendRequest> requests = requests(owner);
        if (index < 0 || index >= requests.size()) {
            return RequestActionResult.NOT_FOUND;
        }
        return denyRequestResult(owner, requests.get(index));
    }

    public RequestActionResult denyRequestResult(UUID owner, FriendRequest request) {
        if (request == null) return RequestActionResult.NOT_FOUND;
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(
                requestsStore, owner.toString(), raw -> {
            List<FriendRequest> current = parseRequests(raw);
            return current.remove(request) ? serializeRequests(current) : null;
        });
        if (applied) return RequestActionResult.APPLIED;
        return parseRequests(requestsStore.get(owner.toString())).contains(request)
                ? RequestActionResult.STORAGE_FAILED : RequestActionResult.NOT_FOUND;
    }

    /** Acepta la solicitud pendiente de un jugador por su NOMBRE (para el boton del chat). */
    public boolean acceptRequestFrom(UUID owner, String ownerName, String fromName) {
        return acceptRequestFromResult(owner, ownerName, fromName)
                == RequestActionResult.APPLIED;
    }

    public RequestActionResult acceptRequestFromResult(
            UUID owner, String ownerName, String fromName) {
        int index = requestIndexOf(owner, fromName);
        return index >= 0 ? acceptRequestResult(owner, ownerName, index)
                : RequestActionResult.NOT_FOUND;
    }

    /** Rechaza la solicitud pendiente de un jugador por su NOMBRE (para el boton del chat). */
    public boolean denyRequestFrom(UUID owner, String fromName) {
        return denyRequestFromResult(owner, fromName) == RequestActionResult.APPLIED;
    }

    public RequestActionResult denyRequestFromResult(UUID owner, String fromName) {
        int index = requestIndexOf(owner, fromName);
        return index >= 0 ? denyRequestResult(owner, index) : RequestActionResult.NOT_FOUND;
    }

    private int requestIndexOf(UUID owner, String fromName) {
        if (fromName == null) return -1;
        List<FriendRequest> requests = requests(owner);
        for (int i = 0; i < requests.size(); i++) {
            if (fromName.equalsIgnoreCase(requests.get(i).fromName())) return i;
        }
        return -1;
    }

    public boolean removeFriend(UUID owner, UUID friendId, String friendName) {
        String id = friendId != null ? friendId.toString() : "";
        if (friendId == null) {
            return removeFriendOneWay(owner, id, friendName) == RemoveResult.REMOVED;
        }
        if (!areFriends(owner, friendId, friendName)) return false;
        String operationId = "remove:" + UUID.randomUUID();
        String operation = owner + String.valueOf(US) + friendId + US + clean(friendName);
        if (!dev.chatalyst.db.StoreCas.putIfAbsentDurably(
                friendOpsStore, operationId, operation)) return false;
        return finishFriendRemoval(operationId, operation);
    }

    public int moveSpamToInboxFrom(UUID owner, String fromId, String fromName) {
        // Primero copiar al inbox y DESPUES retirar del spam. Si el segundo CAS falla puede
        // quedar un duplicado temporal entre carpetas, pero ningun mensaje se pierde.
        List<MailEntry> movedEntries = spam(owner).stream()
                .filter(entry -> sameIdentity(entry.fromId(), entry.from(), fromId, fromName))
                .toList();
        if (movedEntries.isEmpty()) return 0;
        boolean inboxOk = dev.chatalyst.db.StoreCas.updateDurably(store, owner.toString(), raw -> {
            List<MailEntry> inbox = parse(raw);
            for (MailEntry entry : movedEntries) {
                if (indexOf(inbox, entry.id()) < 0) inbox.add(entry);
            }
            return serialize(capOldest(inbox, mailboxLimit()));
        });
        if (!inboxOk) return 0;
        dev.chatalyst.db.StoreCas.updateDurably(spamStore, owner.toString(), raw -> {
            List<MailEntry> current = parse(raw);
            for (MailEntry entry : movedEntries) current.remove(entry);
            return serialize(current);
        });
        return movedEntries.size();
    }

    public boolean isReceivingDisabled(UUID owner) {
        return Boolean.parseBoolean(settingsStore.get(owner.toString()));
    }

    public boolean setReceivingDisabled(UUID owner, boolean disabled) {
        boolean saved = disabled
                ? settingsStore.putDurably(owner.toString(), "true")
                : settingsStore.removeDurably(owner.toString());
        if (!saved && plugin != null) plugin.getLogger().severe(
                "Could not durably update mail privacy for " + owner + ".");
        return saved;
    }

    public void shutdown() {
        store.flushNow();
        spamStore.flushNow();
        blockStore.flushNow();
        settingsStore.flushNow();
        friendsStore.flushNow();
        requestsStore.flushNow();
        if (friendOpsStore != requestsStore) friendOpsStore.flushNow();
    }

    // ---- Serializacion ---------------------------------------------------------

    /** Limita el buzon a los {@code max} mas recientes (descarta los mas viejos). */
    private static List<MailEntry> capOldest(List<MailEntry> list, int max) {
        if (list.size() <= max) return list;
        return new ArrayList<>(list.subList(list.size() - max, list.size()));
    }

    private static String serialize(List<MailEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (MailEntry m : list) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(clean(m.id())).append(US)
                    .append(clean(m.fromId())).append(US)
                    .append(clean(m.from())).append(US)
                    .append(clean(m.message())).append(US)
                    .append(m.time()).append(US)
                    .append(m.read()).append(US)
                    .append(m.anonymous());
        }
        return sb.toString();
    }

    private static List<MailEntry> parse(String raw) {
        List<MailEntry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        int ordinal = 0;
        for (String entry : raw.split(String.valueOf(RS))) {
            String[] f = entry.split(String.valueOf(US), -1);
            if (f.length < 4) continue; // entrada corrupta: la saltamos
            boolean legacyNamesOnly = f.length == 4;
            boolean legacyWithoutId = f.length == 5;
            long time;
            try {
                time = Long.parseLong(legacyNamesOnly ? f[2]
                        : legacyWithoutId ? f[3] : f[4]);
            } catch (NumberFormatException ex) {
                time = 0L;
            }
            String fromId = legacyNamesOnly ? "" : legacyWithoutId ? f[0] : f[1];
            String from = legacyNamesOnly ? f[0] : legacyWithoutId ? f[1] : f[2];
            String message = legacyNamesOnly ? f[1] : legacyWithoutId ? f[2] : f[3];
            boolean read = Boolean.parseBoolean(legacyNamesOnly ? f[3]
                    : legacyWithoutId ? f[4] : f[5]);
            boolean anonymous = !legacyNamesOnly && !legacyWithoutId
                    && f.length >= 7 && Boolean.parseBoolean(f[6]);
            String id = legacyNamesOnly || legacyWithoutId
                    ? legacyMailId(fromId, from, message, time, ordinal) : f[0];
            out.add(new MailEntry(id, fromId, from, message, time, read, anonymous));
            ordinal++;
        }
        return out;
    }

    private static String legacyMailId(String fromId, String from, String message,
                                       long time, int ordinal) {
        String seed = fromId + US + from + US + message + US + time + US + ordinal;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static int indexOf(List<MailEntry> list, String id) {
        if (id == null || id.isBlank()) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id())) return i;
        }
        return -1;
    }

    private static MailEntry entryAt(Store targetStore, UUID owner, int index) {
        List<MailEntry> list = parse(targetStore.get(owner.toString()));
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static MailEntry find(Store targetStore, UUID owner, String id) {
        List<MailEntry> list = parse(targetStore.get(owner.toString()));
        int index = indexOf(list, id);
        return index < 0 ? null : list.get(index);
    }

    public MailEntry find(UUID owner, String id, boolean spam) {
        return find(spam ? spamStore : store, owner, id);
    }

    /**
     * Identidad visual historica: un correo anonimo nunca se re-resuelve por su UUID.
     *
     * <p>Devuelve MiniMessage listo para meter en una plantilla. Un apodo ya viene saneado
     * (sin click/hover), pero el {@code from} guardado es texto PLANO: el alias de un anonimo
     * es el apodo con {@code MM.strip}, y un apodo con {@code \<click:...>} literal salia de
     * ahi como {@code <click:run_command:...>} de verdad — el destinatario del correo pulsaba
     * su propio inbox y ejecutaba el comando que eligio el remitente. Lo plano se escapa.
     */
    public static String shownFrom(MailEntry entry) {
        if (entry == null) return "?";
        if (entry.anonymous()) return dev.chatalyst.util.MM.escape(entry.from());
        return shownSender(entry.fromId(), entry.from());
    }

    /**
     * Nombre mostrado de un remitente (apodo actual con formato), escapando el nombre guardado
     * cuando no hay apodo que lo sustituya: ese nombre es texto plano, no MiniMessage.
     */
    public static String shownSender(String fromId, String from) {
        String raw = from == null ? "" : from;
        String shown = dev.chatalyst.nick.NickManager.shownName(fromId, raw);
        return shown == null || shown.equals(raw) ? dev.chatalyst.util.MM.escape(raw) : shown;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(RS, ' ').replace(US, ' ');
    }

    private static String serializeBlocks(List<BlockEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (BlockEntry entry : list) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(clean(entry.playerId())).append(US).append(clean(entry.playerName()));
        }
        return sb.toString();
    }

    private static List<BlockEntry> parseBlocks(String raw) {
        List<BlockEntry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(String.valueOf(RS))) {
            String[] f = entry.split(String.valueOf(US), -1);
            if (f.length == 0) continue;
            out.add(new BlockEntry(f[0], f.length > 1 ? f[1] : ""));
        }
        return out;
    }

    private static boolean sameBlock(BlockEntry entry, String id, String name) {
        return sameIdentity(entry.playerId(), entry.playerName(), id, name);
    }

    // La lista de amigos del OTRO jugador se toca desde acceptRequest/removeFriend, que
    // pueden ejecutarse en otro server de la red: por eso el read-modify-write va con CAS.
    private boolean addFriendOneWay(UUID owner, String friendId, String friendName) {
        if (owner == null || ((friendId == null || friendId.isBlank())
                && (friendName == null || friendName.isBlank()))) {
            return false;
        }
        FriendEntry newEntry = new FriendEntry(friendId != null ? friendId : "",
                friendName != null ? friendName : "");
        boolean[] alreadyFriend = {false};
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(
                friendsStore, owner.toString(), raw -> {
            List<FriendEntry> friends = parseFriends(raw);
            for (FriendEntry entry : friends) {
                if (sameIdentity(entry.playerId(), entry.playerName(), friendId, friendName)) {
                    alreadyFriend[0] = true;
                    return null; // ya es amigo: nada que escribir
                }
            }
            friends.add(newEntry);
            return serializeFriends(friends);
        });
        return applied || alreadyFriend[0];
    }

    private enum RemoveResult { REMOVED, ABSENT, FAILED }

    private RemoveResult removeFriendOneWay(UUID owner, String friendId, String friendName) {
        if (owner == null) {
            return RemoveResult.FAILED;
        }
        boolean[] found = {false};
        boolean applied = dev.chatalyst.db.StoreCas.updateDurably(
                friendsStore, owner.toString(), raw -> {
            found[0] = false;
            List<FriendEntry> friends = parseFriends(raw);
            boolean removed = friends.removeIf(entry ->
                    sameIdentity(entry.playerId(), entry.playerName(), friendId, friendName));
            found[0] = removed;
            return removed ? serializeFriends(friends) : null;
        });
        if (applied) return RemoveResult.REMOVED;
        return found[0] ? RemoveResult.FAILED : RemoveResult.ABSENT;
    }

    private static String serializeFriends(List<FriendEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (FriendEntry entry : list) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(clean(entry.playerId())).append(US).append(clean(entry.playerName()));
        }
        return sb.toString();
    }

    private static List<FriendEntry> parseFriends(String raw) {
        List<FriendEntry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(String.valueOf(RS))) {
            String[] f = entry.split(String.valueOf(US), -1);
            if (f.length == 0) continue;
            out.add(new FriendEntry(f[0], f.length > 1 ? f[1] : ""));
        }
        return out;
    }

    private static String serializeRequests(List<FriendRequest> list) {
        StringBuilder sb = new StringBuilder();
        for (FriendRequest request : list) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(clean(request.fromId())).append(US)
                    .append(clean(request.fromName())).append(US)
                    .append(request.time()).append(US)
                    .append(request.state().name()).append(US)
                    .append(clean(request.ownerName()));
        }
        return sb.toString();
    }

    private static List<FriendRequest> parseRequests(String raw) {
        List<FriendRequest> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(String.valueOf(RS))) {
            String[] f = entry.split(String.valueOf(US), -1);
            if (f.length < 2) continue;
            long time = 0L;
            if (f.length > 2) {
                try {
                    time = Long.parseLong(f[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            RequestState state = RequestState.PENDING;
            if (f.length > 3) {
                try {
                    state = RequestState.valueOf(f[3]);
                } catch (IllegalArgumentException ignored) {
                    // Formato futuro/corrupto: no ejecutar automaticamente una aceptacion.
                }
            }
            out.add(new FriendRequest(f[0], f[1], time, state,
                    f.length > 4 ? f[4] : ""));
        }
        return out;
    }

    private boolean finishAcceptance(UUID owner, FriendRequest request) {
        UUID sender = parseUuid(request.fromId());
        if (sender == null) return false;
        if (!addFriendOneWay(owner, request.fromId(), request.fromName())) return false;
        if (!addFriendOneWay(sender, owner.toString(), request.ownerName())) return false;
        return dev.chatalyst.db.StoreCas.updateDurably(
                requestsStore, owner.toString(), raw -> {
            List<FriendRequest> current = parseRequests(raw);
            return current.remove(request) ? serializeRequests(current) : null;
        });
    }

    private void recoverAcceptances(UUID owner) {
        for (FriendRequest request : parseRequests(requestsStore.get(owner.toString()))) {
            if (request.state() == RequestState.ACCEPTING) finishAcceptance(owner, request);
        }
    }

    private boolean finishFriendRemoval(String operationId, String raw) {
        String[] fields = raw.split(String.valueOf(US), -1);
        if (fields.length < 2) return friendOpsStore.compareAndRemoveDurably(operationId, raw);
        final UUID owner;
        final UUID friend;
        try {
            owner = UUID.fromString(fields[0]);
            friend = UUID.fromString(fields[1]);
        } catch (IllegalArgumentException invalid) {
            return friendOpsStore.compareAndRemoveDurably(operationId, raw);
        }
        String friendName = fields.length > 2 ? fields[2] : "";
        if (removeFriendOneWay(owner, friend.toString(), friendName) == RemoveResult.FAILED) {
            return false;
        }
        if (removeFriendOneWay(friend, owner.toString(), "") == RemoveResult.FAILED) {
            return false;
        }
        return friendOpsStore.compareAndRemoveDurably(operationId, raw);
    }

    private void recoverFriendOperations() {
        // Primero termina aceptaciones reclamadas. Despues completa journals de eliminacion;
        // ambas operaciones son idempotentes y pueden reintentarse tras otra caida.
        for (String rawOwner : requestsStore.all().keySet()) {
            if (rawOwner.startsWith("remove:")) continue;
            try {
                recoverAcceptances(UUID.fromString(rawOwner));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (Map.Entry<String, String> operation : friendOpsStore.all().entrySet()) {
            if (operation.getKey().startsWith("remove:")) {
                finishFriendRemoval(operation.getKey(), operation.getValue());
            }
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean sameIdentity(String entryId, String entryName, String targetId, String targetName) {
        boolean entryHasId = entryId != null && !entryId.isBlank();
        boolean targetHasId = targetId != null && !targetId.isBlank();
        // Con UUID en AMBOS lados, el UUID es autoritativo: dos cuentas distintas jamas son
        // "la misma" solo por compartir nombre. Sin esto, quien tomara el nombre viejo de
        // otro jugador heredaba sus amistades y bloqueos (los nombres Mojang se reciclan).
        if (entryHasId && targetHasId) {
            return targetId.equals(entryId);
        }
        // Fallback por nombre SOLO cuando un lado no tiene UUID (entradas legacy o
        // remitentes de sistema): es la unica comparacion posible ahi.
        return targetName != null
                && !targetName.isBlank()
                && targetName.equalsIgnoreCase(entryName);
    }

    // ---- Migracion one-time desde mail-data.yml --------------------------------

    private void migrateLegacy(JavaPlugin plugin) {
        File old = new File(plugin.getDataFolder(), "mail-data.yml");
        if (!old.exists()) return;
        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(old);
            int migrated = 0;
            boolean durable = true;
            for (String key : data.getKeys(false)) {
                if (store.get(key) != null) continue; // ya migrado antes
                List<MailEntry> list = new ArrayList<>();
                for (Map<?, ?> m : data.getMapList(key)) {
                    list.add(new MailEntry(
                            UUID.randomUUID().toString(), "",
                            String.valueOf(m.get("from")),
                            String.valueOf(m.get("message")),
                            m.get("time") instanceof Number n ? n.longValue() : 0L,
                            Boolean.TRUE.equals(m.get("read"))));
                }
                if (!list.isEmpty()) {
                    if (!dev.chatalyst.db.StoreCas.putIfAbsentDurably(
                            store, key, serialize(list))) {
                        durable = false;
                        break;
                    }
                    migrated++;
                }
            }
            if (!durable) {
                plugin.getLogger().warning("Mail migration did not reach durable storage; "
                        + "mail-data.yml was preserved and will be retried.");
                return;
            }
            if (old.renameTo(new File(plugin.getDataFolder(), "mail-data.yml.migrated"))) {
                plugin.getLogger().info("Migrated mail-data.yml to the storage backend ("
                        + migrated + " mailboxes).");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not migrate mail-data.yml: " + t.getMessage());
        }
    }
}
