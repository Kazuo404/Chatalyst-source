package dev.chatalyst.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Importa UNA vez los {@code data/<namespace>.yml} del backend YAML a {@code chatalyst_data}.
 *
 * <p>Reglas que no se negocian, porque detras hay matrimonios, cofres, saldos y bans:
 * <ul>
 *   <li>Solo INSERT: una fila que ya existe en SQL con otro valor se conserva y se informa
 *       como conflicto. Varios servidores importan, uno tras otro, sobre la misma base.</li>
 *   <li>El YAML nunca se toca. Lo importado se marca con una fila en {@code $import} dentro
 *       de la MISMA transaccion (asi "importado" y "copiado" no pueden divergir) y, como
 *       registro legible, en {@code data/.sql-import.json}.</li>
 *   <li>Todo o nada por namespace; se relee lo escrito antes de confirmar para detectar
 *       truncados o charsets que MySQL no rechaza en modo no estricto.</li>
 * </ul>
 */
public final class YamlSqlImporter {

    /** Namespace reservado para las marcas de importacion (como {@code $counter}). */
    static final String MARKER_NAMESPACE = "$import";
    static final String MARKER_FILE = ".sql-import.json";

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .create();

    private final Path dataDir;
    private final Database db;
    private final String serverTag;
    private final UnaryOperator<String> sqlNamespace;
    private final Logger logger;
    private final Clock clock;

    /**
     * @param sqlNamespace traduce el nombre del YAML al namespace SQL REAL que leera ese
     *                     Store en el modo configurado (prefijos de red y de servidor).
     */
    public YamlSqlImporter(Path dataDir, Database db, String serverTag,
                           UnaryOperator<String> sqlNamespace, Logger logger, Clock clock) {
        this.dataDir = dataDir;
        this.db = db;
        this.serverTag = serverTag;
        this.sqlNamespace = sqlNamespace;
        this.logger = logger;
        this.clock = clock;
    }

    /** dryRun: solo planifica. includeChanged: reintenta tambien YAML modificados tras importar. */
    public record Options(boolean dryRun, boolean includeChanged) {
        public static final Options AUTOMATIC = new Options(false, false);
    }

    public enum Outcome { IMPORTED, WOULD_IMPORT, ALREADY_IMPORTED, CHANGED_SKIPPED, EMPTY, IGNORED, FAILED }

    public enum State { PENDING, IMPORTED, CHANGED, EMPTY, INVALID }

    public record NamespaceResult(String yamlNamespace, String sqlNamespace, Outcome outcome,
                                  int yamlRows, int inserted, int equal, List<String> conflictIds,
                                  String problem) {
        public NamespaceResult {
            conflictIds = List.copyOf(conflictIds);
        }

        public int conflicts() {
            return conflictIds.size();
        }
    }

    public record Report(boolean dryRun, List<NamespaceResult> namespaces) {
        public Report {
            namespaces = List.copyOf(namespaces);
        }

        public long count(Outcome outcome) {
            return namespaces.stream().filter(n -> n.outcome() == outcome).count();
        }

        public int conflicts() {
            return namespaces.stream().mapToInt(NamespaceResult::conflicts).sum();
        }
    }

    public record NamespaceStatus(String yamlNamespace, String sqlNamespace, int yamlRows,
                                  int sqlRows, State state, String importedAt, String problem) {
    }

    /** Lo que habia en el YAML: filas en su orden y el hash del archivo tal cual. */
    private record Source(Map<String, String> rows, String sha256) {
    }

    /** Marca guardada en SQL: con que contenido se importo y cuando. */
    private record Marker(String sha256, String importedAt) {
    }

    /** Fallo explicable de un namespace (limites, YAML no soportado...). */
    static final class ImportProblem extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        ImportProblem(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ descubrimiento

    /** Namespaces con archivo en data/, ordenados. Corruptos, copias y no-YAML no cuentan. */
    List<String> discover() throws IOException {
        if (!Files.isDirectory(dataDir)) return List.of();
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(YamlSqlImporter::isNamespaceFile)
                    .map(name -> name.substring(0, name.length() - ".yml".length()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * {@code <ns>.corrupt-<ts>.yml} lo crea YamlStore al apartar un archivo roto y las
     * {@code .bak} son copias manuales: importarlas resucitaria datos que ya se descartaron.
     */
    static boolean isNamespaceFile(String fileName) {
        if (!fileName.endsWith(".yml") || fileName.startsWith(".")) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return fileName.length() > ".yml".length()
                && !LOCAL_FILES.contains(lower)
                && !lower.contains(".corrupt-") && !lower.contains(".bak");
    }

    /**
     * Archivos de data/ que NO son de un Store: los escribe su modulo a proposito solo para
     * este servidor (carteles y murales dependen de mundos que se llaman igual en Lobby y
     * Survival). Llevarlos a la base compartida los haria aparecer en todos los servidores.
     */
    private static final java.util.Set<String> LOCAL_FILES = java.util.Set.of(
            "holograms.yml", "murals.yml");

    // ------------------------------------------------------------------ importacion

    /** Recorre todos los namespaces. Nunca lanza por un namespace concreto: lo informa. */
    public Report run(Options options) throws IOException, SQLException {
        Map<String, Marker> markers = readMarkers();
        // Los limites del esquema REAL: una tabla creada a mano o ya ampliada manda.
        Database.ColumnLimits limits = readLimits();
        List<NamespaceResult> results = new ArrayList<>();
        for (String namespace : discover()) {
            NamespaceResult result = importOne(namespace, markers, options, limits);
            results.add(result);
            log(result, options.dryRun());
        }
        Report report = new Report(options.dryRun(), results);
        logSummary(report);
        return report;
    }

    private Database.ColumnLimits readLimits() throws SQLException {
        try {
            return db.columnLimits();
        } catch (SQLException failure) {
            db.invalidate();
            throw failure;
        }
    }

    private NamespaceResult importOne(String namespace, Map<String, Marker> markers,
                                      Options options, Database.ColumnLimits limits) {
        if (!NAMESPACE.matcher(namespace).matches()) {
            return result(namespace, null, Outcome.IGNORED, 0, "not a storage namespace name");
        }
        String target = sqlNamespace.apply(namespace);
        Source source;
        try {
            source = read(namespace);
        } catch (ImportProblem problem) {
            return result(namespace, target, Outcome.FAILED, 0, problem.getMessage());
        } catch (Exception failure) {
            return result(namespace, target, Outcome.FAILED, 0,
                    "could not read the YAML file: " + describe(failure));
        }
        int rows = source.rows().size();
        if (rows == 0) return result(namespace, target, Outcome.EMPTY, 0, null);

        Marker marker = markers.get(markerId(target));
        if (marker != null) {
            if (marker.sha256().equals(source.sha256())) {
                return result(namespace, target, Outcome.ALREADY_IMPORTED, rows, null);
            }
            if (!options.includeChanged()) {
                return result(namespace, target, Outcome.CHANGED_SKIPPED, rows,
                        "changed after it was imported on " + marker.importedAt());
            }
        }
        try {
            checkLimits(target, source.rows(), limits);
            return transfer(namespace, target, source, marker != null, options.dryRun());
        } catch (ImportProblem problem) {
            return result(namespace, target, Outcome.FAILED, rows, problem.getMessage());
        } catch (Exception failure) {
            db.invalidate();
            return result(namespace, target, Outcome.FAILED, rows,
                    "database error, nothing was written for this namespace: "
                            + describe(failure));
        }
    }

    private Source read(String namespace) throws IOException, ImportProblem {
        byte[] bytes = Files.readAllBytes(dataDir.resolve(namespace + ".yml"));
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException invalid) {
            throw new ImportProblem("invalid YAML: " + describe(invalid));
        }
        Map<String, String> rows = new LinkedHashMap<>();
        for (String id : yaml.getKeys(false)) {
            Object raw = yaml.get(id);
            // YamlStore solo escribe pares planos. Una seccion o lista significa que este
            // archivo no lo escribio un Store (o se edito a mano): copiarlo como
            // "MemorySection[...]" seria basura persistente.
            if (raw instanceof ConfigurationSection || raw instanceof List<?>) {
                throw new ImportProblem("id '" + shortId(id)
                        + "' holds a nested section or list, which is not a storage value");
            }
            // Misma decodificacion que YamlStore: los valores con caracteres de control
            // vuelven como byte[] (!!binary) y son el UTF-8 del texto original.
            String value = YamlStore.text(raw);
            if (value != null) rows.put(id, value);
        }
        return new Source(rows, sha256(bytes));
    }

    static void checkLimits(String target, Map<String, String> rows,
                            Database.ColumnLimits limits) throws ImportProblem {
        if (target.codePointCount(0, target.length()) > limits.namespaceChars()) {
            throw new ImportProblem("SQL namespace '" + target + "' is longer than "
                    + limits.namespaceChars() + " characters; shorten Database.Server_Id");
        }
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String id = row.getKey();
            int length = id.codePointCount(0, id.length());
            if (length > Database.ID_COLUMN_LENGTH) {
                throw new ImportProblem("id '" + shortId(id) + "' has " + length
                        + " characters and the SQL id column holds " + Database.ID_COLUMN_LENGTH
                        + "; it cannot be stored without changing it");
            }
            if (row.getValue().getBytes(StandardCharsets.UTF_8).length > limits.valueBytes()) {
                throw new ImportProblem("the value of id '" + shortId(id) + "' is larger than"
                        + " the SQL value column (" + limits.valueBytes() + " bytes)");
            }
        }
    }

    private NamespaceResult transfer(String namespace, String target, Source source,
                                     boolean replacingMarker, boolean dryRun)
            throws SQLException {
        synchronized (db) {
            Connection c = db.get();
            if (dryRun) {
                Plan plan = plan(source.rows(), readNamespace(c, target, false));
                return new NamespaceResult(namespace, target, Outcome.WOULD_IMPORT,
                        source.rows().size(), plan.inserts().size(), plan.equal(),
                        plan.conflicts(), null);
            }
            boolean oldAutoCommit = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                // FOR UPDATE en MySQL bloquea el rango del namespace: otro servidor que
                // importe a la vez espera en vez de colarse entre la lectura y el INSERT.
                Plan plan = plan(source.rows(), readNamespace(c, target, db.isMySql()));
                insertRows(c, target, plan.inserts());
                verify(c, target, plan.inserts());
                JsonObject marker = writeMarker(c, target, namespace, source, plan,
                        replacingMarker);
                c.commit();
                // Solo tras el commit: el registro local nunca debe adelantarse a los datos.
                recordLocally(markerId(target), marker);
                return new NamespaceResult(namespace, target, Outcome.IMPORTED,
                        source.rows().size(), plan.inserts().size(), plan.equal(),
                        plan.conflicts(), null);
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                try {
                    c.setAutoCommit(oldAutoCommit);
                } catch (SQLException ignored) {
                    // La conexion se invalida en el llamador y la siguiente se reabre.
                }
            }
        }
    }

    private record Plan(Map<String, String> inserts, int equal, List<String> conflicts) {
    }

    private static Plan plan(Map<String, String> yaml, Map<String, String> sql) {
        Map<String, String> inserts = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        int equal = 0;
        for (Map.Entry<String, String> row : yaml.entrySet()) {
            String existing = sql.get(row.getKey());
            if (existing == null && !sql.containsKey(row.getKey())) {
                inserts.put(row.getKey(), row.getValue());
            } else if (row.getValue().equals(existing)) {
                equal++;
            } else {
                conflicts.add(row.getKey());
            }
        }
        return new Plan(inserts, equal, conflicts);
    }

    private static Map<String, String> readNamespace(Connection c, String namespace,
                                                     boolean forUpdate) throws SQLException {
        Map<String, String> rows = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, value FROM chatalyst_data WHERE namespace = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.put(rs.getString(1), rs.getString(2));
            }
        }
        return rows;
    }

    private static void insertRows(Connection c, String namespace, Map<String, String> rows)
            throws SQLException {
        if (rows.isEmpty()) return;
        // INSERT y nunca REPLACE: si otra fila aparecio entre la lectura y la escritura, la
        // PK hace fallar el namespace entero en vez de pisarla.
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> row : rows.entrySet()) {
                ps.setString(1, namespace);
                ps.setString(2, row.getKey());
                ps.setString(3, row.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Relee lo insertado y lo compara caracter a caracter. MySQL no estricto trunca un TEXT
     * largo o cambia por '?' lo que su charset no admite (utf8 de 3 bytes y emojis) sin
     * lanzar nada; aqui eso aborta el namespace en vez de quedar guardado mal.
     */
    private static void verify(Connection c, String namespace, Map<String, String> inserted)
            throws SQLException {
        if (inserted.isEmpty()) return;
        Map<String, String> stored = readNamespace(c, namespace, false);
        for (Map.Entry<String, String> row : inserted.entrySet()) {
            if (!Objects.equals(stored.get(row.getKey()), row.getValue())) {
                throw new SQLException("the database did not store id '" + shortId(row.getKey())
                        + "' exactly (truncated or re-encoded); check the column charset");
            }
        }
    }

    private JsonObject writeMarker(Connection c, String target, String namespace,
                                   Source source, Plan plan, boolean replace)
            throws SQLException {
        JsonObject value = new JsonObject();
        value.addProperty("server", serverTag);
        value.addProperty("yaml", namespace);
        value.addProperty("sqlNamespace", target);
        value.addProperty("rows", source.rows().size());
        value.addProperty("inserted", plan.inserts().size());
        value.addProperty("equal", plan.equal());
        value.addProperty("conflicts", plan.conflicts().size());
        value.addProperty("sha256", source.sha256());
        value.addProperty("importedAt", Instant.now(clock).toString());
        // La primera marca es INSERT: si otro proceso con el mismo Server_Id ya importo este
        // namespace, la PK lo detecta y esta transaccion se deshace entera.
        String sql = replace
                ? "REPLACE INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)"
                : "INSERT INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, MARKER_NAMESPACE);
            ps.setString(2, markerId(target));
            ps.setString(3, value.toString());
            ps.executeUpdate();
        }
        return value;
    }

    private String markerId(String target) {
        return serverTag + "/" + target;
    }

    private Map<String, Marker> readMarkers() throws SQLException {
        Map<String, Marker> markers = new HashMap<>();
        synchronized (db) {
            try {
                Connection c = db.get();
                for (Map.Entry<String, String> row : readNamespace(c, MARKER_NAMESPACE, false)
                        .entrySet()) {
                    try {
                        JsonObject json = JsonParser.parseString(row.getValue()).getAsJsonObject();
                        markers.put(row.getKey(), new Marker(json.get("sha256").getAsString(),
                                json.get("importedAt").getAsString()));
                    } catch (RuntimeException unreadable) {
                        // Una marca ilegible sigue significando "ya importado": sin hash, el
                        // YAML aparece como cambiado y nunca se reimporta solo.
                        markers.put(row.getKey(), new Marker("", "an unknown date"));
                    }
                }
            } catch (SQLException failure) {
                db.invalidate();
                throw failure;
            }
        }
        return markers;
    }

    /**
     * Copia legible de la marca en data/.sql-import.json. Se escribe tras el commit y su
     * fallo solo avisa: la marca que manda es la fila de SQL, atomica con los datos.
     */
    private void recordLocally(String key, JsonObject entry) {
        Path file = dataDir.resolve(MARKER_FILE);
        try {
            synchronized (YamlFiles.lock(file.toFile())) {
                JsonObject root = Files.isRegularFile(file)
                        ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject()
                        : new JsonObject();
                if (!root.has("imports")) root.add("imports", new JsonObject());
                JsonObject imports = root.getAsJsonObject("imports");
                JsonObject withTarget = entry.deepCopy();
                withTarget.addProperty("target", db.targetDescription());
                imports.add(db.targetDescription() + " " + key, withTarget);
                YamlFiles.writeAtomic(file.toFile(), GSON.toJson(root) + System.lineSeparator());
            }
        } catch (IOException | RuntimeException failure) {
            logger.warning("Could not update data/" + MARKER_FILE + " (the import itself is"
                    + " recorded in the database): " + describe(failure));
        }
    }

    // ------------------------------------------------------------------ estado

    public List<NamespaceStatus> status() throws IOException, SQLException {
        Map<String, Marker> markers = readMarkers();
        List<NamespaceStatus> out = new ArrayList<>();
        for (String namespace : discover()) {
            if (!NAMESPACE.matcher(namespace).matches()) continue;
            String target = sqlNamespace.apply(namespace);
            int sqlRows = countRows(target);
            Marker marker = markers.get(markerId(target));
            String importedAt = marker == null ? null : marker.importedAt();
            Source source;
            try {
                source = read(namespace);
            } catch (Exception failure) {
                String problem = failure instanceof ImportProblem
                        ? failure.getMessage() : describe(failure);
                out.add(new NamespaceStatus(namespace, target, -1, sqlRows, State.INVALID,
                        importedAt, problem));
                continue;
            }
            State state;
            if (marker != null) {
                state = marker.sha256().equals(source.sha256()) ? State.IMPORTED : State.CHANGED;
            } else {
                state = source.rows().isEmpty() ? State.EMPTY : State.PENDING;
            }
            out.add(new NamespaceStatus(namespace, target, source.rows().size(), sqlRows, state,
                    importedAt, null));
        }
        return out;
    }

    private int countRows(String namespace) throws SQLException {
        synchronized (db) {
            try {
                Connection c = db.get();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM chatalyst_data WHERE namespace = ?")) {
                    ps.setString(1, namespace);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            } catch (SQLException failure) {
                db.invalidate();
                throw failure;
            }
        }
    }

    // ------------------------------------------------------------------ registro

    private void log(NamespaceResult r, boolean dryRun) {
        String tag = dryRun ? "[dry-run] " : "";
        switch (r.outcome()) {
            case IMPORTED, WOULD_IMPORT -> {
                logger.info(tag + (dryRun ? "Would import" : "Imported") + " YAML '"
                        + r.yamlNamespace() + "' into SQL namespace '" + r.sqlNamespace() + "': "
                        + r.inserted() + " new, " + r.equal() + " identical, " + r.conflicts()
                        + " conflict(s) (" + r.yamlRows() + " row(s) in YAML).");
                // Un conflicto por linea, sin valores: pueden ser inventarios enteros.
                for (String id : r.conflictIds()) {
                    logger.warning(tag + "YAML->SQL conflict in '" + r.sqlNamespace() + "': id '"
                            + safe(id) + "' already has a different value in SQL; the SQL"
                            + " value is kept.");
                }
            }
            case CHANGED_SKIPPED -> logger.warning(tag + "YAML '" + r.yamlNamespace() + "' "
                    + r.problem() + "; it is NOT re-imported automatically. Review it with"
                    + " /chatalyst storage import --dry-run --changed.");
            case IGNORED -> logger.warning(tag + "Ignoring data/" + r.yamlNamespace()
                    + ".yml: " + r.problem() + ".");
            case FAILED -> logger.log(Level.SEVERE, tag + "YAML '" + r.yamlNamespace()
                    + "' was NOT imported into SQL namespace '" + r.sqlNamespace() + "': "
                    + r.problem() + ". The YAML file is untouched and the import will be"
                    + " retried on the next start.");
            case ALREADY_IMPORTED, EMPTY -> {
                // Lo normal en cada arranque tras la primera importacion: sin ruido.
            }
        }
    }

    private void logSummary(Report report) {
        long done = report.count(report.dryRun() ? Outcome.WOULD_IMPORT : Outcome.IMPORTED);
        long failed = report.count(Outcome.FAILED);
        long changed = report.count(Outcome.CHANGED_SKIPPED);
        if (done == 0 && failed == 0 && changed == 0) return;
        String message = (report.dryRun() ? "[dry-run] YAML->SQL import simulation: "
                : "YAML->SQL import finished: ") + done + " namespace(s) "
                + (report.dryRun() ? "would be imported" : "imported") + ", "
                + report.count(Outcome.ALREADY_IMPORTED) + " already imported, " + changed
                + " changed since import (skipped), " + failed + " failed, "
                + report.conflicts() + " conflict(s).";
        if (failed > 0) logger.severe(message);
        else logger.info(message);
    }

    // ------------------------------------------------------------------ utilidades

    private static NamespaceResult result(String namespace, String target, Outcome outcome,
                                          int rows, String problem) {
        return new NamespaceResult(namespace, target, outcome, rows, 0, 0, List.of(), problem);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Ids en el log: sin saltos ni controles que partan o falseen la linea. */
    static String safe(String id) {
        StringBuilder out = new StringBuilder(id.length());
        id.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) out.append(String.format("\\u%04x", cp));
            else out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static String shortId(String id) {
        String clean = safe(id);
        return clean.length() <= 40 ? clean : clean.substring(0, 40) + "...";
    }

    private static String describe(Throwable failure) {
        Throwable shown = failure;
        while (shown.getCause() != null && shown.getCause() != shown) shown = shown.getCause();
        String message = shown.getMessage();
        if (message == null || message.isBlank()) message = shown.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() <= 240 ? message : message.substring(0, 240) + "...";
    }
}
