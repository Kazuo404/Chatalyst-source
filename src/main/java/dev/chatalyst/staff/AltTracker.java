package dev.chatalyst.staff;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Posibles cuentas alternas por coincidencia de conexion. Todo se agrupa por el SHA-256 de
 * la IP; la IP en claro se guarda aparte ({@code addr:<hash>}) y solo se enseña a quien tiene
 * {@code chatalyst.staff.ip} — el resto del staff ve un identificador corto del hash.
 * Ojo staff: dos hermanos o un cibercafe comparten conexion — es una PISTA, no una prueba.
 */
public final class AltTracker implements Listener {

    private static final int MAX_NAMES = 10;

    private final JavaPlugin plugin;
    private final Store store;
    // La MISMA instancia para instalar y retirar: cada "this::uuidByName" crea una nueva y
    // el uninstall por identidad no la reconoceria.
    private final java.util.function.Function<String, UUID> nameLookup = this::uuidByName;

    AltTracker(JavaPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("staff_alts");
        dev.chatalyst.nick.OfflineNameIndex.install(nameLookup);
        retentionTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                task -> applyRetention(), 10, 24 * 60, java.util.concurrent.TimeUnit.MINUTES);
    }

    private final io.papermc.paper.threadedregions.scheduler.ScheduledTask retentionTask;

    void stopRetention() {
        retentionTask.cancel();
    }

    /** Borra los datos de conexion pasados de plazo (Staff_Moderation.Alts_Retention_Days). */
    private void applyRetention() {
        long days = plugin.getConfig().getLong("Staff_Moderation.Alts_Retention_Days", 365L);
        if (days <= 0) return;
        java.util.Map<String, String> all = store.all();
        AltRetention.Plan plan = AltRetention.plan(all,
                System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L);
        if (plan.isEmpty()) return;
        plan.updates().forEach((key, value) -> store.compareAndPut(key, all.get(key), value));
        plan.removals().forEach(key -> store.compareAndRemove(key, all.get(key)));
        store.flushNow();
        plugin.getLogger().info("Removed connection data older than " + days + " days ("
                + (plan.updates().size() + plan.removals().size()) + " entries).");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        InetSocketAddress address = player.getAddress();
        String ip = address == null || address.getAddress() == null ? null
                : canonicalIp(address.getAddress().getHostAddress());
        String hash = ip == null ? null : hashIp(ip);
        UUID id = player.getUniqueId();
        String name = player.getName();
        boolean trackAlts = plugin.getConfig().getBoolean("Staff_Moderation.Track_Alts", true);
        // Fuera del hilo del join: con MySQL cada lectura/CAS es un viaje a la base, y ahora
        // un join puede tocar hasta seis claves. Nada de esto bloquea la entrada.
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    task -> record(store, id, name, hash, ip, trackAlts));
        } catch (RuntimeException rejected) {
            record(store, id, name, hash, ip, trackAlts); // apagandose: se escribe aqui mismo
        }
    }

    /**
     * IP en claro de una conexion, o null si se registro antes de guardarlas (entonces solo
     * hay hash). Es dato sensible: quien la enseñe debe exigir {@code chatalyst.staff.ip}.
     */
    public String ipOf(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        String ip = store.get("addr:" + hash);
        return ip == null || ip.isBlank() ? null : ip;
    }

    // ---- Esquema de identidad (dupeip / iphistory / namehistory) -------------------------

    /** Cuanto tiene que pasar para volver a escribir la "ultima vez": un rejoin no escribe. */
    static final long SEEN_GRANULARITY = 6L * 60 * 60 * 1000;
    static final int MAX_CONNECTIONS_PER_ACCOUNT = 64;
    static final int MAX_ACCOUNTS_PER_CONNECTION = 100;
    static final int MAX_NAMES_PER_ACCOUNT = 32;

    /** Una cuenta vista desde alguna de las conexiones consultadas. */
    public record Account(UUID id, String name, long firstSeen, long lastSeen,
                          java.util.Set<String> sharedHashes) {
    }

    /** Conexiones (hash) usadas por la cuenta, mas reciente primero. Incluye las legacy. */
    public List<dev.chatalyst.staff.lookup.SeenList.Seen> connections(UUID account) {
        List<dev.chatalyst.staff.lookup.SeenList.Seen> rows = new ArrayList<>(
                dev.chatalyst.staff.lookup.SeenList.decode(store.get("acc:" + account)));
        String last = lastHash(account);
        // Antes de este esquema solo se guardaba la ULTIMA conexion: se suma sin fechas.
        if (last != null && rows.stream().noneMatch(row -> row.key().equals(last))) {
            rows.add(new dev.chatalyst.staff.lookup.SeenList.Seen(last, "", 0L, 0L));
        }
        return List.copyOf(rows);
    }

    /**
     * Todas las cuentas que han entrado desde CUALQUIERA de las conexiones de {@code target},
     * incluida la propia. Valen las de toda la red (el Store es compartido) y las anteriores
     * a este esquema: esas solo tenian nombre y se resuelven por el indice de nombres.
     */
    public List<Account> accountsSharingWith(UUID target) {
        java.util.Map<UUID, Account> out = new java.util.LinkedHashMap<>();
        for (var connection : connections(target)) {
            for (Account account : accountsOn(connection.key())) {
                out.merge(account.id(), account, AltTracker::mergeAccounts);
            }
        }
        if (!out.containsKey(target)) {
            out.put(target, new Account(target, lastKnownName(target), 0L, 0L,
                    java.util.Set.of()));
        }
        return List.copyOf(out.values());
    }

    /** Cuentas vistas desde una conexion concreta (esquema nuevo + nombres legacy). */
    public List<Account> accountsOn(String hash) {
        java.util.Map<UUID, Account> out = new java.util.LinkedHashMap<>();
        if (hash == null) return List.of();
        for (var row : dev.chatalyst.staff.lookup.SeenList.decode(store.get("conn:" + hash))) {
            UUID id = parseUuid(row.key());
            if (id == null) continue;
            out.put(id, new Account(id, row.label(), row.first(), row.last(),
                    java.util.Set.of(hash)));
        }
        for (String legacyName : parse(store.get("ip:" + hash))) {
            UUID id = lookupName(store, legacyName);
            if (id == null || out.containsKey(id)) continue;
            out.put(id, new Account(id, legacyName, 0L, 0L, java.util.Set.of(hash)));
        }
        return List.copyOf(out.values());
    }

    /** Nombres que ha usado esta cuenta, mas reciente primero. */
    public List<dev.chatalyst.staff.lookup.SeenList.Seen> nameHistory(UUID account) {
        return dev.chatalyst.staff.lookup.SeenList.decode(store.get("names:" + account));
    }

    /** Ultimo nombre con el que se vio la cuenta en la red, o null. */
    public String lastKnownName(UUID account) {
        List<dev.chatalyst.staff.lookup.SeenList.Seen> names = nameHistory(account);
        if (!names.isEmpty()) return names.getFirst().key();
        String cached = plugin == null ? null
                : plugin.getServer().getOfflinePlayer(account).getName();
        return cached == null || cached.isBlank() ? null : cached;
    }

    /** UUID del ultimo que entro en la red con ese nombre (indice compartido), o null. */
    public UUID uuidOf(String name) {
        return lookupName(store, name);
    }

    private static Account mergeAccounts(Account a, Account b) {
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>(a.sharedHashes());
        hashes.addAll(b.sharedHashes());
        long first = a.firstSeen() == 0 ? b.firstSeen()
                : b.firstSeen() == 0 ? a.firstSeen() : Math.min(a.firstSeen(), b.firstSeen());
        boolean bNewer = b.lastSeen() > a.lastSeen();
        return new Account(a.id(), bNewer && !b.name().isBlank() ? b.name() : a.name(), first,
                Math.max(a.lastSeen(), b.lastSeen()), java.util.Set.copyOf(hashes));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Todo lo que un join deja escrito. Estatico sobre el {@link Store} para poder probar la
     * regla que importa: un join que no cambia nada NO escribe nada.
     *
     * <p>Antes cada join hacia dos escrituras durables, y en YAML "durable" es volcar el
     * archivo ENTERO de forma sincrona en el hilo del join — con miles de jugadores vistos,
     * dos reescrituras completas por cada entrada. Ahora solo se escribe lo que cambia, y
     * diferido: nada de esto es dinero ni una sancion; perderlo en un crash cuesta
     * exactamente volver a escribirlo en el siguiente join.
     */
    static void record(Store store, UUID id, String name, String hash, boolean trackAlts) {
        record(store, id, name, hash, null, trackAlts, System.currentTimeMillis());
    }

    static void record(Store store, UUID id, String name, String hash, String ip,
                       boolean trackAlts) {
        record(store, id, name, hash, ip, trackAlts, System.currentTimeMillis());
    }

    static void record(Store store, UUID id, String name, String hash, boolean trackAlts,
                       long now) {
        record(store, id, name, hash, null, trackAlts, now);
    }

    static void record(Store store, UUID id, String name, String hash, String ip,
                       boolean trackAlts, long now) {
        // El indice de nombres va primero y no depende de la conexion: sirve para sancionar
        // por nombre aunque no se haya podido leer la IP.
        indexName(store, id, name);
        // Historial de nombres (/namehistory): identidad, no agrupacion, asi que no depende de
        // Track_Alts. Igual que el resto, solo escribe cuando algo cambia.
        dev.chatalyst.db.StoreCas.update(store, "names:" + id,
                raw -> dev.chatalyst.staff.lookup.SeenList.touch(raw, name, "", now,
                        SEEN_GRANULARITY, MAX_NAMES_PER_ACCOUNT));
        if (hash == null) return;
        // El hash de la ultima conexion se guarda SIEMPRE: es lo que permite /banip por
        // nombre. Track_Alts solo controla la agrupacion de nombres para /check.
        String lastKey = "last:" + id;
        if (!hash.equals(store.get(lastKey))) store.put(lastKey, hash);
        // La IP en claro cuelga de su hash ("addr:<hash>"): el hash sigue siendo la llave de
        // todo (bans, alts, ipmute), asi los registros viejos, que solo tienen hash, siguen
        // valiendo igual y simplemente no tienen IP que enseñar. Solo se guarda si de verdad
        // es la IP de ese hash, y solo se escribe la primera vez (una IP no cambia de hash).
        if (ip != null && hash.equals(hashIp(ip)) && !ip.equals(store.get("addr:" + hash))) {
            store.put("addr:" + hash, ip);
        }
        if (!trackAlts) return;
        // CAS: la clave "ip:<hash>" la COMPARTEN todos los alts de esa conexion. Dos que
        // entren a la vez (o en servers de red distintos con la misma MySQL) harian un
        // list-then-put que perderia uno de los nombres; el read-modify-write atomico lo evita.
        dev.chatalyst.db.StoreCas.update(store, "ip:" + hash, raw -> nextNames(raw, name));
        // Esquema con fechas (/dupeip, /iphistory). La lista "ip:" de arriba se sigue escribiendo
        // para los servidores de la red que aun no tengan esta version.
        dev.chatalyst.db.StoreCas.update(store, "acc:" + id,
                raw -> dev.chatalyst.staff.lookup.SeenList.touch(raw, hash, "", now,
                        SEEN_GRANULARITY, MAX_CONNECTIONS_PER_ACCOUNT));
        dev.chatalyst.db.StoreCas.update(store, "conn:" + hash,
                raw -> dev.chatalyst.staff.lookup.SeenList.touch(raw, id.toString(), name, now,
                        SEEN_GRANULARITY, MAX_ACCOUNTS_PER_CONNECTION));
    }

    /**
     * La lista de nombres de una conexion tras ver entrar a {@code name}, o null si ya era
     * el ultimo (el caso de casi todos los joins: el mismo jugador desde su casa) y no hay
     * nada que escribir.
     */
    static String nextNames(String raw, String name) {
        LinkedHashSet<String> names = parse(raw);
        names.remove(name);
        names.add(name); // al final: orden por ultima vez visto
        while (names.size() > MAX_NAMES) {
            names.remove(names.iterator().next());
        }
        String next = String.join(",", names);
        return next.equals(raw) ? null : next;
    }

    /**
     * Indice nombre a UUID ({@code name:<nombre>}) para resolver a quien ya caduco del usercache.
     * Gana el ULTIMO que entro con ese nombre. {@code nameof:<uuid>} recuerda con que nombre se
     * indexo la cuenta: es la unica forma de saber, cuando vuelve renombrada, que entrada
     * vieja hay que soltar.
     */
    private static void indexName(Store store, UUID id, String name) {
        String key = dev.chatalyst.nick.OfflineNameIndex.key(name);
        if (key == null) return;
        String owner = id.toString();
        String ownKey = "nameof:" + id;
        String previous = store.get(ownKey);
        if (previous != null && !previous.equals(key)) {
            // Condicional: si entre tanto OTRA cuenta cogio el nombre viejo, esa entrada ya
            // es suya y no se toca.
            store.compareAndRemove("name:" + previous, owner);
        }
        if (!owner.equals(store.get("name:" + key))) store.put("name:" + key, owner);
        if (!key.equals(previous)) store.put(ownKey, key);
    }

    private UUID uuidByName(String name) {
        return lookupName(store, name);
    }

    /** UUID del ultimo que entro con ese nombre, o null si nunca se le vio. */
    static UUID lookupName(Store store, String name) {
        String key = dev.chatalyst.nick.OfflineNameIndex.key(name);
        if (key == null) return null;
        String raw = store.get("name:" + key);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException corrupt) {
            return null;
        }
    }

    /** Hash SHA-256 de la ultima conexion conocida de este jugador, o null si nunca entro. */
    public String lastHash(UUID target) {
        String hash = store.get("last:" + target);
        return hash == null || hash.isEmpty() ? null : hash;
    }

    /**
     * Nombres vistos desde CUALQUIERA de las conexiones de este jugador, sin el propio. Antes
     * solo se miraba la ultima: quien cambiaba de casa o de datos moviles "perdia" sus alts.
     */
    public List<String> altsOf(UUID target, String selfName) {
        List<String> out = new ArrayList<>();
        for (Account account : accountsSharingWith(target)) {
            if (account.id().equals(target)) continue;
            String name = account.name();
            if (name == null || name.isBlank() || name.equalsIgnoreCase(selfName)) continue;
            if (out.stream().noneMatch(name::equalsIgnoreCase)) out.add(name);
        }
        // Nombres de la lista vieja cuyo indice ya no existe: se siguen enseñando tal cual,
        // como antes de este esquema.
        for (var connection : connections(target)) {
            for (String name : parse(store.get("ip:" + connection.key()))) {
                if (name.equalsIgnoreCase(selfName)) continue;
                if (out.stream().noneMatch(name::equalsIgnoreCase)) out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Importa el historial de este servidor (logs + Essentials) al registro compartido.
     * Bloquea: llamalo fuera del hilo del servidor. Con {@code force=false} solo corre la
     * primera vez en cada servidor (marca {@code import:v1:<carpeta>} en el Store).
     */
    public synchronized AltImport.Result importHistory(boolean force) {
        java.io.File root = new java.io.File(".").getAbsoluteFile().getParentFile();
        String flag = "import:v1:" + (root == null ? "server" : root.getName());
        if (!force && store.get(flag) != null) return null;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        int[] files = {0};
        List<AltImport.Login> logins = new ArrayList<>(AltImport.readLogs(
                new java.io.File("logs"), zone, this::uuidByName, files));
        logins.addAll(AltImport.readEssentials(new java.io.File("plugins/Essentials/userdata")));
        AltImport.Result result = AltImport.apply(store, logins, files[0]);
        store.put(flag, Long.toString(System.currentTimeMillis()));
        store.flushNow();
        return result;
    }

    void shutdown() {
        dev.chatalyst.nick.OfflineNameIndex.uninstall(nameLookup);
        store.flushNow();
    }

    private static LinkedHashSet<String> parse(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String name : raw.split(",")) {
            if (!name.isBlank()) out.add(name.trim());
        }
        return out;
    }

    /** Hash canonico de una IP para todo el modulo (alts e IP bans usan el mismo). */
    public static String hashIp(String value) {
        return sha256(canonicalIp(value));
    }

    /**
     * Forma canonica de una IP escrita a mano. El login hashea {@code getHostAddress()}
     * ("2001:db8:0:0:0:0:0:1"), pero el staff escribe "2001:db8::1": eran dos hashes y el ban
     * no coincidia nunca. Solo se toca lo que ya es una IP literal (con ':' o cuatro numeros con
     * puntos), asi que nunca se dispara una consulta DNS.
     */
    static String canonicalIp(String value) {
        if (value == null) return null;
        String v = value.trim();
        boolean literal = v.indexOf(':') >= 0 ? v.matches("[0-9a-fA-F:.]+")
                : v.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!literal) return value;
        try {
            return java.net.InetAddress.getByName(v).getHostAddress();
        } catch (java.net.UnknownHostException | SecurityException ex) {
            return value;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }
}
