package dev.chatalyst.config;

import dev.chatalyst.format.ChatFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carga y recarga la configuracion de Chatalyst: config.yml y los formatos de chat.
 * Los formatos se cargan bajo demanda y se cachean por nombre.
 * Los defaults se copian del jar la primera vez y no se sobrescriben.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    // El cache de formatos se SUSTITUYE entero en cada load() (swap del volatile) en vez de
    // limpiarse en sitio, y los escalares son volatile: el chat async que corre durante un
    // /reload ve o toda la config vieja o toda la nueva, nunca una mezcla a medias.
    private volatile Map<String, ChatFormat> formatCache = new ConcurrentHashMap<>();
    private volatile boolean chatEnabled;
    private volatile String defaultFormat = "chat";

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        saveDefault("config.yml");
        // El upgrade por version corre una sola vez al arrancar (onEnable), ANTES de partir el
        // config, para que las migraciones vean todas las secciones en un unico config.yml. Aqui
        // (incluido en /reload) la config ya esta en la version actual, asi que solo se recarga.
        saveDefault("formats/chat.yml");
        saveDefault("formats/staff.yml");
        saveDefault("formats/trade.yml");
        saveDefault("formats/auction.yml");
        saveDefault("formats/pm-sender.yml");
        saveDefault("formats/pm-receiver.yml");
        saveDefault("formats/spy.yml");

        plugin.reloadConfig();

        chatEnabled = plugin.getConfig().getBoolean("Chat.Enabled", true);
        defaultFormat = plugin.getConfig().getString("Chat.Default_Format", "chat");
        // Ultimo: cache nuevo y vacio (lazy-load con la config recien recargada). Quien
        // tenga el cache viejo en la mano sigue leyendo formatos viejos coherentes.
        formatCache = new ConcurrentHashMap<>();
    }

    /** Devuelve el formato pedido (cacheado); cae a chat.yml si no existe. */
    public ChatFormat getFormat(String name) {
        String selected = name == null || name.isEmpty() ? defaultFormat : name;
        String key = safeFormatName(selected);
        return formatCache.computeIfAbsent(key, this::loadFormat);
    }

    public ChatFormat getChatFormat() {
        return getFormat(defaultFormat);
    }

    /** Nombre del formato base de chat (Chat.Default_Format), para los overrides por grupo. */
    public String getDefaultFormatName() {
        return defaultFormat;
    }

    private ChatFormat loadFormat(String name) {
        File file = new File(plugin.getDataFolder(), "formats/" + name + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Format '" + name + "' not found; using chat.yml.");
            file = new File(plugin.getDataFolder(), "formats/chat.yml");
        }
        // Carga ESTRICTA. loadConfiguration convierte un error de sintaxis en un YAML vacio, y
        // de ahi salia un formato sin partes: tras un /chatalyst reload con formats/chat.yml
        // roto, el chat entero dejaba de verse sin que nada dijera por que. Ante un fichero
        // ilegible se conserva el ultimo formato que si se pudo leer.
        try {
            YamlConfiguration yaml = dev.chatalyst.util.YamlFiles.load(file);
            ChatFormat format = ChatFormat.fromConfig(name, yaml);
            lastGood.put(name, format);
            return format;
        } catch (java.io.IOException
                 | org.bukkit.configuration.InvalidConfigurationException broken) {
            plugin.getLogger().severe("Format file " + file.getName() + " is not valid YAML;"
                    + " keeping the last version that loaded. " + broken.getMessage());
            ChatFormat previous = lastGood.get(name);
            return previous != null ? previous
                    : ChatFormat.fromConfig(name, YamlConfiguration.loadConfiguration(file));
        }
    }

    /** Ultimo formato leido bien por nombre; sobrevive a los reloads a proposito. */
    private final Map<String, ChatFormat> lastGood = new ConcurrentHashMap<>();

    static String safeFormatName(String configured) {
        if (configured == null) return "chat";
        String name = configured.trim().toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9_-]{1,64}") ? name : "chat";
    }

    private void saveDefault(String resource) {
        File out = new File(plugin.getDataFolder(), resource);
        if (!out.exists()) {
            plugin.saveResource(resource, false);
        }
    }

    public boolean isChatEnabled() {
        return chatEnabled;
    }
}
