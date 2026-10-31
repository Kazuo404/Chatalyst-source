package dev.chatalyst.db;

import dev.chatalyst.module.Module;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Modulo de persistencia: decide el backend (YAML/SQLite/MySQL segun config) y entrega el
 * {@link StorageManager} al resto de modulos. Se enciende ANTES que cualquier modulo que
 * guarde datos de jugador y se apaga DESPUES de todos ellos (flush final + cierre de DB).
 */
public final class StorageModule implements Module {

    private final JavaPlugin plugin;
    private StorageManager storage;

    public StorageModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public void enable() {
        storage = new StorageManager(plugin);
        storage.init();
    }

    @Override
    public void disable() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    public StorageManager storage() {
        return storage;
    }
}
