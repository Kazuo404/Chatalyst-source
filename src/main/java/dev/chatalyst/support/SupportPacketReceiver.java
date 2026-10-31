package dev.chatalyst.support;

import dev.chatalyst.proxy.SupportPacketCodec;
import dev.chatalyst.proxy.SupportPriorityPacketCodec;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Applies authenticated, deduplicated support packets to the local state machine. */
final class SupportPacketReceiver {

    private static final int MAX_SEEN_EVENTS = 4_096;
    private final SupportConversationState state;
    private final SupportDelivery delivery;
    // Orden de insercion para expulsar el evento mas antiguo en O(1) al llegar al tope.
    // Todo acceso se serializa en mark/prune.
    private final Map<UUID, Long> seen = new java.util.LinkedHashMap<>();
    private final java.util.function.Consumer<SupportPacketCodec.Open> openSender;
    private volatile String serverId;
    private volatile long pendingTimeoutMs;

    SupportPacketReceiver(SupportConversationState state, SupportDelivery delivery,
            java.util.function.Consumer<SupportPacketCodec.Open> openSender) {
        this.state = state;
        this.delivery = delivery;
        this.openSender = openSender;
    }

    void configure(String serverId, long pendingTimeoutMs) {
        this.serverId = serverId;
        this.pendingTimeoutMs = pendingTimeoutMs;
    }

    void request(SupportPacketCodec.Request packet) {
        if (!mark(packet.eventId()) || expired(packet.timestamp())) return;
        var request = new SupportConversationState.Request(packet.requestId(), packet.timestamp(),
                packet.originServer(), packet.userId(), packet.userName(), packet.group(),
                packet.message());
        Optional.of(request).filter(state::submit).ifPresent(value ->
                delivery.request(value, packet.stage()));
    }

    void claim(SupportPacketCodec.Claim packet) {
        if (!mark(packet.eventId())) return;
        Optional<SupportConversationState.Request> pending = state.pending(packet.requestId());
        if (pending.isEmpty() || !pending.get().originServer().equalsIgnoreCase(serverId)) return;
        var result = state.claim(packet.requestId(), packet.staffId(), packet.staffName(),
                packet.staffServer(), packet.timestamp());
        if (result.status() != SupportConversationState.ClaimStatus.OPENED) return;
        SupportConversationState.Session session = result.session();
        delivery.opened(session, false);
        openSender.accept(new SupportPacketCodec.Open(UUID.randomUUID(), packet.timestamp(),
                session.id(), session.userServer(), session.userId(), session.userName(),
                session.staffServer(), session.staffId(), session.staffName(), false));
    }

    void open(SupportPacketCodec.Open packet) {
        if (!mark(packet.eventId())) return;
        var session = new SupportConversationState.Session(packet.requestId(), packet.timestamp(),
                packet.timestamp(), packet.userId(), packet.userName(), packet.userServer(),
                packet.staffId(), packet.staffName(), packet.staffServer());
        Optional.of(session).filter(state::install).ifPresent(value ->
                delivery.opened(value, packet.restored()));
    }

    void message(SupportPacketCodec.Message packet) {
        if (!mark(packet.eventId())) return;
        Optional<SupportConversationState.Session> found = state.sessionFor(packet.senderId());
        if (found.isEmpty() || !found.get().id().equals(packet.sessionId())) return;
        SupportConversationState.Session session = found.get();
        boolean participant = session.userId().equals(packet.senderId())
                || session.staffId().equals(packet.senderId());
        if (!participant) return;
        state.touch(session.id(), packet.timestamp());
        delivery.message(session, packet.senderName(), packet.message());
    }

    void close(SupportPacketCodec.Close packet) {
        if (!mark(packet.eventId())) return;
        state.close(packet.sessionId()).ifPresent(session ->
                delivery.closed(session, packet.reason().equals("timeout")));
    }

    void markLocal(UUID eventId) {
        mark(eventId);
    }

    void escalation(SupportPriorityPacketCodec.Escalate packet,
            java.util.function.Consumer<SupportPriorityPacketCodec.Escalate> handler) {
        if (mark(packet.eventId())) handler.accept(packet);
    }

    void prune(long now, long retentionMs) {
        synchronized (seen) {
            if (seen.size() <= MAX_SEEN_EVENTS) return;
            long oldest = now - Math.max(retentionMs, TimeUnit.MINUTES.toMillis(10));
            seen.entrySet().removeIf(entry -> entry.getValue() < oldest);
            trimOldest();
        }
    }

    private boolean mark(UUID id) {
        if (id == null) return false;
        synchronized (seen) {
            if (seen.containsKey(id)) return false;
            seen.put(id, System.currentTimeMillis());
            trimOldest();
            return true;
        }
    }

    private void trimOldest() {
        while (seen.size() > MAX_SEEN_EVENTS) {
            var iterator = seen.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private boolean expired(long timestamp) {
        long now = System.currentTimeMillis();
        return timestamp < now - pendingTimeoutMs || timestamp > now + TimeUnit.MINUTES.toMillis(1);
    }

    int seenCount() {
        synchronized (seen) {
            return seen.size();
        }
    }
}
