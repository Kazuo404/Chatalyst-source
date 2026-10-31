package dev.chatalyst.proxybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded protocol shared by Bukkit, Velocity and Bungee staff-directory runtimes. */
public final class StaffNetworkCodec {

    public static final String PRESENCE = "staff_presence";
    public static final String OFFLINE = "staff_offline";
    public static final String PLAYER_PRESENCE = "player_presence";
    public static final String PLAYER_OFFLINE = "player_offline";
    public static final String TELEPORT_REQUEST = "staff_tp_request";
    public static final String TELEPORT_ARRIVAL = "staff_tp_arrival";
    public static final String TELEPORT_RESULT = "staff_tp_result";
    private static final int MAX_BYTES = 2_048;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public record Presence(UUID eventId, long timestamp, UUID playerId, String playerName,
            String tier, String server, String world) { }
    public record Offline(UUID eventId, long timestamp, UUID playerId) { }
    public record PlayerPresence(UUID eventId, long timestamp, UUID playerId, String playerName,
            String rank, String server, String world, String gameMode, int ping, String balance,
            boolean afk) { }
    public record PlayerOffline(UUID eventId, long timestamp, UUID playerId) { }
    public record TeleportRequest(UUID eventId, UUID requesterId, UUID targetId, String scope) { }
    public record TeleportArrival(UUID eventId, UUID requesterId, UUID targetId, String scope) { }
    public record TeleportResult(UUID eventId, UUID requesterId, String result) { }

    private StaffNetworkCodec() { }

    public static String type(byte[] data) throws IOException {
        try (DataInputStream in = input(data)) { return in.readUTF(); }
    }

    public static byte[] encode(Presence value) throws IOException {
        ids(value.eventId(), value.playerId());
        text(value.playerName(), 64); token(value.tier());
        token(value.server()); text(value.world(), 64);
        return write(PRESENCE, out -> {
            uuid(out, value.eventId()); out.writeLong(value.timestamp());
            uuid(out, value.playerId()); out.writeUTF(value.playerName());
            out.writeUTF(value.tier()); out.writeUTF(value.server()); out.writeUTF(value.world());
        });
    }

    public static Presence decodePresence(DataInputStream in) throws IOException {
        Presence value = new Presence(uuid(in), in.readLong(), uuid(in), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF());
        ids(value.eventId(), value.playerId()); text(value.playerName(), 64);
        token(value.tier()); token(value.server()); text(value.world(), 64);
        return value;
    }

    public static byte[] encode(Offline value) throws IOException {
        ids(value.eventId(), value.playerId());
        return write(OFFLINE, out -> {
            uuid(out, value.eventId()); out.writeLong(value.timestamp()); uuid(out, value.playerId());
        });
    }

    public static Offline decodeOffline(DataInputStream in) throws IOException {
        Offline value = new Offline(uuid(in), in.readLong(), uuid(in));
        ids(value.eventId(), value.playerId()); return value;
    }

    public static byte[] encode(PlayerPresence value) throws IOException {
        validate(value);
        return write(PLAYER_PRESENCE, out -> {
            uuid(out, value.eventId()); out.writeLong(value.timestamp());
            uuid(out, value.playerId()); out.writeUTF(value.playerName());
            out.writeUTF(value.rank()); out.writeUTF(value.server()); out.writeUTF(value.world());
            out.writeUTF(value.gameMode()); out.writeInt(value.ping());
            out.writeUTF(value.balance()); out.writeBoolean(value.afk());
        });
    }

    public static PlayerPresence decodePlayerPresence(DataInputStream in) throws IOException {
        PlayerPresence value = new PlayerPresence(uuid(in), in.readLong(), uuid(in), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt(),
                in.readUTF(), in.readBoolean());
        validate(value); return value;
    }

    public static byte[] encode(PlayerOffline value) throws IOException {
        ids(value.eventId(), value.playerId());
        return write(PLAYER_OFFLINE, out -> {
            uuid(out, value.eventId()); out.writeLong(value.timestamp()); uuid(out, value.playerId());
        });
    }

    public static PlayerOffline decodePlayerOffline(DataInputStream in) throws IOException {
        PlayerOffline value = new PlayerOffline(uuid(in), in.readLong(), uuid(in));
        ids(value.eventId(), value.playerId()); return value;
    }

    public static byte[] encode(TeleportRequest value) throws IOException {
        ids(value.eventId(), value.requesterId(), value.targetId()); scope(value.scope());
        return write(TELEPORT_REQUEST, out -> {
            uuid(out, value.eventId()); uuid(out, value.requesterId()); uuid(out, value.targetId());
            out.writeUTF(value.scope());
        });
    }

    public static TeleportRequest decodeRequest(DataInputStream in) throws IOException {
        TeleportRequest value = new TeleportRequest(uuid(in), uuid(in), uuid(in), in.readUTF());
        ids(value.eventId(), value.requesterId(), value.targetId()); scope(value.scope()); return value;
    }

    public static byte[] encode(TeleportArrival value) throws IOException {
        ids(value.eventId(), value.requesterId(), value.targetId()); scope(value.scope());
        return write(TELEPORT_ARRIVAL, out -> {
            uuid(out, value.eventId()); uuid(out, value.requesterId()); uuid(out, value.targetId());
            out.writeUTF(value.scope());
        });
    }

    public static TeleportArrival decodeArrival(DataInputStream in) throws IOException {
        TeleportArrival value = new TeleportArrival(uuid(in), uuid(in), uuid(in), in.readUTF());
        ids(value.eventId(), value.requesterId(), value.targetId()); scope(value.scope()); return value;
    }

    public static byte[] encode(TeleportResult value) throws IOException {
        ids(value.eventId(), value.requesterId()); text(value.result(), 32);
        return write(TELEPORT_RESULT, out -> {
            uuid(out, value.eventId()); uuid(out, value.requesterId()); out.writeUTF(value.result());
        });
    }

    public static TeleportResult decodeResult(DataInputStream in) throws IOException {
        TeleportResult value = new TeleportResult(uuid(in), uuid(in), in.readUTF());
        ids(value.eventId(), value.requesterId()); text(value.result(), 32); return value;
    }

    private static DataInputStream input(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            throw new IOException("Invalid staff packet size");
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static byte[] write(String type, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(type); writer.write(out);
        }
        byte[] data = bytes.toByteArray();
        if (data.length > MAX_BYTES) throw new IOException("Staff packet is too large");
        return data;
    }

    private static void ids(UUID... values) throws IOException {
        for (UUID value : values) if (value == null) throw new IOException("Missing id");
    }

    private static void text(String value, int maximum) throws IOException {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IOException("Invalid staff field");
        }
    }

    private static void validate(PlayerPresence value) throws IOException {
        ids(value.eventId(), value.playerId()); text(value.playerName(), 64);
        text(value.rank(), 64); token(value.server()); text(value.world(), 64);
        token(value.gameMode()); text(value.balance(), 64);
        if (value.ping() < 0 || value.ping() > 100_000) throw new IOException("Invalid ping");
    }

    private static void scope(String value) throws IOException {
        if (!"staff".equals(value) && !"player".equals(value)) {
            throw new IOException("Invalid teleport scope");
        }
    }

    private static void token(String value) throws IOException {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IOException("Invalid staff token");
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }
}
