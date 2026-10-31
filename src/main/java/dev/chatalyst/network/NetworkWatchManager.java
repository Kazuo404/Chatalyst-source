package dev.chatalyst.network;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;

/** Permission-aware live subscriptions to bounded network-monitor streams. */
public final class NetworkWatchManager implements Listener {

    public enum ToggleResult {
        ENABLED, DISABLED, LIMIT_REACHED, DENIED
    }

    private final ChatalystPlugin plugin;
    private final NetworkWatchRegistry subscriptions = new NetworkWatchRegistry(2);
    private volatile String liveFormat;

    NetworkWatchManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    void load() {
        subscriptions.configure(Math.max(1, Math.min(8, plugin.getConfig().getInt(
                "Network_Admin.Monitor.Max_Live_Subscriptions", 2))));
        liveFormat = plugin.getConfig().getString("Network_Admin.Monitor.Live_Format",
                "<#7B68A0>[<#B56CFF>{server}<#7B68A0>] <#C0B3D9>#{channel} <#FBF2FF>{player}<#C0B3D9>: <#FBF2FF>{message}");
    }

    public int maxSubscriptions() {
        return subscriptions.limit();
    }

    public int count(Player viewer) {
        return subscriptions.count(viewer.getUniqueId());
    }

    public boolean isWatching(Player viewer, String serverId) {
        return subscriptions.isWatching(viewer.getUniqueId(), normalize(serverId));
    }

    public Set<String> watched(Player viewer) {
        return subscriptions.watched(viewer.getUniqueId());
    }

    public ToggleResult toggle(Player viewer, String serverId) {
        String normalized = normalize(serverId);
        return subscriptions.toggle(viewer.getUniqueId(), normalized,
                canViewServer(viewer, normalized));
    }

    public boolean canViewServer(Player viewer, String serverId) {
        if (viewer == null || !viewer.hasPermission("chatalyst.admin.network.monitor")) return false;
        String normalized = normalize(serverId);
        return !normalized.isEmpty() && grants(viewer,
                "chatalyst.admin.network.monitor.server." + normalized,
                "chatalyst.admin.network.monitor.server.*");
    }

    /**
     * El permiso concreto manda sobre el comodin. Con un OR a secas, {@code server.*=true}
     * mas {@code server.privado=false} seguia dejando ver "privado": la denegacion expresa
     * que el dueño puso justo para ese servidor no servia de nada.
     */
    private static boolean grants(Player viewer, String specific, String wildcard) {
        if (viewer.isPermissionSet(specific)) return viewer.hasPermission(specific);
        return viewer.hasPermission(wildcard);
    }

    public boolean canViewEntry(Player viewer, NetworkMonitor.ChatEntry entry) {
        if (!canViewServer(viewer, entry.serverId())) return false;
        String channelId = normalize(entry.channel());
        String specific = "chatalyst.admin.network.monitor.channel." + channelId;
        // Decidido de forma expresa para ESTE canal: se respeta, sea si o no.
        if (viewer.isPermissionSet(specific)) return viewer.hasPermission(specific);
        if (viewer.hasPermission("chatalyst.admin.network.monitor.channel.*")) return true;
        Channel localDefinition = plugin.getChannelManager().byName(entry.channel());
        return localDefinition != null && localDefinition.canUse(viewer);
    }

    void publish(NetworkMonitor.ChatEntry entry) {
        if (subscriptions.isEmpty()) return;
        FoliaTasks.onlinePlayers(plugin,
                viewer -> isWatching(viewer, entry.serverId()) && canViewEntry(viewer, entry),
                viewer -> viewer.sendMessage(render(entry)));
    }

    private net.kyori.adventure.text.Component render(NetworkMonitor.ChatEntry entry) {
        return MM.full(liveFormat
                .replace("{server}", MM.escape(entry.serverId()))
                .replace("{channel}", MM.escape(entry.channel()))
                .replace("{player}", MM.escape(entry.player()))
                .replace("{message}", MM.escape(entry.message())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        subscriptions.remove(event.getPlayer().getUniqueId());
    }

    static String normalize(String value) {
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return clean.substring(0, Math.min(clean.length(), 64));
    }
}
