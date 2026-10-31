package dev.chatalyst.gui;

import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Estado del editor de formatos: quien esta editando que parte de que formato, y el guardado
 * del nuevo texto al YAML. El input llega por chat ({@link FormatEditChatListener}).
 */
public final class FormatEditManager {

    private static final Pattern SAFE_FORMAT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    static final int MAX_MESSAGE_LENGTH = 4_096;

    public record Session(String format, String part) {
    }

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Map<UUID, Session> editing = new ConcurrentHashMap<>();

    public FormatEditManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void startEdit(UUID player, String format, String part) {
        editing.put(player, new Session(format, part));
    }

    public Session getSession(UUID player) {
        return editing.get(player);
    }

    public void endEdit(UUID player) {
        editing.remove(player);
    }

    /**
     * Normalizes a user-supplied format identifier. Format names are identifiers, never paths.
     * Kept package-private for focused unit tests.
     */
    static String normalizeFormatName(String rawFormat) {
        if (rawFormat == null) return null;
        String format = rawFormat.trim().toLowerCase(Locale.ROOT);
        return SAFE_FORMAT_NAME.matcher(format).matches() ? format : null;
    }

    /** True only for a regular YAML file directly inside the formats directory. */
    public boolean formatExists(String format) {
        File file = resolveFormatFile(format);
        return file != null && file.isFile();
    }

    /** Guarda el nuevo Message de una parte en formats/&lt;format&gt;.yml y recarga. */
    public boolean saveMessage(String format, String part, String message) {
        File file = resolveFormatFile(format);
        if (file == null || !file.isFile() || !validMessage(message)) return false;
        try {
            // Carga estricta + escritura atomica bajo el lock del archivo: un formato con
            // error de sintaxis no debe cargarse como vacio y perder sus otras Parts al guardar.
            synchronized (YamlFiles.lock(file)) {
                YamlConfiguration yaml = YamlFiles.load(file);
                yaml.set("Parts." + part + ".Message", message);
                YamlFiles.saveAtomic(yaml, file);
            }
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("Could not save format " + format + ": " + ex.getMessage());
            return false;
        }
        config.load(); // limpia el cache de formatos para que el cambio surta efecto
        return true;
    }

    static boolean validMessage(String message) {
        return message != null && message.codePointCount(0, message.length())
                <= MAX_MESSAGE_LENGTH
                && message.indexOf('\u0000') < 0;
    }

    /**
     * Resolve through canonical paths as a second boundary against traversal and symlinks.
     * Only files whose canonical parent is exactly dataFolder/formats are accepted.
     */
    private File resolveFormatFile(String rawFormat) {
        String format = normalizeFormatName(rawFormat);
        if (format == null) return null;
        try {
            File formatsDir = new File(plugin.getDataFolder(), "formats").getCanonicalFile();
            File file = new File(formatsDir, format + ".yml").getCanonicalFile();
            return formatsDir.equals(file.getParentFile()) ? file : null;
        } catch (IOException ex) {
            return null;
        }
    }
}
