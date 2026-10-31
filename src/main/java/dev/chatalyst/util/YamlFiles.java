package dev.chatalyst.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/** Strict, atomic YAML file I/O shared by configuration editors and persistent stores. */
public final class YamlFiles {

    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private YamlFiles() {
    }

    /**
     * Returns the process-wide lock for one file. Callers doing a read-modify-write must hold
     * this lock for the complete transaction, not only for the final write.
     */
    public static Object lock(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        return LOCKS.computeIfAbsent(path, ignored -> new Object());
    }

    /** Loads YAML strictly. Unlike loadConfiguration, syntax errors are never converted to empty. */
    public static YamlConfiguration load(File file)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) yaml.load(file);
        return yaml;
    }

    /** Writes through a sibling temporary file and atomically replaces the destination. */
    public static void saveAtomic(YamlConfiguration yaml, File file) throws IOException {
        writeAtomic(file, yaml.saveToString());
    }

    /** Atomic UTF-8 write used by text-preserving migrations too. */
    public static void writeAtomic(File file, String contents) throws IOException {
        synchronized (lock(file)) {
            Path target = file.toPath().toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getName() + ".", ".tmp");
            try {
                Files.writeString(tmp, contents, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
