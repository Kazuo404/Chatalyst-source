package dev.chatalyst.redis;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Codifica/valida los mensajes del canal de red. Cada mensaje va autenticado con
 * HMAC-SHA256 (secreto compartido por toda la red) y protegido contra repeticion
 * con timestamp + nonce. Formato (todos los campos sin ':' internos):
 *
 * <pre>b64(serverId):timestampMs:nonce:b64(data):b64(hmac)</pre>
 *
 * La firma cubre los cuatro primeros campos. Un mensaje se rechaza si: la firma no
 * verifica, su timestamp cae fuera de la ventana, su nonce ya se vio dentro de la
 * ventana, excede el tamano maximo, o lo origino este mismo servidor.
 */
final class RedisMessageCodec {

    /** Tamano maximo del payload util (antes de base64). */
    static final int MAX_DATA_BYTES = 64 * 1024;
    /** Ventana de validez de un mensaje (tolerancia de reloj + transito). */
    static final long REPLAY_WINDOW_MS = 5 * 60_000L;
    static final int MAX_SEEN_NONCES = 10_000;
    private static final int NONCE_BYTES = 12;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String serverId;
    private final String encodedServerId;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    // nonce -> timestamp del mensaje; se poda todo lo que salga de la ventana.
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    RedisMessageCodec(String serverId, String secret) {
        this.serverId = serverId;
        this.encodedServerId = Base64.getEncoder()
                .encodeToString(serverId.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** @return payload firmado listo para PUBLISH, o null si data excede el maximo. */
    String encode(byte[] data, long nowMs) {
        if (data == null || data.length == 0 || data.length > MAX_DATA_BYTES) return null;
        byte[] nonceBytes = new byte[NONCE_BYTES];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String body = encodedServerId + ":" + nowMs + ":" + nonce + ":"
                + Base64.getEncoder().encodeToString(data);
        return body + ":" + Base64.getEncoder().encodeToString(hmac(body));
    }

    /** @return los datos originales, o null si el mensaje no es valido (o es propio). */
    byte[] decode(String payload, long nowMs) {
        if (payload == null || payload.length() > MAX_DATA_BYTES * 2) return null;
        String[] parts = payload.split(":", 5);
        if (parts.length != 5) return null;

        long timestamp;
        try {
            timestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }

        // La firma se verifica ANTES de cualquier otra decision basada en el contenido.
        String body = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
        byte[] expected;
        byte[] provided;
        try {
            expected = hmac(body);
            provided = Base64.getDecoder().decode(parts[4]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, provided)) return null;

        if (!insideReplayWindow(nowMs, timestamp)) return null;
        byte[] data;
        String origin;
        try {
            origin = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            data = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (origin.equals(serverId)) return null; // eco de este mismo server
        if (data.length == 0 || data.length > MAX_DATA_BYTES) return null;

        String nonce = parts[2];
        // Solo se guarda el nonce canonico que produce encode(): 12 bytes -> 16 chars
        // base64url sin padding. Aunque el HMAC sea valido, un peer comprometido no debe
        // llenar las 10 000 entradas acotadas con strings de decenas de KB cada una.
        if (!validNonce(nonce)) return null;
        pruneNonces(nowMs);
        // Limite duro: aun con HMAC valido, un peer averiado no puede crecer este mapa
        // indefinidamente dentro de la ventana de replay.
        if (seenNonces.size() >= MAX_SEEN_NONCES && !seenNonces.containsKey(nonce)) return null;
        if (seenNonces.putIfAbsent(nonce, timestamp) != null) return null; // repetido
        return data;
    }

    int seenNonceCount() {
        return seenNonces.size();
    }

    private void pruneNonces(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = seenNonces.entrySet().iterator();
        while (it.hasNext()) {
            if (!insideReplayWindow(nowMs, it.next().getValue())) it.remove();
        }
    }

    /** Diferencia temporal sin overflow (Math.abs(Long.MIN_VALUE) sigue siendo negativo). */
    private static boolean insideReplayWindow(long nowMs, long timestamp) {
        try {
            long distance = timestamp >= nowMs
                    ? Math.subtractExact(timestamp, nowMs)
                    : Math.subtractExact(nowMs, timestamp);
            return distance <= REPLAY_WINDOW_MS;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Visible en el paquete para probar que el valor cacheado tiene tamano fijo/canonico. */
    static boolean validNonce(String nonce) {
        if (nonce == null || nonce.length() != 16) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(nonce);
            return decoded.length == NONCE_BYTES
                    && nonce.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            // HmacSHA256 es obligatorio en toda JVM; una clave vacia se rechaza al construir.
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }
}
