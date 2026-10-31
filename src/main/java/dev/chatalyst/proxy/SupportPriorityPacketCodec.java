package dev.chatalyst.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Bounded packet for advancing an existing support request to the next staff tier. */
public final class SupportPriorityPacketCodec {

    public static final String ESCALATE = "support_escalate";

    private SupportPriorityPacketCodec() {
    }

    public record Escalate(UUID eventId, long timestamp, UUID requestId, String stage) {
    }

    public static byte[] encode(Escalate value) throws IOException {
        validate(value);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(ESCALATE);
            PacketIo.writeUuid(out, value.eventId());
            out.writeLong(value.timestamp());
            PacketIo.writeUuid(out, value.requestId());
            out.writeUTF(value.stage());
        }
        return bytes.toByteArray();
    }

    public static Escalate decode(DataInputStream in) throws IOException {
        Escalate value = new Escalate(PacketIo.readUuid(in), in.readLong(), PacketIo.readUuid(in), in.readUTF());
        validate(value);
        return value;
    }

    private static void validate(Escalate value) throws IOException {
        if (value.eventId() == null || value.requestId() == null
                || value.stage() == null || value.stage().isBlank() || value.stage().length() > 32) {
            throw new IOException("Invalid support escalation packet");
        }
    }

}
