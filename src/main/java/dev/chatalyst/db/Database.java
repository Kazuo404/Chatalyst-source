package dev.chatalyst.db;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conexion a la base de datos (SQLite local o MySQL/MariaDB remoto). Una unica conexion,
 * sincronizada, con reconexion automatica. Los drivers los descarga Paper via 'libraries'.
 * Tabla unica clave-valor: chatalyst_data(namespace, id, value).
 */
public final class Database {

    /** Rule-data keys can reach 101 chars; leave room without making the PK excessively wide. */
    static final int ID_COLUMN_LENGTH = 120;

    /**
     * Cuanto se fia {@link #get()} de una comprobacion reciente. Muy por debajo del
     * {@code wait_timeout} de MySQL (8 h por defecto), asi que no sustituye a la reconexion:
     * solo evita repetir el ping varias veces dentro de la misma rafaga de trabajo.
     */
    private static final long ALIVE_WINDOW_NANOS = 30_000_000_000L;

    private final JavaPlugin plugin;
    private final java.util.function.LongSupplier clock;
    private String url;
    private String user;
    private String password;
    private boolean mysql;
    private Connection connection;
    private ConnectionSettings settings;
    /** Instante de la ultima comprobacion real; 0 = sin comprobar. */
    private long validatedAt;

    public Database(JavaPlugin plugin) {
        this(plugin, System::nanoTime);
    }

    Database(JavaPlugin plugin, java.util.function.LongSupplier clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    /** Constructor package-private para probar commit/rollback sin abrir JDBC real. */
    Database(JavaPlugin plugin, ConnectionSettings initial, Connection connection) {
        this(plugin, initial, connection, System::nanoTime);
    }

    /** Igual, con reloj inyectado para probar la ventana de {@link #get()} sin esperar. */
    Database(JavaPlugin plugin, ConnectionSettings initial, Connection connection,
             java.util.function.LongSupplier clock) {
        this.plugin = plugin;
        this.clock = clock;
        install(initial, connection);
    }

    private final Object readerOrder = new Object();
    private final java.util.concurrent.atomic.AtomicReference<Connection> reader =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong readerGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    public boolean connect() {
        Connection candidate = null;
        try {
            ConnectionSettings configured = configuredSettings();
            loadDriver(configured.mysql());
            candidate = open(configured);
            createTable(candidate, configured.mysql(), plugin.getLogger());
            install(configured, candidate);
            candidate = null;
            plugin.getLogger().info("Database connected (" + (mysql ? "MySQL" : "SQLite") + ").");
            return true;
        } catch (Throwable t) {
            closeQuietly(candidate);
            close();
            // Database.Enabled expresa una eleccion autoritativa. Cambiar silenciosamente a
            // YAML crea una segunda realidad vacia y puede sobrescribir datos cuando MySQL
            // vuelva; es mas seguro impedir el arranque y conservar la fuente original.
            throw new IllegalStateException("Could not initialize the configured database", t);
        }
    }

    private void open() throws SQLException {
        connection = open(settings);
    }

    /** Una lectura contra la base. */
    @FunctionalInterface
    interface Read<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Lectura masiva sin ocupar la conexion de escritura. En MySQL va por una segunda conexion,
     * para que un SELECT de un namespace entero no deje en fila los comandos que esperan una
     * escritura. SQLite tiene un solo escritor y sus lecturas son locales: comparte la principal.
     */
    <T> T read(Read<T> query) throws SQLException {
        if (!mysql) {
            synchronized (this) {
                return query.run(get());
            }
        }
        synchronized (readerOrder) {
            try {
                return query.run(readerConnection());
            } catch (SQLException failure) {
                dropReader();
                throw failure;
            }
        }
    }

    private Connection readerConnection() throws SQLException {
        Connection current = reader.get();
        if (current != null && !current.isClosed() && current.isValid(2)) return current;
        closeQuietly(reader.getAndSet(null));
        while (true) {
            long generation = readerGeneration.get();
            Connection opened = open(activeSettings());
            // Un reload puede cambiar las credenciales mientras se abre: esa conexion no vale.
            if (generation == readerGeneration.get()) {
                reader.set(opened);
                return opened;
            }
            closeQuietly(opened);
        }
    }

    private void dropReader() {
        readerGeneration.incrementAndGet();
        closeQuietly(reader.getAndSet(null));
    }

    private static Connection open(ConnectionSettings target) throws SQLException {
        return target.mysql()
                ? DriverManager.getConnection(target.url(), target.user(), target.password())
                : DriverManager.getConnection(target.url());
    }

    private static void loadDriver(boolean mysql) throws ClassNotFoundException {
        Class.forName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
    }

    private ConnectionSettings configuredSettings() {
        String type = plugin.getConfig().getString("Database.Type", "SQLITE");
        boolean configuredMysql = type.equalsIgnoreCase("MYSQL");
        if (configuredMysql) {
            String host = plugin.getConfig().getString("Database.Host", "localhost");
            int port = plugin.getConfig().getInt("Database.Port", 3306);
            String schema = plugin.getConfig().getString("Database.Database", "chatalyst");
            String configuredUser = plugin.getConfig().getString("Database.User", "root");
            String configuredPassword = plugin.getConfig().getString("Database.Password", "");
            // TLS configurable (antes se forzaba useSSL=false, mandando credenciales y
            // datos en claro por la red). PREFERRED usa TLS si el servidor lo ofrece sin
            // romper instalaciones locales; para MySQL remoto usa REQUIRED o superior.
            String sslMode = plugin.getConfig()
                    .getString("Database.SSL_Mode", "PREFERRED")
                    .trim().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("DISABLED", "PREFERRED", "REQUIRED",
                    "VERIFY_CA", "VERIFY_IDENTITY").contains(sslMode)) {
                plugin.getLogger().warning("Unknown Database.SSL_Mode '" + sslMode
                        + "'; using PREFERRED.");
                sslMode = "PREFERRED";
            }
            String configuredUrl = "jdbc:mysql://" + host + ":" + port + "/" + schema
                    + "?sslMode=" + sslMode + "&autoReconnect=true&characterEncoding=utf8";
            return new ConnectionSettings(true, configuredUrl, configuredUser,
                    configuredPassword);
        }

        // La BD SQLite vive junto al resto de datos en data/ (antes en la raiz).
        File dataDir = new File(plugin.getDataFolder(), "data");
        dataDir.mkdirs();
        File file = new File(dataDir, "chatalyst.db");
        // Migra la BD de la ubicacion antigua (raiz) para no perder datos al actualizar.
        File legacy = new File(plugin.getDataFolder(), "chatalyst.db");
        if (!file.exists() && legacy.exists()) {
            try {
                java.nio.file.Files.move(legacy.toPath(), file.toPath());
                plugin.getLogger().info("Moved chatalyst.db into the data/ folder.");
            } catch (java.io.IOException moveFailed) {
                plugin.getLogger().warning("Could not move chatalyst.db into data/; using"
                        + " the old location: " + moveFailed.getMessage());
                file = legacy;
            }
        }
        return new ConnectionSettings(false, "jdbc:sqlite:" + file.getAbsolutePath(), "", "");
    }

    private synchronized void install(ConnectionSettings configured, Connection next) {
        settings = configured;
        mysql = configured.mysql();
        url = configured.url();
        user = configured.user();
        password = configured.password();
        connection = next;
        validatedAt = 0L;
        dropReader();
    }

    /**
     * Devuelve la conexion, reabriendola si se cerro (timeouts de MySQL).
     *
     * <p>{@code isValid} en MySQL no es una comprobacion local: manda un ping al servidor y
     * espera respuesta. Hacerlo en CADA llamada — y este metodo lo llama el preload, los dos
     * CAS y el flush de cada namespace — costaba un viaje de red extra por operacion, dentro
     * del monitor global de esta clase, de modo que el ping de un namespace hacia esperar al
     * flush de todos los demas. Un CAS con contencion lo pagaba hasta cinco veces seguidas
     * ({@link StoreCas#DEFAULT_ATTEMPTS}).
     *
     * <p>Por eso una conexion validada hace muy poco se da por viva sin volver a preguntar.
     * Lo que hace segura esa ventana es {@link #invalidate()}: cualquier operacion que falle
     * la cierra, asi que el siguiente {@code get()} vuelve a comprobar de verdad y reconecta.
     * Sin eso, los cinco reintentos de un CAS reusarian la misma conexion muerta y el
     * reintento —que hoy si se recupera— dejaria de servir para nada.
     */
    public synchronized Connection get() throws SQLException {
        if (connection != null && !connection.isClosed() && withinAliveWindow()) {
            return connection;
        }
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            // Cerrar la conexion invalida pero aun abierta antes de reemplazarla: si no,
            // cada reconexion filtra un socket/sesion en el servidor MySQL hasta su timeout.
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            open();
        }
        validatedAt = clock.getAsLong();
        return connection;
    }

    /**
     * Marca la conexion como no comprobada: el proximo {@link #get()} vuelve a validarla y,
     * si hace falta, reconecta. Lo llama quien ve fallar una operacion, porque una excepcion
     * de SQL es mejor evidencia de que la conexion murio que cualquier ping preventivo.
     */
    synchronized void invalidate() {
        validatedAt = 0L;
    }

    private boolean withinAliveWindow() {
        // validatedAt == 0 significa "sin comprobar todavia", no "comprobada en el instante 0".
        if (validatedAt == 0L) return false;
        return clock.getAsLong() - validatedAt < ALIVE_WINDOW_NANOS;
    }

    /**
     * Ancho de {@code namespace} en MySQL. Con un Server_Id largo los perServerStore pasaban
     * de 64 ({@code server-<uuid>_market_negotiation_escrow} son 69) y MySQL truncaba o
     * rechazaba la fila.
     */
    static final int MYSQL_NAMESPACE_LENGTH = 128;
    /** MEDIUMTEXT (16 MB): un TEXT (64 KB) no basta para un cofre lleno de shulkers. */
    static final long MYSQL_MEDIUMTEXT_BYTES = 16_777_215L;

    /** Package-private y estatico para que los tests creen el mismo esquema en SQLite real. */
    static void createTable(Connection candidate, boolean candidateMysql) throws SQLException {
        createTable(candidate, candidateMysql, null);
    }

    static void createTable(Connection candidate, boolean candidateMysql,
                            java.util.logging.Logger logger) throws SQLException {
        // SQLite no aplica longitudes: su esquema se queda como siempre.
        String sql = candidateMysql
                ? "CREATE TABLE IF NOT EXISTS chatalyst_data ("
                + "namespace VARCHAR(" + MYSQL_NAMESPACE_LENGTH + ") NOT NULL, id VARCHAR("
                + ID_COLUMN_LENGTH + ") NOT NULL, value MEDIUMTEXT, "
                + "PRIMARY KEY (namespace, id))"
                : "CREATE TABLE IF NOT EXISTS chatalyst_data ("
                + "namespace VARCHAR(64) NOT NULL, id VARCHAR(" + ID_COLUMN_LENGTH
                + ") NOT NULL, value TEXT, "
                + "PRIMARY KEY (namespace, id))";
        try (Statement st = candidate.createStatement()) {
            st.executeUpdate(sql);
            if (!candidateMysql) return;
            // CREATE IF NOT EXISTS no corrige instalaciones antiguas. No exigimos ALTER en
            // cada arranque: muchos usuarios runtime tienen DML pero no permisos de esquema.
            // Solo se migra una instalacion cuyo metadata confirme que la columna es corta.
            Map<String, ColumnInfo> columns = readColumns(candidate);
            ColumnInfo id = columns.get("id");
            if (id == null) throw new SQLException("Could not inspect chatalyst_data.id metadata");
            if (id.size() < ID_COLUMN_LENGTH) {
                st.executeUpdate("ALTER TABLE chatalyst_data MODIFY id VARCHAR("
                        + ID_COLUMN_LENGTH + ") NOT NULL");
            }
            // namespace y value: si el ALTER falla (sin permiso ALTER, indice demasiado
            // largo en MySQL 5.6...) el plugin sigue funcionando como antes, pero avisando.
            for (String alter : schemaUpgrades(columns)) {
                try {
                    st.executeUpdate(alter);
                } catch (SQLException failure) {
                    if (logger != null) {
                        logger.warning("Could not widen chatalyst_data (" + alter + "): "
                                + failure.getMessage() + ". Run it by hand with a user that has"
                                + " ALTER permission; until then long namespaces and values"
                                + " over the current column size cannot be stored.");
                    }
                }
            }
        }
    }

    /** Lo que dicen los metadatos de una columna: tipo SQL y tamaño declarado. */
    record ColumnInfo(String type, long size) {
    }

    /** Columnas de chatalyst_data por nombre en minusculas. */
    static Map<String, ColumnInfo> readColumns(Connection candidate) throws SQLException {
        java.sql.DatabaseMetaData metadata = candidate.getMetaData();
        String catalog = candidate.getCatalog();
        Map<String, ColumnInfo> out = new java.util.HashMap<>();
        for (String table : new String[]{"chatalyst_data", "CHATALYST_DATA"}) {
            try (ResultSet columns = metadata.getColumns(catalog, null, table, null)) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    String type = columns.getString("TYPE_NAME");
                    if (name == null) continue;
                    out.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), new ColumnInfo(
                            type == null ? "" : type.toUpperCase(java.util.Locale.ROOT),
                            columns.getLong("COLUMN_SIZE")));
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /**
     * ALTER que faltan en MySQL para namespace y value. Solo si los metadatos CONFIRMAN que
     * son cortos: una columna desconocida o ya ancha (LONGTEXT) no se toca.
     */
    static List<String> schemaUpgrades(Map<String, ColumnInfo> columns) {
        List<String> out = new ArrayList<>();
        ColumnInfo namespace = columns.get("namespace");
        if (namespace != null && namespace.size() > 0
                && namespace.size() < MYSQL_NAMESPACE_LENGTH) {
            out.add("ALTER TABLE chatalyst_data MODIFY namespace VARCHAR("
                    + MYSQL_NAMESPACE_LENGTH + ") NOT NULL");
        }
        ColumnInfo value = columns.get("value");
        if (value != null && valueBytes(value) < MYSQL_MEDIUMTEXT_BYTES) {
            out.add("ALTER TABLE chatalyst_data MODIFY value MEDIUMTEXT");
        }
        return out;
    }

    /**
     * Bytes que admite la columna value. Por el nombre del tipo y no por COLUMN_SIZE, que en
     * los TEXT de MySQL depende del driver y del charset. Desconocido = su tamaño declarado.
     */
    static long valueBytes(ColumnInfo value) {
        return switch (value.type()) {
            case "TINYTEXT" -> 255L;
            case "TEXT" -> 65_535L;
            case "MEDIUMTEXT" -> MYSQL_MEDIUMTEXT_BYTES;
            case "LONGTEXT" -> 4_294_967_295L;
            default -> value.size() > 0 ? value.size() : Long.MAX_VALUE;
        };
    }

    /** Limites reales de la tabla con esta conexion; SQLite no aplica ninguno. */
    record ColumnLimits(long namespaceChars, long valueBytes) {
        static final ColumnLimits UNLIMITED = new ColumnLimits(Long.MAX_VALUE, Long.MAX_VALUE);

        static ColumnLimits of(Map<String, ColumnInfo> columns) {
            ColumnInfo namespace = columns.get("namespace");
            ColumnInfo value = columns.get("value");
            return new ColumnLimits(
                    namespace == null || namespace.size() <= 0 ? Long.MAX_VALUE : namespace.size(),
                    value == null ? Long.MAX_VALUE : Database.valueBytes(value));
        }
    }

    /** Lee los limites del esquema activo (el importador los comprueba antes de escribir). */
    synchronized ColumnLimits columnLimits() throws SQLException {
        if (!mysql) return ColumnLimits.UNLIMITED;
        return ColumnLimits.of(readColumns(get()));
    }

    boolean isMySql() {
        return mysql;
    }

    /**
     * Destino sin credenciales ni parametros ("mysql://host:3306/chatalyst" o
     * "sqlite:/ruta/chatalyst.db"). La URL JDBC nunca lleva usuario ni password aqui.
     */
    String targetDescription() {
        String target = url == null ? "" : url;
        if (target.startsWith("jdbc:")) target = target.substring("jdbc:".length());
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    /** True si host/puerto/schema/usuario/password/SSL cambiaron desde la conexion activa. */
    boolean configurationChanged() {
        return !configuredSettings().equals(settings);
    }

    ConnectionSettings activeSettings() {
        return settings;
    }

    /** Verifica (y, si hacia falta, reabre) la conexion activa sin mutar datos. */
    synchronized boolean activeConnectionHealthy() {
        try {
            Connection current = get();
            return current != null && !current.isClosed() && current.isValid(2);
        } catch (SQLException failure) {
            return false;
        }
    }

    /**
     * Abre y valida una conexion candidata sin tocar la conexion activa. El caller decide
     * despues si la activa o la descarta, por lo que credenciales incorrectas nunca tumban
     * un backend que ya estaba funcionando.
     */
    PreparedReload prepareReload() throws Exception {
        ConnectionSettings configured = configuredSettings();
        if (settings != null && configured.mysql() != settings.mysql()) {
            throw new IllegalStateException("database type changes require a server restart");
        }
        loadDriver(configured.mysql());
        Connection candidate = null;
        try {
            candidate = open(configured);
            createTable(candidate, configured.mysql(), plugin.getLogger());
            return new PreparedReload(configured, candidate);
        } catch (Throwable failure) {
            closeQuietly(candidate);
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException("Could not validate the candidate database", failure);
        }
    }

    /** Instala la candidata y conserva la anterior abierta hasta commit o rollback. */
    synchronized ConnectionSnapshot activate(PreparedReload prepared) {
        Connection next = prepared.take();
        ConnectionSnapshot previous = new ConnectionSnapshot(settings, connection);
        install(prepared.settings(), next);
        return previous;
    }

    /** Confirma el cambio cerrando la conexion anterior, que ya no usa ningun SqlStore. */
    void commit(ConnectionSnapshot previous) {
        closeQuietly(previous.connection());
    }

    /** Restaura la conexion anterior y cierra la candidata que no supero el refresco. */
    synchronized void rollback(ConnectionSnapshot previous) {
        Connection rejected = connection;
        install(previous.settings(), previous.connection());
        closeQuietly(rejected);
    }

    static final class PreparedReload implements AutoCloseable {
        private final ConnectionSettings settings;
        private Connection connection;

        PreparedReload(ConnectionSettings settings, Connection connection) {
            this.settings = settings;
            this.connection = connection;
        }

        private ConnectionSettings settings() {
            return settings;
        }

        private Connection take() {
            if (connection == null) throw new IllegalStateException("Candidate already consumed");
            Connection taken = connection;
            connection = null;
            return taken;
        }

        @Override
        public void close() {
            closeQuietly(connection);
            connection = null;
        }
    }

    record ConnectionSnapshot(ConnectionSettings settings, Connection connection) {
    }

    /** El toString deliberadamente no incluye user/password. */
    record ConnectionSettings(boolean mysql, String url, String user, String password) {
        @Override
        public String toString() {
            return mysql ? "MySQL connection settings" : "SQLite connection settings";
        }
    }

    /**
     * Claims rows written with the legacy {@code srv<port>} identity and moves them to the
     * installation identity. The row lock is important on MySQL: two backends that used the
     * same port must not both copy the same escrow into their new namespaces.
     */
    public synchronized int migrateIdentityPrefix(String legacyPrefix, String stablePrefix)
            throws SQLException {
        if (legacyPrefix == null || stablePrefix == null || legacyPrefix.isEmpty()
                || legacyPrefix.equals(stablePrefix)) {
            return 0;
        }
        Connection c = get();
        boolean oldAutoCommit = c.getAutoCommit();
        List<IdentityRow> rows = new ArrayList<>();
        try {
            c.setAutoCommit(false);
            String select = "SELECT namespace, id, value FROM chatalyst_data "
                    + "WHERE namespace LIKE ? ESCAPE '!' "
                    + "OR (namespace = '$counter' AND id LIKE ? ESCAPE '!')"
                    + (mysql ? " FOR UPDATE" : "");
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, escapeLike(legacyPrefix) + "%");
                ps.setString(2, escapeLike(legacyPrefix) + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String namespace = rs.getString(1);
                        String id = rs.getString(2);
                        boolean counter = "$counter".equals(namespace);
                        String source = counter ? id : namespace;
                        if (!source.startsWith(legacyPrefix)) continue;
                        String migrated = stablePrefix + source.substring(legacyPrefix.length());
                        rows.add(new IdentityRow(namespace, id, rs.getString(3),
                                counter ? namespace : migrated, counter ? migrated : id));
                    }
                }
            }

            int moved = 0;
            for (IdentityRow row : rows) {
                if (row.namespace().equals(row.targetNamespace()) && row.id().equals(row.targetId())) {
                    continue;
                }
                if (rowExists(c, row.targetNamespace(), row.targetId())) {
                    throw new SQLException("Identity migration target already exists: "
                            + row.targetNamespace() + "/" + row.targetId());
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)")) {
                    insert.setString(1, row.targetNamespace());
                    insert.setString(2, row.targetId());
                    insert.setString(3, row.value());
                    insert.executeUpdate();
                }
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
                    delete.setString(1, row.namespace());
                    delete.setString(2, row.id());
                    if (delete.executeUpdate() != 1) {
                        throw new SQLException("Legacy identity row changed during migration");
                    }
                }
                moved++;
            }
            c.commit();
            return moved;
        } catch (SQLException failure) {
            try {
                c.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            c.setAutoCommit(oldAutoCommit);
        }
    }

    private static boolean rowExists(Connection c, String namespace, String id)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record IdentityRow(String namespace, String id, String value,
                               String targetNamespace, String targetId) {
    }

    /**
     * Reserva el siguiente valor de un contador atomico en la base de datos. Lee el valor
     * REAL de la DB (no de un cache), asi que ve lo que otros servers de la red ya usaron —
     * los ids de reporte no colisionan aunque varios backends compartan la misma MySQL.
     * Se guarda en un namespace propio ("$counter") para no chocar con el cache de los Store.
     *
     * @param atLeast piso minimo (p. ej. el mayor id ya existente al arrancar).
     * @return el nuevo valor del contador (siempre &gt; atLeast y &gt; el valor previo).
     */
    public synchronized int nextCounter(String name, int atLeast) throws SQLException {
        if (atLeast == Integer.MAX_VALUE) {
            throw new SQLException("Counter exhausted for '" + name + "'");
        }
        if (mysql) {
            return nextMysqlCounter(name, atLeast);
        }
        Connection c = get();
        int current = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM chatalyst_data WHERE namespace = '$counter' AND id = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        current = Integer.parseInt(rs.getString(1));
                    } catch (NumberFormatException ignore) {
                        // valor corrupto: partimos de 0
                    }
                }
            }
        }
        long candidate = (long) Math.max(current, atLeast) + 1L;
        if (candidate > Integer.MAX_VALUE) {
            throw new SQLException("Counter exhausted for '" + name + "'");
        }
        int next = (int) candidate;
        try (PreparedStatement ps = c.prepareStatement(
                "REPLACE INTO chatalyst_data (namespace, id, value) VALUES ('$counter', ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, String.valueOf(next));
            ps.executeUpdate();
        }
        return next;
    }

    /**
     * Incremento atomico entre JVMs para MySQL/MariaDB. LAST_INSERT_ID(expr) guarda el valor
     * en la conexion que ejecuto la sentencia, por lo que otro backend puede incrementar la
     * misma fila inmediatamente sin hacer que ambos reciban el mismo id.
     */
    private int nextMysqlCounter(String name, int atLeast) throws SQLException {
        Connection c = get();
        int first = Math.max(0, atLeast) + 1;
        String sql = "INSERT INTO chatalyst_data (namespace, id, value) "
                + "VALUES ('$counter', ?, LAST_INSERT_ID(?)) "
                + "ON DUPLICATE KEY UPDATE value = LAST_INSERT_ID("
                + "GREATEST(CAST(value AS UNSIGNED), ?) + 1)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, first);
            ps.setInt(3, Math.max(0, atLeast));
            ps.executeUpdate();
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
            if (!rs.next()) throw new SQLException("MySQL did not return the reserved counter");
            long reserved = rs.getLong(1);
            if (reserved <= 0 || reserved > Integer.MAX_VALUE) {
                throw new SQLException("Counter exhausted for '" + name + "': " + reserved);
            }
            return (int) reserved;
        }
    }

    // Sincronizado con get(): un close concurrente con la reconexion automatica podria
    // devolver una conexion recien cerrada o filtrar el socket reabierto.
    public synchronized void close() {
        dropReader();
        closeQuietly(connection);
        connection = null;
        validatedAt = 0L;
    }

    private static void closeQuietly(Connection target) {
        if (target == null) return;
        try {
            if (!target.isClosed()) target.close();
        } catch (SQLException ignored) {
        }
    }
}
