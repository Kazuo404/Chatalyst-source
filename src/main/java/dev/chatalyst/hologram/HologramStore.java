package dev.chatalyst.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los carteles de ESTE servidor, en {@code plugins/Chatalyst/data/holograms.yml}.
 *
 * <p>No van al Store compartido de la red a proposito: Lobby y Survival tienen mundos que se
 * llaman igual ("world"), y un cartel guardado en MySQL apareceria en los dos sitios.
 *
 * <p>Se escribe entero cada vez (son pocos y pequeños), en un temporal que luego se mueve
 * encima: un apagado a mitad de escritura deja el archivo anterior, nunca uno cortado.
 */
final class HologramStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Object writeLock = new Object();

    HologramStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(new File(plugin.getDataFolder(), "data"), "holograms.yml");
    }

    List<Hologram> load() {
        List<Hologram> out = new ArrayList<>();
        if (!file.exists()) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("holograms");
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            Object raw = root.get(id);
            Hologram hologram = raw instanceof ConfigurationSection section
                    ? HoloCodec.decode(id, plain(section)) : null;
            if (hologram == null) {
                plugin.getLogger().warning("Skipped an unreadable hologram in data/holograms.yml: "
                        + id);
                continue;
            }
            out.add(hologram);
        }
        return out;
    }

    /** La instantanea mas reciente que aun no se ha escrito, o null. */
    private final java.util.concurrent.atomic.AtomicReference<List<Hologram>> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Escribe la instantanea fuera del hilo del servidor.
     *
     * <p>Cada tarea escribe la ULTIMA instantanea pedida, no la suya. Dos cambios seguidos (dos
     * clics en el editor) lanzan dos tareas que el pool puede ejecutar en cualquier orden: si
     * cada una escribiera su copia, la vieja podia acabar encima de la nueva y el archivo se
     * quedaba un cambio por detras hasta el siguiente guardado (y para siempre tras un crash).
     */
    void saveAsync(Collection<Hologram> snapshot) {
        pending.set(List.copyOf(snapshot));
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> writePending());
        } catch (RuntimeException shuttingDown) {
            writePending();
        }
    }

    private void writePending() {
        synchronized (writeLock) {
            List<Hologram> latest = pending.getAndSet(null);
            if (latest != null) write(latest);
        }
    }

    /** Escritura inmediata (apagado): lo que hubiera pendiente queda superado por esta. */
    void save(Collection<Hologram> holograms) {
        List<Hologram> copy = List.copyOf(holograms);
        synchronized (writeLock) {
            pending.set(null);
            write(copy);
        }
    }

    private void write(Collection<Hologram> holograms) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Carteles flotantes de este servidor (Chatalyst). Se editan en el juego con",
                "/cartel; si tocas esto a mano, usa /cartel recargar despues."));
        for (Hologram hologram : holograms) {
            yaml.createSection("holograms." + hologram.id(), HoloCodec.encode(hologram));
        }
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                File temp = new File(file.getParentFile(), "holograms.yml.tmp");
                yaml.save(temp);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                plugin.getLogger().warning("Could not save data/holograms.yml: "
                        + failure.getMessage());
            }
        }
    }

    /** Seccion de Bukkit a mapas y listas normales, que es lo que entiende HoloCodec. */
    private static Map<String, Object> plain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, value instanceof ConfigurationSection nested ? plain(nested) : value);
        }
        return out;
    }
}
