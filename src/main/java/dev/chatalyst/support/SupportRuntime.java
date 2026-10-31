package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.Sanitize;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/** Configuration snapshot and bounded-state maintenance for the support subsystem. */
final class SupportRuntime {

    private final ChatalystPlugin plugin;
    private final SupportConversationState state;
    private final SupportDelivery delivery;
    private final SupportNetworkCoordinator network;
    private volatile String serverId;
    private volatile boolean enabled;
    private volatile long pendingTimeoutMs;
    private volatile long sessionTimeoutMs;
    private volatile int maxMessageLength;
    private ScheduledTask sweepTask;

    SupportRuntime(ChatalystPlugin plugin, SupportConversationState state,
            SupportDelivery delivery, SupportNetworkCoordinator network) {
        this.plugin = plugin;
        this.state = state;
        this.delivery = delivery;
        this.network = network;
        load();
    }

    void load() {
        enabled = plugin.getConfig().getBoolean("Staff_Mentions.Private_Chat.Enabled", true);
        pendingTimeoutMs = seconds("Staff_Mentions.Private_Chat.Pending_Timeout_Seconds",
                300, 30, 1_800) * 1_000L;
        sessionTimeoutMs = seconds("Staff_Mentions.Private_Chat.Session_Timeout_Minutes",
                30, 2, 240) * 60_000L;
        maxMessageLength = Math.max(64, Math.min(512, plugin.getConfig().getInt(
                "Staff_Mentions.Private_Chat.Max_Message_Length", 512)));
        serverId = plugin.getNetworkMonitor() == null ? null : plugin.getNetworkMonitor().serverId();
        if (serverId == null || serverId.isBlank()) serverId = "server-" + plugin.getServer().getPort();
        network.configure(serverId, pendingTimeoutMs);
    }

    void start() {
        stop();
        sweepTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                ignored -> sweep(), 20L * 60L, 20L * 60L);
    }

    void stop() {
        if (sweepTask != null) sweepTask.cancel();
        sweepTask = null;
    }

    boolean enabled() {
        return enabled;
    }

    String serverId() {
        return serverId;
    }

    void updateServerId(String value) {
        if (value == null || value.isBlank() || value.equals(serverId)) return;
        serverId = value;
        network.configure(serverId, pendingTimeoutMs);
    }

    String clean(String raw) {
        String clean = Sanitize.stripInvisible(raw == null ? "" : raw).strip();
        return clean.length() <= maxMessageLength ? clean : clean.substring(0, maxMessageLength);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (var request : state.expirePending(now - pendingTimeoutMs)) {
            if (request.originServer().equalsIgnoreCase(serverId)) {
                delivery.requestExpired(request.userId());
            }
        }
        for (var session : state.expireSessions(now - sessionTimeoutMs)) {
            delivery.closed(session, true);
            network.sendClose(session.id(), session.staffId(), "timeout");
        }
        network.prune(now, sessionTimeoutMs);
    }

    private long seconds(String path, long fallback, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, plugin.getConfig().getLong(path, fallback)));
    }
}
