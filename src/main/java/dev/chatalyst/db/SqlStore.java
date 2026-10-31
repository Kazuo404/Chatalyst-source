package dev.chatalyst.db;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Backend SQL de un namespace. Cache en memoria (lectura async segura) + escrituras diferidas
 * y coalescidas a la base de datos (batch de cambios cada par de segundos).
 */
public final class SqlStore implements Store {

    private final JavaPlugin plugin;
    private final Database db;
    private final String namespace;
    private final StorageOperationGate gate;
    private final PendingState state = new PendingState();
    /**
     * Pone en fila flush, reconciliacion y CAS de este namespace. Va antes que {@code state}, que
     * va antes que {@code db}: ese orden es fijo. Asi el SQL masivo corre sin {@code state} y un
     * put() desde el hilo principal nunca espera a la base de datos.
     */
    private final Object writeOrder = new Object();
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    // Publica los ids persistidos para que otros servers de la red invaliden su cache
    // (modo Network_Shared). Lo inyecta StorageManager cuando hay transporte de red.
    private volatile Consumer<Collection<String>> invalidationPublisher;
    private final List<Consumer<Collection<String>>> invalidationListeners =
            new CopyOnWriteArrayList<>();

    public SqlStore(JavaPlugin plugin, Database db, String namespace,
                    StorageOperationGate gate) {
        this.plugin = plugin;
        this.db = db;
        this.namespace = namespace;
        this.gate = gate;
        preload();
    }

    private void preload() {
        try {
            synchronized (db) {
                Connection c = db.get();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, value FROM chatalyst_data WHERE namespace = ?")) {
                    ps.setString(1, namespace);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            state.cache.put(rs.getString("id"), rs.getString("value"));
                        }
                    }
                }
            }
        } catch (Exception failure) {
            db.invalidate();
            // Una lectura fallida NO equivale a un namespace vacio. Continuar permitiria
            // que managers de bans/mail/etc. funcionaran sobre un cache falso y luego
            // reemplazaran filas validas al recuperarse la conexion.
            throw new IllegalStateException("Could not load '" + namespace
                    + "' from the database", failure);
        }
    }

    @Override
    public String get(String id) {
        return shared(() -> state.get(id));
    }

    @Override
    public Map<String, String> all() {
        return shared(state::all);
    }

    @Override
    public void put(String id, String value) {
        shared(() -> {
            state.put(id, value);
            scheduleSave();
        });
    }

    @Override
    public void remove(String id) {
        shared(() -> {
            state.remove(id);
            scheduleSave();
        });
    }

    /**
     * CAS contra la fila real de la base de datos. La escritura condicional final es una sola
     * sentencia (UPDATE o INSERT contra la PK), atomica tanto en MySQL como en SQLite.
     * Cualquier cambio diferido local de la misma clave se materializa antes de comparar.
     */
    @Override
    public boolean compareAndPut(String id, String expected, String value) {
        return shared(() -> compareAndPutUnlocked(id, expected, value));
    }

    private boolean compareAndPutUnlocked(String id, String expected, String value) {
        if (id == null || value == null) return false;
        boolean applied = false;
        boolean persistedPending = false;
        try {
            // writeOrder + state cubren cache, colas y operacion SQL. Sin esto, un flush
            // drenado podia cruzarse con el CAS y dejar el cache mas viejo que la fila.
            synchronized (writeOrder) { synchronized (state) {
                synchronized (db) {
                    Connection c = db.get();
                    // StoreCas lee del cache. Si esa lectura incluye un put/remove aun
                    // diferido, materializamos primero ese estado en SQL; de otro modo el
                    // CAS compararia cinco veces contra una fila deliberadamente atrasada.
                    persistedPending = persistPendingLocked(c, id);

                    if (expected == null) {
                        // Insertar solo si no existe: la PK (namespace, id) rechaza duplicados.
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)")) {
                            ps.setString(1, namespace);
                            ps.setString(2, id);
                            ps.setString(3, value);
                            try {
                                applied = ps.executeUpdate() == 1;
                            } catch (java.sql.SQLException insertFailure) {
                                // No toda SQLException es una colision de PK. Solo es un CAS
                                // fallido normal si podemos comprobar que la fila ya existe.
                                if (!readRow(c, id).exists()) throw insertFailure;
                                applied = false;
                            }
                        }
                    } else {
                        applied = exactly(c, id, expected, () -> {
                            try (PreparedStatement ps = c.prepareStatement(
                                    "UPDATE chatalyst_data SET value = ? "
                                            + "WHERE namespace = ? AND id = ? AND value = ?")) {
                                ps.setString(1, value);
                                ps.setString(2, namespace);
                                ps.setString(3, id);
                                ps.setString(4, expected);
                                return ps.executeUpdate() == 1;
                            }
                        });
                    }

                    if (applied) {
                        state.cache.put(id, value);
                        state.dirty.remove(id);
                        state.removed.remove(id);
                    } else {
                        Row row = readRow(c, id);
                        if (row.exists()) state.cache.put(id, row.value());
                        else state.cache.remove(id);
                    }
                }
            } }
        } catch (Throwable t) {
            // Generoso a proposito: este catch tambien atrapa fallos que no son de la
            // conexion, y revalidar de mas solo cuesta un ping. Lo que no puede pasar es
            // quedarse corto — los cinco reintentos del CAS reusarian una conexion muerta.
            db.invalidate();
            plugin.getLogger().warning("compareAndPut failed on '" + namespace + "': " + t.getMessage());
            if (persistedPending) notifyPeers(List.of(id), List.of());
            return false;
        }
        if (applied || persistedPending) notifyPeers(List.of(id), List.of());
        return applied;
    }

    /** DELETE condicional contra la fila real, con la misma serializacion que el CAS de put. */
    @Override
    public boolean compareAndRemove(String id, String expected) {
        return shared(() -> compareAndRemoveUnlocked(id, expected));
    }

    private boolean compareAndRemoveUnlocked(String id, String expected) {
        if (id == null || expected == null) return false;
        boolean applied = false;
        boolean persistedPending = false;
        try {
            synchronized (writeOrder) { synchronized (state) {
                synchronized (db) {
                    Connection c = db.get();
                    persistedPending = persistPendingLocked(c, id);
                    applied = exactly(c, id, expected, () -> {
                        try (PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM chatalyst_data WHERE namespace = ? AND id = ? AND value = ?")) {
                            ps.setString(1, namespace);
                            ps.setString(2, id);
                            ps.setString(3, expected);
                            return ps.executeUpdate() == 1;
                        }
                    });
                    if (applied) {
                        state.cache.remove(id);
                        state.dirty.remove(id);
                        state.removed.remove(id);
                    } else {
                        Row row = readRow(c, id);
                        if (row.exists()) state.cache.put(id, row.value());
                        else state.cache.remove(id);
                    }
                }
            } }
        } catch (Throwable t) {
            db.invalidate();
            plugin.getLogger().warning("compareAndRemove failed on '" + namespace + "': "
                    + t.getMessage());
            if (persistedPending) notifyPeers(List.of(id), List.of());
            return false;
        }
        if (applied) notifyPeers(List.of(), List.of(id));
        else if (persistedPending) notifyPeers(List.of(id), List.of());
        return applied;
    }

    /** Una escritura condicional que devuelve si toco la fila. */
    @FunctionalInterface
    private interface ConditionalWrite {
        boolean run() throws java.sql.SQLException;
    }

    /**
     * Ejecuta {@code write} solo si la fila guarda EXACTAMENTE {@code expected}.
     *
     * <p>En MySQL el {@code value = ?} del CAS compara segun la collation de la columna, que
     * en muchos esquemas no distingue mayusculas ni acentos: un CAS que esperaba "abc" pisaba
     * el "ABC" que otro servidor acababa de escribir. Se bloquea la fila con FOR UPDATE, se
     * compara en Java caracter a caracter y solo entonces se escribe, todo en una
     * transaccion. SQLite ya compara en binario, asi que ahi no hace falta nada.
     */
    private boolean exactly(Connection c, String id, String expected, ConditionalWrite write)
            throws java.sql.SQLException {
        if (!db.isMySql()) return write.run();
        boolean oldAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            boolean applied = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM chatalyst_data WHERE namespace = ? AND id = ? FOR UPDATE")) {
                ps.setString(1, namespace);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && expected.equals(rs.getString(1))) applied = write.run();
                }
            }
            c.commit();
            return applied;
        } catch (java.sql.SQLException failure) {
            try {
                c.rollback();
            } catch (java.sql.SQLException ignored) {
                // La conexion se invalida igualmente en el catch del llamante.
            }
            throw failure;
        } finally {
            try {
                c.setAutoCommit(oldAutoCommit);
            } catch (java.sql.SQLException ignored) {
                // Idem: una conexion que no acepta esto se descarta y se reabre.
            }
        }
    }

    @Override
    public boolean compareAndPutDurably(String id, String expected, String value) {
        return compareAndPut(id, expected, value);
    }

    /**
     * Version contra la fila real del atajo de {@link Store#putDurably}.
     *
     * <p>El de Store da por hecho el trabajo si el CACHE ya tiene ese valor; con dos
     * servidores sobre la misma base, el cache puede ir atrasado (otro servidor cambio la
     * fila y aun no llego la invalidacion) y el put "durable" volvia true sin escribir nada.
     */
    @Override
    public boolean putDurably(String id, String value) {
        if (id == null || value == null) return false;
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            Row row = authoritativeRow(id);
            if (row == null) return false;
            if (row.exists() && value.equals(row.value())) return true;
            if (compareAndPut(id, row.exists() ? row.value() : null, value)) return true;
        }
        return false;
    }

    /** Igual que {@link #putDurably}: la ausencia en el cache no prueba que la fila no este. */
    @Override
    public boolean removeDurably(String id) {
        if (id == null) return false;
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            Row row = authoritativeRow(id);
            if (row == null) return false;
            if (!row.exists()) return true;
            if (compareAndRemove(id, row.value())) return true;
        }
        return false;
    }

    /** La fila tal como esta en la base ahora mismo (y el cache al dia), o null si falla. */
    private Row authoritativeRow(String id) {
        return shared(() -> {
            boolean persistedPending = false;
            Row row;
            try {
                synchronized (writeOrder) { synchronized (state) {
                    synchronized (db) {
                        Connection c = db.get();
                        persistedPending = persistPendingLocked(c, id);
                        row = readRow(c, id);
                        if (row.exists()) state.cache.put(id, row.value());
                        else state.cache.remove(id);
                    }
                } }
            } catch (Throwable t) {
                db.invalidate();
                plugin.getLogger().warning("Could not read '" + id + "' from '" + namespace
                        + "': " + t.getMessage());
                if (persistedPending) notifyPeers(List.of(id), List.of());
                return null;
            }
            if (persistedPending) notifyPeers(List.of(id), List.of());
            return row;
        });
    }

    @Override
    public boolean compareAndRemoveDurably(String id, String expected) {
        return compareAndRemove(id, expected);
    }

    /** Debe llamarse con {@code writeOrder}, {@code state} y {@code db} bloqueados, en ese orden. */
    private boolean persistPendingLocked(Connection c, String id) throws java.sql.SQLException {
        if (state.removed.contains(id)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
                ps.setString(1, namespace);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            state.removed.remove(id);
            state.dirty.remove(id);
            return true;
        }
        if (!state.dirty.contains(id)) return false;
        String pending = state.cache.get(id);
        if (pending == null) {
            throw new java.sql.SQLException("Dirty key '" + id + "' has no cached value");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "REPLACE INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)")) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            ps.setString(3, pending);
            ps.executeUpdate();
        }
        state.dirty.remove(id);
        state.removed.remove(id);
        return true;
    }

    private Row readRow(Connection c, String id) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(true, rs.getString(1)) : new Row(false, null);
            }
        }
    }

    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            try {
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> {
                    savePending.set(false);
                    flushNow();
                }, 2, TimeUnit.SECONDS);
            } catch (Throwable t) {
                // Durante el apagado el scheduler puede estar cerrado. Los ids siguen en
                // dirty/removed y StorageManager hace un flush sincrono antes de cerrar DB.
                savePending.set(false);
                if (plugin.isEnabled()) {
                    plugin.getLogger().warning("Could not schedule save for '" + namespace
                            + "': " + t.getMessage());
                }
            }
        }
    }

    @Override
    public boolean flushNow() {
        return shared(this::flushNowUnlocked);
    }

    private boolean flushNowUnlocked() {
        List<String> upserts;
        List<String> deletes;
        synchronized (writeOrder) {
            Map<String, String> values = new HashMap<>();
            // Se captura bajo state y se escribe sin el: mientras corre el batch, un put() o
            // remove() solo vuelve a marcar la clave y sale en el siguiente flush.
            synchronized (state) {
                if (state.dirty.isEmpty() && state.removed.isEmpty()) return true;
                upserts = drain(state.dirty);
                deletes = drain(state.removed);
                if (upserts.isEmpty() && deletes.isEmpty()) return true;
                for (String id : upserts) {
                    String value = state.cache.get(id);
                    if (value != null) values.put(id, value);
                }
            }
            try {
                writeBatch(values, deletes);
            } catch (Throwable t) {
                db.invalidate();
                requeue(upserts, deletes);
                plugin.getLogger().warning("Could not save '" + namespace + "' to the DB: " + t.getMessage());
                try {
                    scheduleSave();
                } catch (Throwable ignored) {
                    // apagado: el shutdown vuelve a llamar flushNow() de todas formas
                }
                return false;
            }
        }
        notifyPeers(upserts, deletes);
        return true;
    }

    private void writeBatch(Map<String, String> values, List<String> deletes) throws Exception {
        synchronized (db) {
            Connection c = db.get();
            if (!values.isEmpty()) {
                // REPLACE INTO funciona igual en MySQL y SQLite (upsert por PK).
                try (PreparedStatement ps = c.prepareStatement(
                        "REPLACE INTO chatalyst_data (namespace, id, value) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        ps.setString(1, namespace);
                        ps.setString(2, entry.getKey());
                        ps.setString(3, entry.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!deletes.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
                    for (String id : deletes) {
                        ps.setString(1, namespace);
                        ps.setString(2, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
    }

    /**
     * Un fallo de I/O no debe perder escrituras que siguen en cache. Durante el batch el estado
     * pudo cambiar, asi que cada id solo vuelve a su cola si esa intencion sigue vigente: un id
     * borrado no se reescribe, y un id recreado no se borra.
     */
    private void requeue(List<String> upserts, List<String> deletes) {
        synchronized (state) {
            for (String id : upserts) {
                if (state.cache.containsKey(id)) state.dirty.add(id);
            }
            for (String id : deletes) {
                if (!state.cache.containsKey(id)) state.removed.add(id);
            }
        }
    }

    /** La parte de {@link #put} que toca a los hilos de juego, sin el guardado programado. */
    void markPut(String id, String value) {
        state.put(id, value);
    }

    private <T> T shared(Supplier<T> operation) {
        java.util.concurrent.locks.Lock lock = gate.shared();
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private void shared(Runnable operation) {
        java.util.concurrent.locks.Lock lock = gate.shared();
        lock.lock();
        try {
            operation.run();
        } finally {
            lock.unlock();
        }
    }

    /** Extrae de forma atomica (por id) el contenido actual del set concurrente.
     *  Package-private para poder testear la semantica de no-perdida bajo concurrencia. */
    static List<String> drain(Set<String> set) {
        if (set.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : set) {
            if (set.remove(id)) out.add(id);
        }
        return out;
    }

    /** Inyectado por StorageManager cuando hay red compartida con transporte activo. */
    void setInvalidationPublisher(Consumer<Collection<String>> publisher) {
        this.invalidationPublisher = publisher;
    }

    @Override
    public void addInvalidationListener(Consumer<Collection<String>> listener) {
        if (listener != null) invalidationListeners.add(listener);
    }

    private void notifyPeers(List<String> upserts, List<String> deletes) {
        Consumer<Collection<String>> publisher = this.invalidationPublisher;
        if (publisher == null || (upserts.isEmpty() && deletes.isEmpty())) return;
        List<String> ids = new ArrayList<>(upserts.size() + deletes.size());
        ids.addAll(upserts);
        ids.addAll(deletes);
        try {
            publisher.accept(ids);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not broadcast cache invalidation for '"
                    + namespace + "': " + t.getMessage());
        }
    }

    /**
     * Re-lee de la base de datos las filas indicadas (invalidacion llegada de otro server).
     * Las entradas con cambios locales pendientes (dirty/removed) NO se tocan: lo local
     * gana hasta que se persista.
     */
    void refreshFromDatabase(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        shared(() -> refreshFromDatabaseUnlocked(ids));
    }

    private void refreshFromDatabaseUnlocked(Collection<String> ids) {
        List<String> refreshed = new ArrayList<>();
        try {
            synchronized (state) {
                synchronized (db) {
                    Connection c = db.get();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT value FROM chatalyst_data WHERE namespace = ? AND id = ?")) {
                        for (String id : ids) {
                            if (id == null) continue;
                            if (state.dirty.contains(id) || state.removed.contains(id)) continue;
                            ps.setString(1, namespace);
                            ps.setString(2, id);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    state.cache.put(id, rs.getString(1));
                                } else {
                                    state.cache.remove(id);
                                }
                                refreshed.add(id);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            db.invalidate();
            plugin.getLogger().warning("Could not refresh '" + namespace + "' from the DB: "
                    + t.getMessage());
            // Sin return: los ids que SI se releyeron ya cambiaron en el cache, y si no se
            // avisa a los managers con segunda cache (titulos, colores...) se quedan con el
            // valor viejo para siempre: el sondeo de despues ve cache y SQL iguales y calla.
        }
        if (!refreshed.isEmpty()) {
            List<String> immutable = List.copyOf(refreshed);
            for (Consumer<Collection<String>> listener : invalidationListeners) {
                try {
                    listener.accept(immutable);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Cache listener failed for '" + namespace
                            + "': " + t.getMessage());
                }
            }
        }
    }

    /**
     * Reconciliacion completa usada como red de seguridad de Network_Shared. Redis sigue
     * dando invalidacion inmediata, pero este sondeo evita caches eternamente obsoletos si
     * el transporte esta apagado, reiniciandose o pierde un paquete.
     */
    void refreshAllFromDatabase() {
        try {
            refreshAllFromDatabaseOrThrow();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not reconcile '" + namespace
                    + "' from the DB: " + failure.getMessage());
        }
    }

    /** Variante fail-fast usada por el reload transaccional para poder hacer rollback. */
    void refreshAllFromDatabaseOrThrow() {
        shared(this::refreshAllFromDatabaseUnlocked);
    }

    private void refreshAllFromDatabaseUnlocked() {
        List<String> refreshed = new ArrayList<>();
        try {
            // writeOrder impide que corra un flush a la vez: lo drenado y aun sin escribir no
            // esta en dirty, y esta lectura lo revertiria. Lo que se marque durante el SELECT
            // si esta en dirty y la fusion de abajo lo respeta.
            synchronized (writeOrder) {
                Map<String, String> authoritative = db.read(this::readNamespace);
                synchronized (state) {
                Set<String> ids = new java.util.HashSet<>(state.cache.keySet());
                ids.addAll(authoritative.keySet());
                for (String id : ids) {
                    if (state.dirty.contains(id) || state.removed.contains(id)) continue;
                    String next = authoritative.get(id);
                    String previous = state.cache.get(id);
                    if (next == null) state.cache.remove(id);
                    else state.cache.put(id, next);
                    if (!java.util.Objects.equals(previous, next)) refreshed.add(id);
                }
                }
            }
        } catch (Exception failure) {
            db.invalidate();
            throw new IllegalStateException("could not reconcile '" + namespace + "'", failure);
        }
        notifyLocalListeners(refreshed);
    }

    private Map<String, String> readNamespace(Connection c) throws java.sql.SQLException {
        Map<String, String> rows = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, value FROM chatalyst_data WHERE namespace = ?")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return rows;
    }

    private void notifyLocalListeners(Collection<String> refreshed) {
        if (refreshed == null || refreshed.isEmpty()) return;
        List<String> immutable = List.copyOf(refreshed);
        for (Consumer<Collection<String>> listener : invalidationListeners) {
            try {
                listener.accept(immutable);
            } catch (Throwable t) {
                plugin.getLogger().warning("Cache listener failed for '" + namespace
                        + "': " + t.getMessage());
            }
        }
    }

    private record Row(boolean exists, String value) {
    }

    /** Estado mutable de una clave y su intencion pendiente, atomico por operacion. */
    static final class PendingState {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        private final Set<String> dirty = ConcurrentHashMap.newKeySet();
        private final Set<String> removed = ConcurrentHashMap.newKeySet();

        String get(String id) {
            return cache.get(id);
        }

        synchronized Map<String, String> all() {
            return new HashMap<>(cache);
        }

        synchronized void put(String id, String value) {
            cache.put(id, value);
            removed.remove(id);
            dirty.add(id);
        }

        synchronized void remove(String id) {
            cache.remove(id);
            dirty.remove(id);
            removed.add(id);
        }

        synchronized boolean isConsistent(String id) {
            return cache.containsKey(id) ? !removed.contains(id) : !dirty.contains(id);
        }
    }
}
