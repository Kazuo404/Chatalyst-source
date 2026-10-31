package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.support.SupportConversationManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral network staff presence and permission-checked teleport handoff. */
public final class StaffDirectoryManager implements Listener {

    public record Entry(UUID id, String name, String tier, String server, String world,
            long seenAt) { }

    private static final long PRESENCE_TICKS = 200L;
    private static final long EXPIRY_MS = 30_000L;
    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final SupportConversationManager support;
    private final NetworkTeleportService teleports;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean active;

    public StaffDirectoryManager(ChatalystPlugin plugin, ProxyManager proxy,
            SupportConversationManager support) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.support = support;
        this.teleports = new NetworkTeleportService(plugin, proxy, support);
    }

    public void start() {
        active = true;
        pulse();
    }

    public void stop() {
        active = false;
        entries.clear();
    }

    public boolean available() {
        return proxy.usesNativeStaffBridge();
    }

    public List<Entry> snapshot() {
        long cutoff = System.currentTimeMillis() - EXPIRY_MS;
        entries.values().removeIf(entry -> entry.seenAt() < cutoff);
        return entries.values().stream()
                .sorted(Comparator.comparingInt((Entry entry) -> tierIndex(entry.tier()))
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void teleport(Player requester, Entry target) {
        teleports.request(requester, target.id(), target.name(), target.server(), "staff");
    }

    public void teleportPlayer(Player requester, UUID targetId, String targetName, String server) {
        teleports.request(requester, targetId, targetName, server, "player");
    }

    public void receive(StaffNetworkCodec.Presence packet) {
        Entry incoming = new Entry(packet.playerId(), packet.playerName(), packet.tier(),
                packet.server(), packet.world(), packet.timestamp());
        entries.compute(packet.playerId(), (id, current) -> current == null
                || current.seenAt() <= packet.timestamp() ? incoming : current);
    }

    public void receive(StaffNetworkCodec.Offline packet) {
        entries.computeIfPresent(packet.playerId(), (id, current) ->
                current.seenAt() <= packet.timestamp() ? null : current);
    }

    public void arrive(StaffNetworkCodec.TeleportArrival packet) {
        teleports.arrive(packet);
    }

    public void result(StaffNetworkCodec.TeleportResult packet) {
        teleports.result(packet);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        FoliaTasks.globalLater(plugin, () -> FoliaTasks.entity(plugin,
                event.getPlayer(), this::publish), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        entries.remove(event.getPlayer().getUniqueId());
        sendOffline(event.getPlayer().getUniqueId());
    }

    private void pulse() {
        if (!active) return;
        FoliaTasks.onlinePlayers(plugin, this::publish);
        FoliaTasks.globalLater(plugin, this::pulse, PRESENCE_TICKS);
    }

    private void publish(Player player) {
        if (!support.isStaff(player)) return;
        long now = System.currentTimeMillis();
        Entry entry = new Entry(player.getUniqueId(), player.getName(), support.tier(player),
                plugin.getNetworkMonitor().serverId(), player.getWorld().getName(), now);
        entries.put(entry.id(), entry);
        try {
            proxy.forwardStaff(StaffNetworkCodec.encode(new StaffNetworkCodec.Presence(
                UUID.randomUUID(), now, entry.id(), entry.name(), entry.tier(),
                entry.server(), entry.world())));
        } catch (IOException ignored) {
        }
    }

    private void sendOffline(UUID playerId) {
        try {
            proxy.forwardStaff(StaffNetworkCodec.encode(new StaffNetworkCodec.Offline(
                UUID.randomUUID(), System.currentTimeMillis(), playerId)));
        } catch (IOException ignored) {
        }
    }

    private int tierIndex(String tier) {
        int index = support.tiers().names().indexOf(tier);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
