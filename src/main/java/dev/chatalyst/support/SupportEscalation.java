package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.SupportPriorityPacketCodec;
import dev.chatalyst.util.FoliaTasks;

import java.util.UUID;

/** Advances unclaimed requests through the configured staff hierarchy. */
final class SupportEscalation {

    private final ChatalystPlugin plugin;
    private final SupportConversationState state;
    private final SupportDelivery delivery;
    private final SupportNetworkCoordinator network;
    private final SupportTierPolicy tiers;
    private volatile long delayTicks;

    SupportEscalation(ChatalystPlugin plugin, SupportConversationState state,
            SupportDelivery delivery, SupportNetworkCoordinator network,
            SupportTierPolicy tiers) {
        this.plugin = plugin;
        this.state = state;
        this.delivery = delivery;
        this.network = network;
        this.tiers = tiers;
        load();
    }

    void load() {
        long seconds = Math.max(3L, Math.min(60L, plugin.getConfig().getLong(
                "Staff_Mentions.Priority.Escalation_Seconds", 8L)));
        delayTicks = seconds * 20L;
    }

    void start(SupportConversationState.Request request) {
        String stage = tiers.requestedTier(request.group());
        delivery.request(request, stage);
        network.sendRequest(request, stage);
        schedule(request.id(), stage);
    }

    void receive(SupportPriorityPacketCodec.Escalate packet) {
        state.pending(packet.requestId()).ifPresent(request ->
                delivery.request(request, packet.stage()));
    }

    private void schedule(UUID requestId, String stage) {
        String next = tiers.next(stage);
        if (next.isEmpty()) return;
        FoliaTasks.globalLater(plugin, () -> state.pending(requestId).ifPresent(request -> {
            delivery.request(request, next);
            network.sendEscalation(requestId, next);
            schedule(requestId, next);
        }), delayTicks);
    }
}
