package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.proxy.SupportPacketCodec;
import dev.chatalyst.proxy.SupportPriorityPacketCodec;

import java.io.IOException;
import java.util.UUID;

/** Validates, deduplicates and transports support state between backends. */
final class SupportNetworkCoordinator {

    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final SupportPacketReceiver receiver;
    private volatile String serverId;

    SupportNetworkCoordinator(ChatalystPlugin plugin, ProxyManager proxy,
            SupportConversationState state, SupportDelivery delivery) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.receiver = new SupportPacketReceiver(state, delivery, this::sendOpen);
    }

    void configure(String serverId, long pendingTimeoutMs) {
        this.serverId = serverId;
        receiver.configure(serverId, pendingTimeoutMs);
    }

    boolean enabled() {
        return proxy.isEnabled();
    }

    void sendRequest(SupportConversationState.Request request, String stage) {
        long now = request.createdAt();
        send(SupportPacketCodec.REQUEST, () -> SupportPacketCodec.encodeRequest(
                new SupportPacketCodec.Request(UUID.randomUUID(), now, request.id(), serverId,
                request.userId(), request.userName(), request.group(), stage, request.message())));
    }

    void sendEscalation(UUID requestId, String stage) {
        var packet = new SupportPriorityPacketCodec.Escalate(UUID.randomUUID(),
                System.currentTimeMillis(), requestId, stage);
        receiver.markLocal(packet.eventId());
        send(SupportPriorityPacketCodec.ESCALATE,
                () -> SupportPriorityPacketCodec.encode(packet));
    }

    void sendClaim(SupportPacketCodec.Claim claim) {
        if (!proxy.usesNativeSupportBroker()) receiveClaim(claim);
        send(SupportPacketCodec.CLAIM, () -> SupportPacketCodec.encodeClaim(claim));
    }

    void sendMessage(SupportPacketCodec.Message message) {
        receiveMessage(message);
        send(SupportPacketCodec.MESSAGE, () -> SupportPacketCodec.encodeMessage(message));
    }

    void sendSync(UUID participantId) {
        var request = new SupportPacketCodec.SyncRequest(UUID.randomUUID(),
                System.currentTimeMillis(), participantId);
        send(SupportPacketCodec.SYNC_REQUEST, () -> SupportPacketCodec.encodeSyncRequest(request));
    }

    void sendClose(UUID sessionId, UUID closedBy, String reason) {
        var close = new SupportPacketCodec.Close(UUID.randomUUID(), System.currentTimeMillis(),
                sessionId, closedBy, reason);
        receiver.markLocal(close.eventId());
        send(SupportPacketCodec.CLOSE, () -> SupportPacketCodec.encodeClose(close));
    }

    void receiveRequest(SupportPacketCodec.Request packet) {
        receiver.request(packet);
    }

    void receiveClaim(SupportPacketCodec.Claim packet) {
        if (proxy.usesNativeSupportBroker()) return;
        receiver.claim(packet);
    }

    void receiveOpen(SupportPacketCodec.Open packet) {
        receiver.open(packet);
    }

    void receiveMessage(SupportPacketCodec.Message packet) {
        receiver.message(packet);
    }

    void receiveClose(SupportPacketCodec.Close packet) {
        receiver.close(packet);
    }

    void receiveEscalation(SupportPriorityPacketCodec.Escalate packet,
            java.util.function.Consumer<SupportPriorityPacketCodec.Escalate> handler) {
        receiver.escalation(packet, handler);
    }

    void prune(long now, long retentionMs) {
        receiver.prune(now, retentionMs);
    }

    private void sendOpen(SupportPacketCodec.Open open) {
        send(SupportPacketCodec.OPEN, () -> SupportPacketCodec.encodeOpen(open));
    }

    private void send(String type, Encoder encoder) {
        try {
            proxy.forwardSupport(encoder.encode());
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward " + type + ": " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface Encoder { byte[] encode() throws IOException; }
}
