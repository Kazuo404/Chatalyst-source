package dev.chatalyst.network;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.ProxyManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Resolves a stable network identity without changing storage namespaces. */
final class NetworkServerIdentity implements Listener {

    private static final Pattern INVALID_ID = Pattern.compile("[^A-Za-z0-9_.-]");

    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final BiConsumer<String, String> changed;
    private volatile boolean automatic;
    private volatile String serverId = "";

    NetworkServerIdentity(ChatalystPlugin plugin, ProxyManager proxy,
            BiConsumer<String, String> changed) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.changed = changed;
    }

    void load() {
        String mode = plugin.getConfig().getString(
                "Proxy.Server_Discovery.Mode", "auto").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("auto") && !mode.equals("manual")) {
            String warning = "Unknown Proxy.Server_Discovery.Mode '" + mode + "'; using auto.";
            plugin.getLogger().warning(warning);
            mode = "auto";
        }
        automatic = mode.equals("auto");
        serverId = resolveInitialServerId(mode,
                plugin.getConfig().getString("Proxy.Server_Discovery.Manual_Server_Id", ""),
                plugin.getConfig().getString("Database.Server_Id", ""),
                plugin.getConfig().getString("Redis.Server_Id", ""),
                "server-" + plugin.getServer().getPort());
        request();
    }

    String serverId() {
        return serverId;
    }

    void request() {
        if (proxy.isEnabled() && automatic) proxy.requestIdentity();
    }

    void accept(String proxyServerId) {
        if (!automatic) return;
        String detected = sanitize(proxyServerId, "");
        if (detected.isEmpty() || detected.equals(serverId)) return;
        String previous = serverId;
        serverId = detected;
        changed.accept(previous, detected);
        plugin.getLogger().info("Proxy server identity detected: " + detected);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!automatic || !proxy.isEnabled()) return;
        event.getPlayer().getScheduler().runDelayed(plugin, task -> request(), null, 10L);
    }

    static String resolveInitialServerId(String mode, String manualId, String databaseId,
            String redisId, String fallback) {
        String selected = "manual".equalsIgnoreCase(mode)
                ? firstNonBlank(manualId, databaseId, redisId)
                : firstNonBlank(databaseId, redisId);
        return sanitize(selected, fallback);
    }

    static String sanitize(String value, String fallback) {
        String clean = value == null ? "" : INVALID_ID.matcher(value.trim()).replaceAll("-");
        if (clean.isEmpty()) clean = fallback;
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
