package dev.chatalyst.proxybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.regex.Pattern;

/** Small shared protocol for resolving a backend's exact proxy registration name. */
public final class ProxyIdentityCodec {

    public static final String REQUEST = "identity_req";
    public static final String RESPONSE = "server_identity";

    private static final int MAX_PACKET_BYTES = 256;
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private ProxyIdentityCodec() {
    }

    public static byte[] encodeRequest() throws IOException {
        return encode(out -> out.writeUTF(REQUEST));
    }

    public static byte[] encodeResponse(String serverId) throws IOException {
        requireServerId(serverId);
        return encode(out -> {
            out.writeUTF(RESPONSE);
            out.writeUTF(serverId);
        });
    }

    public static String decodeResponse(DataInputStream input) throws IOException {
        String serverId = input.readUTF();
        requireServerId(serverId);
        return serverId;
    }

    public static boolean isRequest(byte[] data) {
        return hasType(data, REQUEST);
    }

    public static boolean isResponse(byte[] data) {
        return hasType(data, RESPONSE);
    }

    private static boolean hasType(byte[] data, String expected) {
        try (DataInputStream input = input(data)) {
            return expected.equals(input.readUTF());
        } catch (IOException ex) {
            return false;
        }
    }

    private static DataInputStream input(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid identity packet size");
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    private static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_PACKET_BYTES) throw new IOException("Identity packet is too large");
        return result;
    }

    private static void requireServerId(String value) throws IOException {
        if (value == null || !SERVER_ID.matcher(value).matches()) {
            throw new IOException("Invalid proxy server id");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
