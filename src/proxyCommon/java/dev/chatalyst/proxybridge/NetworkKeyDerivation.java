package dev.chatalyst.proxybridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Domain-separated derivation for transport authentication keys. */
final class NetworkKeyDerivation {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] CONTEXT =
            "chatalyst:network-auth:v1".getBytes(StandardCharsets.UTF_8);

    private NetworkKeyDerivation() {
    }

    static String derive(String material) {
        if (material == null || material.isBlank()) return "";
        // Deriving weak material would change its length without adding entropy. Returning it
        // lets NetworkAuth recognize the weak source and enter fail-closed mode.
        if (!isStrong(material, 16)) return material;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(material.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(CONTEXT));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    static boolean isStrong(String secret, int minimumCharacters) {
        return secret != null && !secret.isBlank() && secret.length() >= minimumCharacters;
    }
}
