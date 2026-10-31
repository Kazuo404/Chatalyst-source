package dev.chatalyst.proxybridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Sobre autenticado para los plugin messages de la red (canal nativo y Forward legacy).
 * Mismo diseño que el codec de Redis: HMAC-SHA256 con secreto compartido + timestamp y
 * nonce contra repeticion. Motivo: en Bukkit cualquier CLIENTE puede enviar plugin
 * messages en un canal entrante registrado, y el backend no distingue por si solo un
 * paquete del proxy de uno forjado por un jugador modificado.
 *
 * <p>Sin secreto configurado queda en modo transparente (sella/abre sin tocar los bytes),
 * para no romper redes existentes; con secreto, TODO paquete sin sellar o invalido se
 * descarta. El mismo valor debe estar en cada backend y en el archivo del bridge del
 * proxy ({@link #loadSecretFile}).</p>
 */
public final class NetworkAuth {

    /** Marcador del sobre; un paquete legitimo nunca empieza por este UTF por accidente. */
    public static final String MARKER = "cauth1";

    private static final int NONCE_BYTES = 12;
    private static final int MAC_BYTES = 32; // HMAC-SHA256
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final long REPLAY_WINDOW_MS = 5 * 60_000L;
    static final int MAX_TRACKED_NONCES = ReplayNonceCache.MAX_ENTRIES;
    private static final int MIN_SECRET_CHARS = 16;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key; // null = autenticacion desactivada (transparente)
    private final boolean misconfigured;
    private final SecureRandom random = new SecureRandom();
    private final ReplayNonceCache replayCache = new ReplayNonceCache(REPLAY_WINDOW_MS);

    public NetworkAuth(String secret) {
        boolean supplied = secret != null && !secret.isBlank();
        misconfigured = supplied && !isStrongSecret(secret);
        this.key = supplied && !misconfigured
                ? new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM)
                : null;
    }

    /** True si hay secreto configurado (los paquetes se sellan y se verifican). */
    public boolean enabled() {
        return key != null;
    }

    /** True cuando se proporciono una clave demasiado corta; en ese estado falla cerrado. */
    public boolean misconfigured() {
        return misconfigured;
    }

    /** Sella un payload. Sin secreto devuelve los bytes tal cual. */
    public byte[] seal(byte[] payload) {
        if (key == null) return misconfigured ? null : payload;
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            long timestamp = System.currentTimeMillis();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 64);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(MARKER);
                out.write(nonce);
                out.writeLong(timestamp);
                out.writeInt(payload.length);
                out.write(payload);
                out.write(hmac(nonce, timestamp, payload));
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            return null; // ByteArrayOutputStream no lanza en la practica
        }
    }

    /**
     * Abre y verifica un sobre. Sin secreto devuelve los bytes tal cual (transparente).
     * Con secreto devuelve el payload original, o null si el paquete no esta sellado,
     * la firma no verifica, esta fuera de ventana o su nonce ya se vio (replay).
     */
    public byte[] open(byte[] data, long nowMs) {
        if (key == null) return misconfigured ? null : data;
        if (data == null || data.length == 0
                || data.length > MAX_PAYLOAD_BYTES + 128) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!MARKER.equals(in.readUTF())) return null;
            byte[] nonce = new byte[NONCE_BYTES];
            in.readFully(nonce);
            long timestamp = in.readLong();
            int length = in.readInt();
            if (length <= 0 || length > MAX_PAYLOAD_BYTES) return null;
            byte[] payload = new byte[length];
            in.readFully(payload);
            byte[] mac = new byte[MAC_BYTES];
            in.readFully(mac);
            if (in.available() != 0) return null;
            // Comparacion en tiempo constante: no filtra por timing cuanto de la firma acerto.
            if (!MessageDigest.isEqual(mac, hmac(nonce, timestamp, payload))) return null;
            if (!insideReplayWindow(nowMs, timestamp)) return null;
            if (!replayCache.remember(nonce, timestamp, nowMs)) return null;
            return payload;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private byte[] hmac(byte[] nonce, long timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            mac.update(nonce);
            mac.update(new byte[]{
                (byte) (timestamp >>> 56), (byte) (timestamp >>> 48),
                (byte) (timestamp >>> 40), (byte) (timestamp >>> 32),
                (byte) (timestamp >>> 24), (byte) (timestamp >>> 16),
                (byte) (timestamp >>> 8), (byte) timestamp});
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException ex) {
            // HmacSHA256 es obligatorio en toda JVM; si faltara, mejor romper que aceptar todo.
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    static boolean insideReplayWindow(long nowMs, long timestamp) {
        try {
            long distance = timestamp >= nowMs
                    ? Math.subtractExact(timestamp, nowMs)
                    : Math.subtractExact(nowMs, timestamp);
            return distance <= REPLAY_WINDOW_MS;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    int trackedNonceCount() {
        return replayCache.size();
    }

    /**
     * Deriva la clave de este protocolo a partir de material ya compartido por la red
     * (p. ej. el forwarding secret de Velocity o Redis.Shared_Secret), en vez de usar ese
     * secreto crudo como clave HMAC de OTRO protocolo (separacion de claves). Determinista:
     * backend y proxy derivan lo mismo del mismo material. Devuelve "" si no hay material.
     */
    public static String derive(String material) {
        return NetworkKeyDerivation.derive(material);
    }

    public static boolean isStrongSecret(String secret) {
        return NetworkKeyDerivation.isStrong(secret, MIN_SECRET_CHARS);
    }

    /**
     * Lee el secreto del archivo del bridge del proxy ({@code network-secret.properties});
     * si no existe, crea una plantilla comentada y devuelve "" (autenticacion apagada).
     * Formato: una linea {@code network-secret=valor}.
     */
    public static String loadSecretFile(Path file) throws IOException {
        return NetworkSecretFile.load(file);
    }
}
