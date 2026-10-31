package dev.chatalyst.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable installation identity used when the owner did not configure a network server id. */
public final class StableServerIdentity {

    private static final Pattern INVALID = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final int MAX_ID_LENGTH = 45;

    private StableServerIdentity() {
    }

    /**
     * Returns the explicit id when present; otherwise creates data/server-id.txt once.
     * A port is not an identity: two hosts routinely use the same one.
     */
    public static String resolve(JavaPlugin plugin, String explicit) {
        String configured = sanitize(explicit);
        if (!configured.isEmpty()) return configured;

        File file = new File(new File(plugin.getDataFolder(), "data"), "server-id.txt");
        synchronized (YamlFiles.lock(file)) {
            try {
                if (file.isFile()) {
                    String existing = sanitize(Files.readString(
                            file.toPath(), StandardCharsets.UTF_8));
                    if (!existing.isEmpty()) return existing;
                }
                String generated = "server-" + UUID.randomUUID();
                YamlFiles.writeAtomic(file, generated + System.lineSeparator());
                plugin.getLogger().warning("No stable server id was configured; generated '"
                        + generated + "' in data/server-id.txt. Copy this file when moving "
                        + "this backend so isolated SQL/Redis state keeps its identity.");
                return generated;
            } catch (IOException ex) {
                throw new IllegalStateException("Could not persist a unique server identity at "
                        + file.getAbsolutePath(), ex);
            }
        }
    }

    public static String sanitize(String value) {
        String clean = value == null ? "" : INVALID.matcher(value.trim()).replaceAll("-");
        return clean.length() <= MAX_ID_LENGTH ? clean : clean.substring(0, MAX_ID_LENGTH);
    }
}
