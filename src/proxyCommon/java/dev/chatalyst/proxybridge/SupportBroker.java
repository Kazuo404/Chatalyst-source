package dev.chatalyst.proxybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Proxy-side first-writer-wins broker for network support requests. */
public final class SupportBroker {

    public record Decision(boolean consumed, byte[] broadcast) {
        public static Decision pass() { return new Decision(false, null); }
        public static Decision consume(byte[] data) { return new Decision(true, data); }
    }

    private record Request(long timestamp, UUID id, String userServer, UUID userId,
            String userName) {
    }

    private record Session(UUID id, UUID userId, String userName, String userServer,
            UUID staffId, String staffName, String staffServer, long openedAt) {
    }

    private static final int MAX_PACKET_BYTES = 8_192;
    private static final int MAX_PENDING = 1_000;
    private static final long REQUEST_TTL_MS = 30 * 60_000L;
    private static final long SESSION_TTL_MS = 4 * 60 * 60_000L;

    private final Map<UUID, Request> pending = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> activeParticipants = new HashSet<>();

    public synchronized Decision inspect(byte[] data, String sourceServer) {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            return Decision.consume(null);
        }
        long now = System.currentTimeMillis();
        prune(now);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return switch (in.readUTF()) {
                case "support_request" -> rememberRequest(in, sourceServer, now, data);
                case "support_claim" -> claim(in, now, sourceServer);
                case "support_close" -> close(in, data);
                case "support_sync" -> sync(in);
                case "support_message" -> touch(in);
                default -> Decision.pass();
            };
        } catch (IOException | RuntimeException ex) {
            return Decision.consume(null);
        }
    }

    private Decision rememberRequest(DataInputStream in, String sourceServer, long now, byte[] data)
            throws IOException {
        readUuid(in); // event id
        long timestamp = in.readLong();
        UUID requestId = readUuid(in);
        in.readUTF(); // claimed origin id; the connected source is authoritative
        UUID userId = readUuid(in);
        String userName = bounded(in.readUTF(), 64);
        bounded(in.readUTF(), 32);
        bounded(in.readUTF(), 32);
        bounded(in.readUTF(), 512);
        if (timestamp < now - REQUEST_TTL_MS || timestamp > now + 60_000L) {
            return Decision.consume(null);
        }
        if (pending.size() >= MAX_PENDING || activeParticipants.contains(userId)) {
            return Decision.consume(null);
        }
        pending.putIfAbsent(requestId, new Request(timestamp, requestId,
                bounded(sourceServer, 64), userId, userName));
        return new Decision(false, data);
    }

    private Decision claim(DataInputStream in, long now, String sourceServer) throws IOException {
        readUuid(in); // claim event id
        in.readLong();
        UUID requestId = readUuid(in);
        UUID staffId = readUuid(in);
        String staffName = bounded(in.readUTF(), 64);
        bounded(in.readUTF(), 64); // claimed server; connected source is authoritative
        String staffServer = bounded(sourceServer, 64);
        Request request = pending.get(requestId);
        if (request == null || activeParticipants.contains(request.userId())
                || activeParticipants.contains(staffId)) return Decision.consume(null);
        pending.remove(requestId);
        Session session = new Session(requestId, request.userId(), request.userName(),
                request.userServer(), staffId, staffName, staffServer, now);
        sessions.put(requestId, session);
        activeParticipants.add(request.userId());
        activeParticipants.add(staffId);
        return Decision.consume(openPacket(now, session, false));
    }

    private Decision close(DataInputStream in, byte[] data) throws IOException {
        readUuid(in); in.readLong();
        UUID sessionId = readUuid(in);
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            activeParticipants.remove(removed.userId());
            activeParticipants.remove(removed.staffId());
        }
        return new Decision(false, data);
    }

    private Decision sync(DataInputStream in) throws IOException {
        readUuid(in); in.readLong();
        UUID participantId = readUuid(in);
        for (Session session : sessions.values()) {
            if (session.userId().equals(participantId) || session.staffId().equals(participantId)) {
                return Decision.consume(openPacket(System.currentTimeMillis(), session, true));
            }
        }
        return Decision.consume(null);
    }

    private Decision touch(DataInputStream in) throws IOException {
        readUuid(in); long timestamp = in.readLong();
        UUID sessionId = readUuid(in);
        Session current = sessions.get(sessionId);
        if (current != null) {
            sessions.put(sessionId, new Session(current.id(), current.userId(), current.userName(),
                current.userServer(), current.staffId(), current.staffName(),
                current.staffServer(), timestamp));
        }
        return Decision.pass();
    }

    private static byte[] openPacket(long now, Session session, boolean restored)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("support_open");
            writeUuid(out, UUID.randomUUID());
            out.writeLong(now);
            writeUuid(out, session.id());
            out.writeUTF(session.userServer());
            writeUuid(out, session.userId());
            out.writeUTF(session.userName());
            out.writeUTF(session.staffServer());
            writeUuid(out, session.staffId());
            out.writeUTF(session.staffName());
            out.writeBoolean(restored);
        }
        return bytes.toByteArray();
    }

    private void prune(long now) {
        pending.values().removeIf(request -> request.timestamp() < now - REQUEST_TTL_MS);
        for (var entry : Map.copyOf(sessions).entrySet()) {
            if (entry.getValue().openedAt() >= now - SESSION_TTL_MS) continue;
            Session removed = sessions.remove(entry.getKey());
            if (removed != null) {
                activeParticipants.remove(removed.userId());
                activeParticipants.remove(removed.staffId());
            }
        }
    }

    private static String bounded(String value, int maximum) throws IOException {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IOException("Invalid support field");
        }
        return value;
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }
}
