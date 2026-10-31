package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Vanish;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral, vanish-safe player profiles grouped by proxy backend. */
public final class NetworkPlayerDirectory implements Listener {

    public record Entry(UUID id, String name, String rank, String server, String world,
            String gameMode, int ping, String balance, boolean afk, long seenAt) { }
    public record ServerEntry(String id, int players) { }

    private static final long PRESENCE_TICKS = 200L;
    private static final long EXPIRY_MS = 30_000L;
    private static final long OFFLINE_HISTORY_MS = 120_000L;
    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final StaffDirectoryManager staffDirectory;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final NetworkPresenceTracker presence = new NetworkPresenceTracker();
    private final Object presenceLock = new Object();
    private volatile boolean active;

    public NetworkPlayerDirectory(ChatalystPlugin plugin, ProxyManager proxy,
            StaffDirectoryManager staffDirectory) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.staffDirectory = staffDirectory;
    }

    public void start() {
        active = true;
        pulse();
    }

    public void stop() {
        active = false;
        entries.clear();
        presence.clear();
    }

    public List<ServerEntry> servers(Player viewer) {
        prune();
        TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        entries.values().forEach(entry -> ids.add(entry.server()));
        if (plugin.getNetworkMonitor() != null) {
            plugin.getNetworkMonitor().servers().forEach(server -> ids.add(server.serverId()));
        }
        List<ServerEntry> result = new ArrayList<>();
        for (String id : ids) {
            if (canViewServer(viewer, id)) result.add(new ServerEntry(id, count(id)));
        }
        return List.copyOf(result);
    }

    public List<Entry> players(Player viewer, String server) {
        if (!canViewServer(viewer, server)) return List.of();
        prune();
        return select(entries.values(), server, System.currentTimeMillis() - EXPIRY_MS);
    }

    /** True when a fresh presence packet says this player is on any backend. */
    public boolean isPresent(UUID playerId) {
        prune();
        Entry entry = entries.get(playerId);
        return entry != null && entry.seenAt() >= System.currentTimeMillis() - EXPIRY_MS;
    }

    /** Monotonic token used by freeze guards to detect a reconnect during their grace. */
    long presenceRevision(UUID playerId) {
        return presence.revision(playerId);
    }

    boolean reconnectedAfter(UUID playerId, long revision) {
        return presence.newerThan(playerId, revision);
    }

    static List<Entry> select(Collection<Entry> entries, String server, long cutoff) {
        return entries.stream()
                .filter(entry -> entry.seenAt() >= cutoff)
                .filter(entry -> entry.server().equalsIgnoreCase(server))
                .sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public boolean canViewServer(Player viewer, String server) {
        if (!StaffAccess.has(viewer, "chatalyst.admin.players.directory")) return false;
        return StaffAccess.has(viewer, "chatalyst.admin.players.server.*")
                || StaffAccess.has(viewer, "chatalyst.admin.players.server." + server);
    }

    public void teleport(Player requester, Entry target) {
        if (!canViewServer(requester, target.server())) {
            requester.sendMessage(Lang.get(requester, "common.no-permission"));
            return;
        }
        staffDirectory.teleportPlayer(requester, target.id(), target.name(), target.server());
    }

    public void receive(StaffNetworkCodec.PlayerPresence packet) {
        if (!active) return;
        Entry incoming = new Entry(packet.playerId(), packet.playerName(), packet.rank(),
                packet.server(), packet.world(), packet.gameMode(), packet.ping(),
                packet.balance(), packet.afk(), packet.timestamp());
        synchronized (presenceLock) {
            if (!presence.presence(packet.playerId(), packet.timestamp())) return;
            entries.compute(packet.playerId(), (id, current) -> current == null
                    || current.seenAt() <= packet.timestamp() ? incoming : current);
        }
    }

    public void receive(StaffNetworkCodec.PlayerOffline packet) {
        if (!active) return;
        synchronized (presenceLock) {
            presence.offline(packet.playerId(), packet.timestamp());
            entries.computeIfPresent(packet.playerId(), (id, current) ->
                    current.seenAt() <= packet.timestamp() ? null : current);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        presence.presence(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        FoliaTasks.globalLater(plugin, () -> FoliaTasks.entity(plugin,
                event.getPlayer(), this::publish), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sendOffline(event.getPlayer().getUniqueId());
    }

    private void pulse() {
        if (!active) return;
        FoliaTasks.onlinePlayers(plugin, this::publish);
        FoliaTasks.globalLater(plugin, this::pulse, PRESENCE_TICKS);
    }

    private void publish(Player player) {
        if (!active || !player.isOnline()) return;
        if (Vanish.isVanished(player)) {
            if (entries.containsKey(player.getUniqueId())) sendOffline(player.getUniqueId());
            return;
        }
        Entry entry = profile(player, System.currentTimeMillis());
        synchronized (presenceLock) {
            if (!presence.presence(entry.id(), entry.seenAt())) return;
            entries.put(entry.id(), entry);
        }
        try {
            proxy.forwardStaff(StaffNetworkCodec.encode(new StaffNetworkCodec.PlayerPresence(
                UUID.randomUUID(), entry.seenAt(), entry.id(), entry.name(), entry.rank(),
                entry.server(), entry.world(), entry.gameMode(), entry.ping(), entry.balance(),
                entry.afk())));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not publish player presence: " + ex.getMessage());
        }
    }

    private Entry profile(Player player, long now) {
        String rank = LuckPermsHook.group(player.getUniqueId());
        if (rank.isBlank()) rank = VaultHook.group(player);
        if (rank.isBlank()) rank = "default";
        String balance = VaultHook.hasEconomy()
                ? VaultHook.format(VaultHook.balance(player)) : "-";
        boolean afk = plugin.getAfkManager() != null
                && plugin.getAfkManager().isAfk(player.getUniqueId());
        return new Entry(player.getUniqueId(), player.getName(), limit(rank, 64),
                plugin.getNetworkMonitor().serverId(), limit(player.getWorld().getName(), 64),
                player.getGameMode().name().toLowerCase(java.util.Locale.ROOT), player.getPing(),
                limit(balance, 64), afk, now);
    }

    private void sendOffline(UUID playerId) {
        long now = System.currentTimeMillis();
        synchronized (presenceLock) {
            entries.remove(playerId);
            presence.offline(playerId, now);
        }
        try {
            proxy.forwardStaff(StaffNetworkCodec.encode(new StaffNetworkCodec.PlayerOffline(
                UUID.randomUUID(), now, playerId)));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not publish player departure: " + ex.getMessage());
        }
    }

    private int count(String server) {
        return (int) entries.values().stream()
                .filter(entry -> entry.server().equalsIgnoreCase(server)).count();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        long cutoff = now - EXPIRY_MS;
        entries.values().removeIf(entry -> entry.seenAt() < cutoff);
        presence.pruneBefore(now - OFFLINE_HISTORY_MS);
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
