package dev.chatalyst.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe, transport-independent state machine for network support conversations. */
public final class SupportConversationState {

    public enum ClaimStatus { OPENED, REQUEST_MISSING, PARTICIPANT_BUSY }

    public record Request(UUID id, long createdAt, String originServer, UUID userId,
            String userName, String group, String message) {
    }

    public record Session(UUID id, long openedAt, long lastActivity, UUID userId,
            String userName, String userServer, UUID staffId,
            String staffName, String staffServer) {
        Session touched(long now) {
            return new Session(id, openedAt, now, userId, userName, userServer,
                staffId, staffName, staffServer);
        }
    }

    public record ClaimResult(ClaimStatus status, Session session) {
    }

    private final Map<UUID, Request> pending = new HashMap<>();
    private final Map<UUID, UUID> pendingByUser = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> sessionByParticipant = new HashMap<>();

    public synchronized boolean submit(Request request) {
        if (request == null || sessionByParticipant.containsKey(request.userId())
                || pendingByUser.containsKey(request.userId()) || pending.containsKey(request.id())) {
            return false;
        }
        pending.put(request.id(), request);
        pendingByUser.put(request.userId(), request.id());
        return true;
    }

    public synchronized ClaimResult claim(UUID requestId, UUID staffId, String staffName,
            String staffServer, long now) {
        Request request = pending.get(requestId);
        if (request == null) return new ClaimResult(ClaimStatus.REQUEST_MISSING, null);
        if (sessionByParticipant.containsKey(request.userId())
                || sessionByParticipant.containsKey(staffId)) {
            return new ClaimResult(ClaimStatus.PARTICIPANT_BUSY, null);
        }
        removePending(request);
        Session session = new Session(request.id(), now, now, request.userId(), request.userName(),
                request.originServer(), staffId, staffName, staffServer);
        installUnchecked(session);
        return new ClaimResult(ClaimStatus.OPENED, session);
    }

    public synchronized boolean install(Session session) {
        if (session == null || sessions.containsKey(session.id())) return false;
        if (sessionByParticipant.containsKey(session.userId())
                || sessionByParticipant.containsKey(session.staffId())) return false;
        Request request = pending.get(session.id());
        if (request != null) removePending(request);
        installUnchecked(session);
        return true;
    }

    public synchronized Optional<Request> pending(UUID requestId) {
        return Optional.ofNullable(pending.get(requestId));
    }

    public synchronized List<Request> pending() {
        return List.copyOf(pending.values());
    }

    public synchronized Optional<Session> sessionFor(UUID participantId) {
        UUID sessionId = sessionByParticipant.get(participantId);
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId));
    }

    public synchronized Optional<Session> touch(UUID sessionId, long now) {
        Session current = sessions.get(sessionId);
        if (current == null) return Optional.empty();
        Session next = current.touched(now);
        sessions.put(sessionId, next);
        return Optional.of(next);
    }

    public synchronized Optional<Session> close(UUID sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed == null) return Optional.empty();
        sessionByParticipant.remove(removed.userId(), sessionId);
        sessionByParticipant.remove(removed.staffId(), sessionId);
        return Optional.of(removed);
    }

    public synchronized List<Request> expirePending(long oldestAllowed) {
        List<Request> expired = new ArrayList<>();
        for (Request request : List.copyOf(pending.values())) {
            if (request.createdAt() >= oldestAllowed) continue;
            removePending(request);
            expired.add(request);
        }
        return List.copyOf(expired);
    }

    public synchronized List<Session> expireSessions(long oldestAllowed) {
        List<Session> expired = new ArrayList<>();
        for (Session session : List.copyOf(sessions.values())) {
            if (session.lastActivity() >= oldestAllowed) continue;
            close(session.id()).ifPresent(expired::add);
        }
        return List.copyOf(expired);
    }

    private void removePending(Request request) {
        pending.remove(request.id());
        pendingByUser.remove(request.userId(), request.id());
    }

    private void installUnchecked(Session session) {
        sessions.put(session.id(), session);
        sessionByParticipant.put(session.userId(), session.id());
        sessionByParticipant.put(session.staffId(), session.id());
    }
}
