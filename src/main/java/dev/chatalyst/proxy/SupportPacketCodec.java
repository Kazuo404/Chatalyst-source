package dev.chatalyst.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict bounded codec for private support traffic crossing the proxy. */
public final class SupportPacketCodec {
    public static final String REQUEST = "support_request";
    public static final String CLAIM = "support_claim";
    public static final String OPEN = "support_open";
    public static final String MESSAGE = "support_message";
    public static final String CLOSE = "support_close";
    public static final String SYNC_REQUEST = "support_sync";

    private static final int MAX_PACKET_BYTES = 8_192;
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private SupportPacketCodec() {
    }

    public record Request(UUID eventId, long timestamp, UUID requestId, String originServer,
            UUID userId, String userName, String group, String stage, String message) {
    }
    public record Claim(UUID eventId, long timestamp, UUID requestId, UUID staffId,
            String staffName, String staffServer) {
    }
    public record Open(UUID eventId, long timestamp, UUID requestId, String userServer,
            UUID userId, String userName, String staffServer,
            UUID staffId, String staffName, boolean restored) {
    }
    public record Message(UUID eventId, long timestamp, UUID sessionId,
            UUID senderId, String senderName, String message) {
    }
    public record Close(UUID eventId, long timestamp, UUID sessionId,
            UUID closedBy, String reason) {
    }
    public record SyncRequest(UUID eventId, long timestamp, UUID participantId) {
    }

    public static byte[] encodeRequest(Request value) throws IOException {
        validate(value);
        return encode(out -> {
            out.writeUTF(REQUEST); PacketIo.writeUuid(out, value.eventId()); out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.requestId()); out.writeUTF(value.originServer());
            PacketIo.writeUuid(out, value.userId()); out.writeUTF(value.userName());
            out.writeUTF(value.group()); out.writeUTF(value.stage()); out.writeUTF(value.message());
        });
    }

    public static Request decodeRequest(DataInputStream in) throws IOException {
        Request value = new Request(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), in.readUTF(),
                PacketIo.readUuid(in), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
        validate(value);
        return value;
    }

    public static byte[] encodeClaim(Claim value) throws IOException {
        validate(value);
        return encode(out -> {
            out.writeUTF(CLAIM); PacketIo.writeUuid(out, value.eventId()); out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.requestId()); PacketIo.writeUuid(out, value.staffId());
            out.writeUTF(value.staffName()); out.writeUTF(value.staffServer());
        });
    }

    public static Claim decodeClaim(DataInputStream in) throws IOException {
        Claim value = new Claim(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), PacketIo.readUuid(in),
                in.readUTF(), in.readUTF());
        validate(value);
        return value;
    }

    public static byte[] encodeOpen(Open value) throws IOException {
        validate(value);
        return encode(out -> {
            out.writeUTF(OPEN); PacketIo.writeUuid(out, value.eventId()); out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.requestId()); out.writeUTF(value.userServer());
            PacketIo.writeUuid(out, value.userId()); out.writeUTF(value.userName());
            out.writeUTF(value.staffServer()); PacketIo.writeUuid(out, value.staffId());
            out.writeUTF(value.staffName()); out.writeBoolean(value.restored());
        });
    }

    public static Open decodeOpen(DataInputStream in) throws IOException {
        Open value = new Open(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), in.readUTF(),
                PacketIo.readUuid(in), in.readUTF(), in.readUTF(), PacketIo.readUuid(in), in.readUTF(),
                in.readBoolean());
        validate(value);
        return value;
    }

    public static byte[] encodeMessage(Message value) throws IOException {
        validate(value);
        return encode(out -> {
            out.writeUTF(MESSAGE); PacketIo.writeUuid(out, value.eventId()); out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.sessionId()); PacketIo.writeUuid(out, value.senderId());
            out.writeUTF(value.senderName()); out.writeUTF(value.message());
        });
    }

    public static Message decodeMessage(DataInputStream in) throws IOException {
        Message value = new Message(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), PacketIo.readUuid(in),
                in.readUTF(), in.readUTF());
        validate(value);
        return value;
    }

    public static byte[] encodeClose(Close value) throws IOException {
        validate(value);
        return encode(out -> {
            out.writeUTF(CLOSE); PacketIo.writeUuid(out, value.eventId()); out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.sessionId()); PacketIo.writeUuid(out, value.closedBy());
            out.writeUTF(value.reason());
        });
    }

    public static Close decodeClose(DataInputStream in) throws IOException {
        Close value = new Close(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), PacketIo.readUuid(in), in.readUTF());
        validate(value);
        return value;
    }

    public static byte[] encodeSyncRequest(SyncRequest value) throws IOException {
        ids(value.eventId(), value.participantId());
        return encode(out -> {
            out.writeUTF(SYNC_REQUEST); PacketIo.writeUuid(out, value.eventId());
            out.writeLong(value.timestamp()); PacketIo.writeUuid(out, value.participantId());
        });
    }

    public static DataInputStream input(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid support packet size");
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static void validate(Request value) throws IOException {
        ids(value.eventId(), value.requestId(), value.userId()); server(value.originServer());
        text("user", value.userName(), 64); text("group", value.group(), 32);
        text("stage", value.stage(), 32);
        text("message", value.message(), 512);
    }

    private static void validate(Claim value) throws IOException {
        ids(value.eventId(), value.requestId(), value.staffId());
        text("staff", value.staffName(), 64); server(value.staffServer());
    }

    private static void validate(Open value) throws IOException {
        ids(value.eventId(), value.requestId(), value.userId(), value.staffId());
        server(value.userServer()); server(value.staffServer());
        text("user", value.userName(), 64); text("staff", value.staffName(), 64);
    }

    private static void validate(Message value) throws IOException {
        ids(value.eventId(), value.sessionId(), value.senderId());
        text("sender", value.senderName(), 64); text("message", value.message(), 512);
    }

    private static void validate(Close value) throws IOException {
        ids(value.eventId(), value.sessionId(), value.closedBy());
        text("reason", value.reason(), 64);
    }

    private static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_PACKET_BYTES) throw new IOException("Support packet is too large");
        return result;
    }

    private static void ids(UUID... ids) throws IOException {
        for (UUID id : ids) if (id == null) throw new IOException("Missing id");
    }

    private static void server(String value) throws IOException {
        if (value == null || !SERVER_ID.matcher(value).matches()) throw new IOException("Invalid server");
    }

    private static void text(String field, String value, int maximum) throws IOException {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IOException("Invalid " + field);
        }
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }
}
