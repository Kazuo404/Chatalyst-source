package dev.chatalyst.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Codec acotado para las operaciones administrativas que cruzan el proxy. */
public final class NetworkPacketCodec {

    public static final String MONITOR_CHAT = "monitor_chat";
    public static final String ANNOUNCEMENT = "network_announce";
    public static final String TOPOLOGY_REQUEST = "topology_req";
    public static final String TOPOLOGY = "topology";
    public static final String PROXY_COMMAND = "proxy_command";
    public static final String PUNISHMENT = "punish_broadcast";

    private static final int MAX_PACKET_BYTES = 30_000;
    private static final int MAX_SERVERS = 256;
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final List<String> DISPLAYS = List.of(
            "epic", "chat", "title", "actionbar", "bossbar", "toast");
    private static final List<String> ANIMATIONS = List.of(
            "none", "typewriter", "expand", "glitch", "wave", "frames", "pulse",
            "flash", "marquee", "countdown");

    private NetworkPacketCodec() {
    }

    public record MonitorChat(UUID eventId, long timestamp, String serverId,
                              String channel, String player, String message) {
    }

    public record Announcement(UUID eventId, long timestamp, String serverId, String sender,
                               String display, String sound, String animation,
                               String template, String message) {
    }

    public record ServerStatus(String serverId, int players) {
    }

    public static byte[] encodeMonitor(MonitorChat packet) throws IOException {
        requireUuid(packet.eventId());
        requireServer(packet.serverId());
        requireText("channel", packet.channel(), 64);
        requireText("player", packet.player(), 64);
        requireText("message", packet.message(), 512);
        return encode(out -> {
            out.writeUTF(MONITOR_CHAT);
            PacketIo.writeUuid(out, packet.eventId());
            out.writeLong(packet.timestamp());
            out.writeUTF(packet.serverId());
            out.writeUTF(packet.channel());
            out.writeUTF(packet.player());
            out.writeUTF(packet.message());
        });
    }

    public static MonitorChat decodeMonitor(DataInputStream in) throws IOException {
        MonitorChat packet = new MonitorChat(PacketIo.readUuid(in), in.readLong(), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF());
        requireUuid(packet.eventId());
        requireServer(packet.serverId());
        requireText("channel", packet.channel(), 64);
        requireText("player", packet.player(), 64);
        requireText("message", packet.message(), 512);
        return packet;
    }

    public static byte[] encodeAnnouncement(Announcement packet) throws IOException {
        requireUuid(packet.eventId());
        requireServer(packet.serverId());
        requireText("sender", packet.sender(), 64);
        requireChoice("display", packet.display(), DISPLAYS);
        requireText("sound", packet.sound(), 128, true);
        requireChoice("animation", packet.animation(), ANIMATIONS);
        requireText("template", packet.template(), 2048);
        requireText("message", packet.message(), 512);
        return encode(out -> {
            out.writeUTF(ANNOUNCEMENT);
            PacketIo.writeUuid(out, packet.eventId());
            out.writeLong(packet.timestamp());
            out.writeUTF(packet.serverId());
            out.writeUTF(packet.sender());
            out.writeUTF(packet.display());
            out.writeUTF(packet.sound());
            out.writeUTF(packet.animation());
            out.writeUTF(packet.template());
            out.writeUTF(packet.message());
        });
    }

    public static Announcement decodeAnnouncement(DataInputStream in) throws IOException {
        Announcement packet = new Announcement(PacketIo.readUuid(in), in.readLong(), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
        requireUuid(packet.eventId());
        requireServer(packet.serverId());
        requireText("sender", packet.sender(), 64);
        requireChoice("display", packet.display(), DISPLAYS);
        requireText("sound", packet.sound(), 128, true);
        requireChoice("animation", packet.animation(), ANIMATIONS);
        requireText("template", packet.template(), 2048);
        requireText("message", packet.message(), 512);
        return packet;
    }

    /**
     * Anuncio de un castigo puesto en otro servidor. Viaja en datos, no ya pintado: cada
     * backend lo dibuja con su idioma y su moderation.yml, igual que si lo hubiera puesto el.
     *
     * @param staff nombre visible de quien castigo; vacio = la consola
     * @param id    numero del castigo, 0 si no tiene
     */
    public record Punishment(UUID eventId, long timestamp, String serverId, String kind,
                             String staff, String target, String reason, String duration,
                             long id, boolean silent) {
    }

    public static byte[] encodePunishment(Punishment packet) throws IOException {
        validatePunishment(packet);
        return encode(out -> {
            out.writeUTF(PUNISHMENT);
            PacketIo.writeUuid(out, packet.eventId());
            out.writeLong(packet.timestamp());
            out.writeUTF(packet.serverId());
            out.writeUTF(packet.kind());
            out.writeUTF(packet.staff());
            out.writeUTF(packet.target());
            out.writeUTF(packet.reason());
            out.writeUTF(packet.duration());
            out.writeLong(packet.id());
            out.writeBoolean(packet.silent());
        });
    }

    public static Punishment decodePunishment(DataInputStream in) throws IOException {
        Punishment packet = new Punishment(PacketIo.readUuid(in), in.readLong(), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                in.readLong(), in.readBoolean());
        validatePunishment(packet);
        return packet;
    }

    private static void validatePunishment(Punishment packet) throws IOException {
        if (packet == null) throw new IOException("Missing punishment");
        requireUuid(packet.eventId());
        requireTimestamp(packet.timestamp());
        requireServer(packet.serverId());
        requireText("kind", packet.kind(), 16);
        requireText("staff", packet.staff(), 64, true);
        requireText("target", packet.target(), 64);
        requireText("reason", packet.reason(), 1024, true);
        requireText("duration", packet.duration(), 128, true);
        if (packet.id() < 0) throw new IOException("Invalid punishment id");
    }

    public static byte[] encodeTopologyRequest() throws IOException {
        return encode(out -> out.writeUTF(TOPOLOGY_REQUEST));
    }

    public record ProxyCommand(java.util.UUID eventId, long timestamp, String sourceServer,
                               String targetServer, String command) {
    }

    /** then proxyconsole: targeted command to execute as console on one receiving backend. */
    public static byte[] encodeProxyCommand(ProxyCommand packet) throws IOException {
        if (packet == null) throw new IOException("Missing proxy command");
        requireUuid(packet.eventId());
        requireTimestamp(packet.timestamp());
        requireServer(packet.sourceServer());
        requireServer(packet.targetServer());
        requireCommand(packet.command());
        return encode(out -> {
            out.writeUTF(PROXY_COMMAND);
            PacketIo.writeUuid(out, packet.eventId());
            out.writeLong(packet.timestamp());
            out.writeUTF(packet.sourceServer());
            out.writeUTF(packet.targetServer());
            out.writeUTF(packet.command());
        });
    }

    public static ProxyCommand decodeProxyCommand(DataInputStream in) throws IOException {
        java.util.UUID eventId = PacketIo.readUuid(in);
        long timestamp = in.readLong();
        String sourceServer = in.readUTF();
        String targetServer = in.readUTF();
        String command = in.readUTF();
        requireTimestamp(timestamp);
        requireServer(sourceServer);
        requireServer(targetServer);
        requireCommand(command);
        if (in.available() != 0) throw new IOException("Trailing proxy command data");
        return new ProxyCommand(eventId, timestamp, sourceServer, targetServer, command);
    }

    private static void requireCommand(String command) throws IOException {
        requireText("command", command, 512);
        if (!command.equals(command.trim())) throw new IOException("Invalid command whitespace");
        for (int i = 0; i < command.length();) {
            int cp = command.codePointAt(i);
            if (Character.isISOControl(cp)) throw new IOException("Invalid command control character");
            i += Character.charCount(cp);
        }
    }

    public static byte[] encodeTopology(List<ServerStatus> servers) throws IOException {
        if (servers == null || servers.size() > MAX_SERVERS) throw new IOException("Too many servers");
        for (ServerStatus server : servers) {
            requireServer(server.serverId());
            if (server.players() < 0 || server.players() > 1_000_000) {
                throw new IOException("Invalid player count");
            }
        }
        return encode(out -> {
            out.writeUTF(TOPOLOGY);
            out.writeInt(servers.size());
            for (ServerStatus server : servers) {
                out.writeUTF(server.serverId());
                out.writeInt(server.players());
            }
        });
    }

    public static List<ServerStatus> decodeTopology(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SERVERS) throw new IOException("Invalid server count");
        List<ServerStatus> servers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            int players = in.readInt();
            requireServer(id);
            if (players < 0 || players > 1_000_000) throw new IOException("Invalid player count");
            servers.add(new ServerStatus(id, players));
        }
        return List.copyOf(servers);
    }

    public static String packetType(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid proxy packet size");
        }
        try (DataInputStream in = input(data)) {
            return in.readUTF();
        }
    }

    public static DataInputStream input(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid proxy packet size");
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_PACKET_BYTES) throw new IOException("Proxy packet is too large");
        return encoded;
    }

    private static void requireUuid(UUID id) throws IOException {
        if (id == null) throw new IOException("Missing event id");
    }

    private static void requireTimestamp(long timestamp) throws IOException {
        if (timestamp <= 0L) throw new IOException("Invalid timestamp");
    }

    private static void requireServer(String value) throws IOException {
        if (value == null || !SERVER_ID.matcher(value).matches()) {
            throw new IOException("Invalid server id");
        }
    }

    private static void requireText(String field, String value, int max) throws IOException {
        requireText(field, value, max, false);
    }

    private static void requireText(String field, String value, int max, boolean emptyAllowed)
            throws IOException {
        if (value == null || (!emptyAllowed && value.isBlank()) || value.length() > max) {
            throw new IOException("Invalid " + field);
        }
    }

    private static void requireChoice(String field, String value, List<String> choices) throws IOException {
        if (!choices.contains(value)) throw new IOException("Invalid " + field);
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
