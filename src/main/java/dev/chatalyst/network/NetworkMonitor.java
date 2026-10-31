package dev.chatalyst.network;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.NetworkPacketCodec;
import dev.chatalyst.proxy.ProxyManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Estado acotado y thread-safe del monitor administrativo de chat de red. */
public final class NetworkMonitor {

    public record ChatEntry(UUID eventId, long timestamp, String serverId, String channel,
            String player, String message) {
    }

    public record ServerView(String serverId, int players, long lastActivity, int bufferedMessages) {
    }

    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final NetworkChatBuffer buffer = new NetworkChatBuffer(100, 60 * 60_000L);
    private final NetworkWatchManager watches;
    private final NetworkServerIdentity identity;
    private final Map<String, NetworkPacketCodec.ServerStatus> topology = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public NetworkMonitor(ChatalystPlugin plugin, ProxyManager proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.watches = new NetworkWatchManager(plugin);
        this.identity = new NetworkServerIdentity(plugin, proxy, this::identityChanged);
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Network_Admin.Monitor.Enabled", true);
        int historySize = Math.max(20, Math.min(500,
                plugin.getConfig().getInt("Network_Admin.Monitor.History_Per_Server", 100)));
        long retentionMs = retentionMillis(plugin.getConfig().getLong(
                "Network_Admin.Monitor.Retention_Minutes", 60));
        buffer.configure(historySize, retentionMs);
        watches.load();
        String previous = identity.serverId();
        identity.load();
        if (!previous.isEmpty() && !previous.equals(serverId())) topology.remove(previous);
        topology.put(serverId(), new NetworkPacketCodec.ServerStatus(serverId(),
                plugin.getServer().getOnlinePlayers().size()));
    }

    public boolean isEnabled() {
        return enabled && proxy.isEnabled();
    }

    public String serverId() {
        return identity.serverId();
    }

    public NetworkWatchManager watches() {
        return watches;
    }

    public Listener identityListener() {
        return identity;
    }

    public void recordLocal(Player sender, String channel, net.kyori.adventure.text.Component message) {
        if (!isEnabled()) return;
        String plain = PlainTextComponentSerializer.plainText().serialize(message).strip();
        if (plain.isBlank()) return;
        plain = limit(plain, 512);
        NetworkPacketCodec.MonitorChat packet = new NetworkPacketCodec.MonitorChat(
                UUID.randomUUID(), System.currentTimeMillis(), serverId(),
                // El nombre MOSTRADO: el monitor lo lee staff de toda la red que puede no
                // tener permiso de /realname, y el de la cuenta delataba a los anonimos.
                limit(channel, 64),
                limit(dev.chatalyst.nick.NickManager.plainName(sender), 64), plain);
        accept(packet);
        proxy.forwardMonitor(packet);
    }

    public void receive(NetworkPacketCodec.MonitorChat packet) {
        if (!enabled) return;
        accept(packet);
    }

    public void updateTopology(List<NetworkPacketCodec.ServerStatus> servers) {
        // El proxy entrega un snapshot, no un delta. Sustituirlo elimina backends retirados
        // y evita que IDs rotativos crezcan para siempre en la GUI.
        topology.clear();
        for (NetworkPacketCodec.ServerStatus server : servers) {
            topology.put(server.serverId(), server);
            buffer.ensureServer(server.serverId());
        }
        topology.put(serverId(), new NetworkPacketCodec.ServerStatus(serverId(),
                plugin.getServer().getOnlinePlayers().size()));
    }

    public void requestRefresh() {
        if (!isEnabled()) return;
        requestIdentity();
        proxy.requestTopology();
    }

    public void requestIdentity() {
        identity.request();
    }

    /** Applies only a proxy-authored identity; manual mode always keeps its configured id. */
    public void updateIdentity(String detectedServerId) {
        identity.accept(detectedServerId);
    }

    private void identityChanged(String previous, String detected) {
        topology.remove(previous);
        topology.put(detected, new NetworkPacketCodec.ServerStatus(detected,
                plugin.getServer().getOnlinePlayers().size()));
        if (plugin.getSupportConversations() != null) {
            plugin.getSupportConversations().updateServerId(detected);
        }
    }

    public List<ServerView> servers() {
        long now = System.currentTimeMillis();
        topology.put(serverId(), new NetworkPacketCodec.ServerStatus(serverId(),
                plugin.getServer().getOnlinePlayers().size()));
        java.util.Set<String> ids = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ids.addAll(topology.keySet());
        ids.addAll(buffer.serverIds());
        List<ServerView> result = new ArrayList<>();
        for (String id : ids) {
            List<ChatEntry> entries = buffer.entries(id, now);
            ChatEntry newest = entries.isEmpty() ? null : entries.getFirst();
            NetworkPacketCodec.ServerStatus status = topology.get(id);
            result.add(new ServerView(id, status == null ? 0 : status.players(),
                newest == null ? 0L : newest.timestamp(), entries.size()));
        }
        result.sort(Comparator.comparing(ServerView::serverId, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public List<ServerView> servers(Player viewer) {
        List<ServerView> visible = new ArrayList<>();
        for (ServerView server : servers()) {
            if (!watches.canViewServer(viewer, server.serverId())) continue;
            List<ChatEntry> entries = entries(viewer, server.serverId());
            visible.add(new ServerView(server.serverId(), server.players(),
                entries.isEmpty() ? 0L : entries.getFirst().timestamp(), entries.size()));
        }
        return List.copyOf(visible);
    }

    public List<ChatEntry> entries(String requestedServer) {
        String id = NetworkServerIdentity.sanitize(requestedServer, "");
        return buffer.entries(id, System.currentTimeMillis());
    }

    public List<ChatEntry> entries(Player viewer, String requestedServer) {
        if (!watches.canViewServer(viewer, requestedServer)) return List.of();
        return entries(requestedServer).stream().filter(entry -> watches.canViewEntry(viewer, entry))
                .toList();
    }

    private void accept(NetworkPacketCodec.MonitorChat packet) {
        long now = System.currentTimeMillis();
        if (buffer.accept(packet, now)) {
            topology.putIfAbsent(packet.serverId(),
                new NetworkPacketCodec.ServerStatus(packet.serverId(), 0));
            watches.publish(new ChatEntry(packet.eventId(), packet.timestamp(), packet.serverId(),
                packet.channel(), packet.player(), packet.message()));
        }
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    static long retentionMillis(long configuredMinutes) {
        return Math.max(1L, Math.min(43_200L, configuredMinutes)) * 60_000L;
    }
}
