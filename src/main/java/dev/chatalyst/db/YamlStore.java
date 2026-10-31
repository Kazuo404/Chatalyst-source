package dev.chatalyst.db;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import dev.chatalyst.util.YamlFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend YAML: un archivo por namespace (ej. data/colors.yml) con pares id: valor.
 * Cache en memoria + volcado async coalescido (el patron thread-safe de siempre).
 */
public final class YamlStore implements Store {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();
    private final Object saveLock = new Object();
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    // Cambios hechos y cambios ya en disco, ambos bajo stateLock: reescribir un archivo entero
    // para confirmar algo que ya esta guardado es trabajo perdido en el hilo que espera.
    private long changes;
    private long savedChanges;

    public YamlStore(JavaPlugin plugin, String namespace) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "data");
        dir.mkdirs();
        this.file = new File(dir, namespace + ".yml");
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception ex) {
            File backup = new File(dir, namespace + ".corrupt-" + System.currentTimeMillis() + ".yml");
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupFailure) {
                throw new IllegalStateException("Invalid persistent YAML " + file
                        + " and its backup failed: " + backupFailure.getMessage(), ex);
            }
            throw new IllegalStateException("Invalid persistent YAML " + file
                    + "; preserved as " + backup.getName() + ". Repair it before starting.", ex);
        }
        for (String key : yaml.getKeys(false)) {
            String v = text(yaml.get(key));
            if (v != null) cache.put(key, v);
        }
    }

    /**
     * Valor de una clave como texto. SnakeYAML escribe como {@code !!binary} toda cadena con
     * caracteres de control y la devuelve como {@code byte[]}; {@code getString} le aplicaba
     * {@code toString()} y lo guardado se convertia en "[B@1a2b3c" para siempre. Los bytes son
     * el UTF-8 de la cadena original, asi que se decodifican.
     */
    static String text(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    @Override
    public String get(String id) {
        return cache.get(id);
    }

    @Override
    public Map<String, String> all() {
        synchronized (stateLock) {
            return new HashMap<>(cache);
        }
    }

    @Override
    public void put(String id, String value) {
        synchronized (stateLock) {
            cache.put(id, value);
            changes++;
        }
        scheduleSave();
    }

    @Override
    public void remove(String id) {
        synchronized (stateLock) {
            cache.remove(id);
            changes++;
        }
        scheduleSave();
    }

    /**
     * CAS en memoria: YAML es siempre single-server, asi que el cache ES el backend
     * autoritativo y basta con serializar el chequeo+escritura.
     */
    @Override
    public boolean compareAndPut(String id, String expected, String value) {
        if (id == null || value == null) return false;
        synchronized (stateLock) {
            if (!java.util.Objects.equals(cache.get(id), expected)) return false;
            cache.put(id, value);
            changes++;
        }
        scheduleSave();
        return true;
    }

    @Override
    public boolean compareAndRemove(String id, String expected) {
        if (id == null || expected == null) return false;
        synchronized (stateLock) {
            if (!java.util.Objects.equals(cache.get(id), expected)) return false;
            cache.remove(id);
            changes++;
        }
        scheduleSave();
        return true;
    }

    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            try {
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> {
                    savePending.set(false);
                    flushNow();
                }, 2, TimeUnit.SECONDS);
            } catch (Throwable t) {
                // onDisable puede rechazar tareas nuevas. El flush final de StorageManager
                // persiste el cache; no dejamos que el rechazo aborte la devolucion de escrow.
                savePending.set(false);
                if (plugin.isEnabled()) {
                    plugin.getLogger().warning("Could not schedule save for " + file.getName()
                            + ": " + t.getMessage());
                }
            }
        }
    }

    @Override
    public boolean flushNow() {
        // TODO el volcado (snapshot + escritura) va dentro del lock: si dos flushes corren a
        // la vez, se serializan y el ultimo en escribir siempre lleva el snapshot mas nuevo.
        // (Antes el lock se soltaba antes del write y un flush viejo podia pisar a uno nuevo.)
        synchronized (saveLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            long captured;
            synchronized (stateLock) {
                if (changes == savedChanges) return true;
                captured = changes;
                for (Map.Entry<String, String> e : cache.entrySet()) {
                    yaml.set(e.getKey(), e.getValue());
                }
            }
            try {
                YamlFiles.saveAtomic(yaml, file);
                synchronized (stateLock) {
                    savedChanges = captured;
                }
                return true;
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
                // El cache sigue siendo autoritativo; reintenta tras un fallo temporal de
                // disco en vez de esperar a otra mutacion (que podria no llegar nunca).
                if (plugin.isEnabled()) scheduleSave();
                return false;
            }
        }
    }
}
