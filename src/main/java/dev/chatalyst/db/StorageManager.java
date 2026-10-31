package dev.chatalyst.db;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import dev.chatalyst.util.StableServerIdentity;

/**
 * Decide el backend de persistencia segun config (Database.Enabled): base de datos (SQLite/
 * MySQL) o archivos YAML. Entrega un {@link Store} por namespace y los cierra al apagar.
 */
public final class StorageManager {

    private final JavaPlugin plugin;
    private Database database;
    private boolean useSql;
    // Prefijo aplicado a cada namespace. Vacio = datos compartidos por toda la red;
    // "<serverId>:" = cada server guarda lo suyo aparte aunque compartan la misma base de datos.
    private String prefix = "";
    private boolean shared;
    private String serverTag;
    private final Map<String, Store> stores = new ConcurrentHashMap<>();
    private final StorageOperationGate operationGate = new StorageOperationGate();
    private StorageProfile activeProfile;
    private long activeRefreshSeconds;
    // Transporte de red para invalidar caches de otros servers (modo Network_Shared).
    private volatile BiConsumer<String, Collection<String>> invalidationPublisher;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask refreshTask;

    public StorageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        activeProfile = configuredProfile();
        if (activeProfile.enabled()) {
            database = new Database(plugin);
            useSql = database.connect(); // falla cerrado: nunca sustituye una DB por YAML vacio
        }
        // Modo de red: el dueño decide si los servers comparten datos (network) o cada uno
        // los suyos (separacion). Solo aplica con base de datos compartida (MySQL); SQLite y
        // YAML ya son por-servidor. Si separa y no da Server_Id, usamos el nombre del mundo
        // principal / el puerto como identificador para que sea unico sin configurar nada.
        shared = activeProfile.shared();
        // Storage identity is deliberately independent from runtime Proxy/Redis identity.
        // Changing a transport id must never silently rename persistent namespaces.
        String configuredStorageId = plugin.getConfig().getString("Database.Server_Id", "");
        serverTag = activeProfile.serverId();
        migrateLegacyIdentity(configuredStorageId);
        if (useSql && !shared) {
            prefix = serverTag() + ":";
            plugin.getLogger().info("Database mode: per-server (data isolated as '" + serverTag() + "').");
        } else if (useSql) {
            plugin.getLogger().info("Database mode: network-shared (data shared across servers).");
            if (database.isMySql()) startSharedRefresh(configuredRefreshSeconds());
        }
        // Antes de que ningun modulo abra su Store: si no, cargaria el namespace SQL vacio y
        // su primera escritura convertiria los datos del YAML en conflictos.
        if (useSql && plugin.getConfig().getBoolean("Database.Import_Yaml_On_Start", true)) {
            try {
                importYaml(YamlSqlImporter.Options.AUTOMATIC);
            } catch (Exception failure) {
                plugin.getLogger().severe("The YAML -> SQL import could not run; the YAML files"
                        + " are untouched and it will be retried on the next start: "
                        + safeFailure(failure));
            }
        }
    }

    /**
     * Namespaces que el codigo pide con {@link #perServerStore}. El importador necesita
     * saberlo de antemano para dar a cada YAML su namespace SQL real; un test comprueba que
     * esta lista y las llamadas del codigo no se separen.
     */
    static final java.util.Set<String> PER_SERVER_NAMESPACES = java.util.Set.of(
            "market", "market_claims", "market_history", "market_credits",
            "market_negotiation_escrow", "market_settlements", "market_publish_drafts",
            "trade_escrow", "staff_inventories",
            "marriages", "marriage_bank", "marriage_money", "tag-reward-pending");

    /** Namespace SQL real que usaria el Store de {@code namespace} con esta configuracion. */
    static String sqlNamespace(String namespace, boolean shared, String serverTag) {
        if (!shared) return serverTag + ":" + namespace;
        return PER_SERVER_NAMESPACES.contains(namespace) ? serverTag + "_" + namespace : namespace;
    }

    private YamlSqlImporter importer() {
        return new YamlSqlImporter(new java.io.File(plugin.getDataFolder(), "data").toPath(),
                database, serverTag(), ns -> sqlNamespace(ns, shared, serverTag()),
                plugin.getLogger(), java.time.Clock.systemUTC());
    }

    /**
     * Importa (o simula) los YAML pendientes. Con managers vivos se hace bajo el lock
     * exclusivo, como un reload: primero se vuelca lo pendiente, y despues los Store ya
     * abiertos releen sus filas y avisan a las segundas caches.
     */
    public YamlSqlImporter.Report importYaml(YamlSqlImporter.Options options) throws Exception {
        if (!useSql || database == null) {
            throw new IllegalStateException("Database.Enabled is false");
        }
        java.util.concurrent.locks.Lock lock = operationGate.exclusive();
        lock.lock();
        try {
            if (!options.dryRun()) {
                for (Store store : stores.values()) {
                    if (!store.flushNow()) {
                        throw new IllegalStateException("pending storage data could not be"
                                + " flushed; nothing was imported");
                    }
                }
            }
            YamlSqlImporter.Report report = importer().run(options);
            for (YamlSqlImporter.NamespaceResult result : report.namespaces()) {
                if (result.outcome() != YamlSqlImporter.Outcome.IMPORTED
                        || result.inserted() == 0) {
                    continue;
                }
                if (stores.get(result.sqlNamespace()) instanceof SqlStore sql) {
                    try {
                        sql.refreshAllFromDatabaseOrThrow();
                    } catch (RuntimeException failure) {
                        plugin.getLogger().severe("Imported '" + result.sqlNamespace()
                                + "' but its live cache could not be refreshed; restart the"
                                + " server: " + safeFailure(failure));
                    }
                }
            }
            return report;
        } finally {
            lock.unlock();
        }
    }

    /** Estado por namespace para /chatalyst storage status; vacio con backend YAML. */
    public java.util.List<YamlSqlImporter.NamespaceStatus> importStatus() throws Exception {
        if (!useSql || database == null) return java.util.List.of();
        java.util.concurrent.locks.Lock lock = operationGate.shared();
        lock.lock();
        try {
            return importer().status();
        } finally {
            lock.unlock();
        }
    }

    public boolean networkShared() {
        return shared;
    }

    public boolean usingMySql() {
        return useSql && database != null && database.isMySql();
    }

    private void startSharedRefresh(long seconds) {
        if (refreshTask != null) refreshTask.cancel();
        activeRefreshSeconds = seconds;
        // Un namespace cada vez, repartidos por el intervalo: leerlos todos seguidos en cada
        // vuelta ocupa la conexion y deja en fila a todo el que necesite la base de datos.
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        long stepMillis = refreshStepMillis(seconds, stores.size());
        refreshTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            java.util.List<Store> current = java.util.List.copyOf(stores.values());
            if (current.isEmpty()) return;
            int index = Math.floorMod(next.getAndIncrement(), current.size());
            if (current.get(index) instanceof SqlStore sql) sql.refreshAllFromDatabase();
        }, stepMillis, stepMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Pausa entre namespaces para dar la vuelta completa en {@code seconds}. */
    static long refreshStepMillis(long seconds, int namespaces) {
        return Math.max(50L, seconds * 1_000L / Math.max(1, namespaces));
    }

    /**
     * Identificador estable de ESTE servidor (config {@code Database.Server_Id}, o
     * {@code srv<puerto>} si no se fija). Sirve para namespaces que NUNCA deben compartirse
     * entre servers aunque la base de datos sea network-shared — p. ej. el mercado, cuya
     * compra/negociacion es local: si dos servers cargaran los mismos anuncios, el mismo
     * item podria venderse dos veces.
     */
    public String serverTag() {
        if (serverTag == null) {
            serverTag = StableServerIdentity.resolve(plugin,
                    plugin.getConfig().getString("Database.Server_Id", ""));
        }
        return serverTag;
    }

    private void migrateLegacyIdentity(String configuredStorageId) {
        if (!useSql || configuredStorageId != null && !configuredStorageId.isBlank()) return;
        String legacyTag = "srv" + plugin.getServer().getPort();
        String separator = shared ? "_" : ":";
        try {
            int moved = database.migrateIdentityPrefix(
                    legacyTag + separator, serverTag() + separator);
            if (moved > 0) {
                plugin.getLogger().info("Migrated " + moved + " SQL row(s) from legacy server"
                        + " identity '" + legacyTag + "' to '" + serverTag() + "'.");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not migrate SQL data from legacy server identity '"
                    + legacyTag + "' to '" + serverTag() + "'", ex);
        }
    }

    /**
     * Store aislado por servidor. Solo la base de datos network-shared necesita el tag de
     * servidor en el namespace; con YAML (archivo local) o SQL per-server (prefijo de modo)
     * el namespace ya esta aislado, asi que se deja tal cual — evita, por ejemplo, meter el
     * puerto en el nombre del .yml y que un cambio de puerto "pierda" los datos.
     */
    public Store perServerStore(String namespace) {
        return (useSql && shared) ? store(serverTag() + "_" + namespace) : store(namespace);
    }

    public boolean usingDatabase() {
        return useSql;
    }

    /**
     * Siguiente id unico para un contador (p. ej. reportes). Con base de datos es ATOMICO y
     * seguro entre varios servers de la red; sin ella devuelve -1 y el llamador usa su propio
     * contador local (single-server, sin riesgo de colision).
     *
     * @param atLeast piso minimo (el mayor id ya existente al arrancar).
     */
    public int nextId(String name, int atLeast) {
        java.util.concurrent.locks.Lock lock = operationGate.shared();
        lock.lock();
        try {
            if (useSql && database != null) {
                try {
                    // El prefijo aisla el contador por server cuando el modo es de separacion,
                    // asi cada server tiene su propia secuencia de ids.
                    return database.nextCounter(prefix + name, atLeast);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Could not reserve id from the DB: " + ex.getMessage());
                }
            }
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /** Store del namespace (se crea una vez y se reutiliza). El prefijo de modo de red se
     *  aplica de forma transparente, asi los managers no saben nada del aislamiento. */
    public Store store(String namespace) {
        java.util.concurrent.locks.Lock lock = operationGate.shared();
        lock.lock();
        try {
            String ns = prefix + namespace;
            return stores.computeIfAbsent(ns, key -> {
                if (!useSql) return new YamlStore(plugin, key);
                SqlStore sql = new SqlStore(plugin, database, key, operationGate);
                wireInvalidation(key, sql);
                return sql;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aplica en caliente host/puerto/schema/usuario/password/SSL y el intervalo de sondeo.
     * El backend, Network_Shared y Server_Id forman parte de la identidad de cada namespace;
     * cambiarlos con managers vivos podria mezclar dos realidades, asi que se reportan como
     * restart-required. Ante cualquier fallo la conexion y los caches anteriores sobreviven.
     */
    public ReloadResult reloadConfiguredConnection() {
        StorageProfile requested = configuredProfile();
        List<String> restartReasons = activeProfile.restartReasons(requested);
        if (!restartReasons.isEmpty()) {
            return ReloadResult.restartRequired(restartReasons);
        }
        if (!useSql || database == null) return ReloadResult.unchanged();

        boolean connectionChanged = database.configurationChanged();
        long requestedRefresh = configuredRefreshSeconds();
        boolean refreshChanged = shared && database.isMySql()
                && requestedRefresh != activeRefreshSeconds;
        if (!connectionChanged && !refreshChanged) return ReloadResult.unchanged();

        java.util.concurrent.locks.Lock lock = operationGate.exclusive();
        lock.lock();
        try {
            if (connectionChanged) {
                for (Store store : stores.values()) {
                    if (!store.flushNow()) {
                        plugin.getLogger().severe("Database reload aborted: pending storage data"
                                + " could not be flushed; the previous connection was retained.");
                        return ReloadResult.failed();
                    }
                }

                Database.ConnectionSnapshot previous = null;
                try (Database.PreparedReload candidate = database.prepareReload()) {
                    previous = database.activate(candidate);
                    for (Store store : stores.values()) {
                        if (store instanceof SqlStore sql) sql.refreshAllFromDatabaseOrThrow();
                    }
                    database.commit(previous);
                    previous = null;
                } catch (Throwable failure) {
                    if (previous != null) {
                        database.rollback(previous);
                        restoreCachesAfterRollback();
                    }
                    if (database.activeConnectionHealthy()) {
                        plugin.getLogger().warning("Database reload failed; previous connection"
                                + " retained and verified: " + safeFailure(failure));
                    } else {
                        plugin.getLogger().severe("Database reload failed and the previous"
                                + " connection did not pass its health check: "
                                + safeFailure(failure));
                    }
                    return ReloadResult.failed();
                }
            }

            if (shared && database.isMySql()) startSharedRefresh(requestedRefresh);
            activeRefreshSeconds = requestedRefresh;
            return ReloadResult.reloaded(connectionChanged);
        } finally {
            lock.unlock();
        }
    }

    private void restoreCachesAfterRollback() {
        for (Store store : stores.values()) {
            if (!(store instanceof SqlStore sql)) continue;
            try {
                sql.refreshAllFromDatabaseOrThrow();
            } catch (Throwable rollbackFailure) {
                plugin.getLogger().severe("Database rollback restored the old connection but"
                        + " could not restore one cache: " + safeFailure(rollbackFailure));
            }
        }
    }

    private StorageProfile configuredProfile() {
        boolean enabled = plugin.getConfig().getBoolean("Database.Enabled", false);
        String type = plugin.getConfig().getString("Database.Type", "SQLITE");
        String normalizedType = type != null && type.equalsIgnoreCase("MYSQL")
                ? "MYSQL" : "SQLITE";
        boolean configuredShared = plugin.getConfig()
                .getBoolean("Database.Network_Shared", true);
        String configuredId = plugin.getConfig().getString("Database.Server_Id", "");
        // Una identidad configurada tambien debe pasar por el mismo limite/alfabeto que
        // Redis y los fallbacks. Sin esto podia exceder la columna SQL, introducir ':' en
        // namespaces aislados o incluso saltos de linea en logs. resolve() conserva el id
        // explicito saneado y solo crea el archivo estable cuando realmente esta vacio.
        String effectiveId = StableServerIdentity.resolve(plugin, configuredId);
        return new StorageProfile(enabled, normalizedType, configuredShared, effectiveId);
    }

    private long configuredRefreshSeconds() {
        return Math.max(1L, Math.min(86_400L,
                plugin.getConfig().getLong("Database.Shared_Refresh_Seconds", 30)));
    }

    private String safeFailure(Throwable failure) {
        Throwable shown = failure;
        while (shown.getCause() != null && shown.getCause() != shown) shown = shown.getCause();
        String message = shown.getMessage();
        if (message == null || message.isBlank()) message = shown.getClass().getSimpleName();
        String configuredPassword = plugin.getConfig().getString("Database.Password", "");
        if (configuredPassword != null && !configuredPassword.isEmpty()) {
            message = message.replace(configuredPassword, "<redacted>");
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() <= 240 ? message : message.substring(0, 240) + "...";
    }

    /**
     * Conecta el transporte de red que propaga invalidaciones de cache entre servidores.
     * Solo aplica en modo Network_Shared con base de datos: sin esto, un cambio hecho en
     * un server no se ve en los demas hasta reiniciar (cada uno cachea su namespace).
     */
    public void bindNetworkInvalidation(BiConsumer<String, Collection<String>> publisher) {
        if (!useSql || !shared || publisher == null) return;
        this.invalidationPublisher = publisher;
        for (Map.Entry<String, Store> e : stores.entrySet()) {
            if (e.getValue() instanceof SqlStore sql) wireInvalidation(e.getKey(), sql);
        }
        plugin.getLogger().info("Network cache invalidation enabled for shared database.");
    }

    private void wireInvalidation(String namespace, SqlStore store) {
        BiConsumer<String, Collection<String>> publisher = this.invalidationPublisher;
        if (publisher != null) {
            store.setInvalidationPublisher(ids -> publisher.accept(namespace, ids));
        }
    }

    /** Invalidacion llegada de otro server: re-lee esas filas de la base de datos. */
    public void onRemoteInvalidation(String namespace, Collection<String> ids) {
        if (!useSql || !shared) return;
        if (stores.get(namespace) instanceof SqlStore sql) {
            sql.refreshFromDatabase(ids);
        }
    }

    public void shutdown() {
        java.util.concurrent.locks.Lock lock = operationGate.exclusive();
        lock.lock();
        try {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        for (Store s : stores.values()) {
            try {
                if (!s.flushNow()) {
                    plugin.getLogger().severe("A storage namespace could not be durably flushed"
                            + " during shutdown; its pending data remains in memory only.");
                }
            } catch (Throwable t) {
                // Un namespace corrupto no debe impedir guardar los demas ni cerrar JDBC.
                plugin.getLogger().severe("Could not flush a storage namespace during shutdown: "
                        + t.getMessage());
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not close the database cleanly: " + t.getMessage());
            }
        }
        } finally {
            lock.unlock();
        }
    }

    record StorageProfile(boolean enabled, String type, boolean shared, String serverId) {
        List<String> restartReasons(StorageProfile requested) {
            java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
            if (enabled != requested.enabled) reasons.add("Database.Enabled");
            if (!enabled || !requested.enabled) return List.copyOf(reasons);
            if (!type.equals(requested.type)) reasons.add("Database.Type");
            if (shared != requested.shared) reasons.add("Database.Network_Shared");
            if (!serverId.equals(requested.serverId)) reasons.add("Database.Server_Id");
            return List.copyOf(reasons);
        }
    }

    public record ReloadResult(Status status, boolean connectionChanged,
                               List<String> restartReasons) {
        public ReloadResult {
            restartReasons = List.copyOf(restartReasons);
        }

        static ReloadResult unchanged() {
            return new ReloadResult(Status.UNCHANGED, false, List.of());
        }

        static ReloadResult reloaded(boolean connectionChanged) {
            return new ReloadResult(Status.RELOADED, connectionChanged, List.of());
        }

        static ReloadResult restartRequired(List<String> reasons) {
            return new ReloadResult(Status.RESTART_REQUIRED, false, reasons);
        }

        static ReloadResult failed() {
            return new ReloadResult(Status.FAILED, false, List.of());
        }
    }

    public enum Status { UNCHANGED, RELOADED, RESTART_REQUIRED, FAILED }
}
