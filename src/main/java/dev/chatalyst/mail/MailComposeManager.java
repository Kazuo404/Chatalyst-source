package dev.chatalyst.mail;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks players currently composing a mail in a plugin-issued book. */
public final class MailComposeManager {

    public enum Mode {
        SEND,
        REPLY
    }

    public record Session(Mode mode, UUID targetId, String targetName, String token) {
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void start(UUID playerId, Mode mode, UUID targetId, String targetName, String token) {
        sessions.put(playerId, new Session(mode, targetId, targetName, token));
    }

    public Session get(UUID playerId) {
        return sessions.get(playerId);
    }

    public void end(UUID playerId) {
        sessions.remove(playerId);
    }
}
