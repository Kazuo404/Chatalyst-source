package dev.chatalyst.proxybridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads or creates the proxy bridge's manual network-secret override. */
final class NetworkSecretFile {

    private NetworkSecretFile() {
    }

    static String load(Path file) throws IOException {
        if (!Files.exists(file)) {
            createTemplate(file);
            return "";
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("network-secret=")) {
                return trimmed.substring("network-secret=".length()).trim();
            }
        }
        return "";
    }

    private static void createTemplate(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, """
                # Chatalyst network authentication (manual override).
                # On Velocity this can stay empty only when the key can be auto-derived from
                # the forwarding secret. BungeeCord has no such source, so it MUST be set:
                # the SAME long random value here and in Proxy.Network_Secret of every
                # backend's config.yml (16+ characters, 32+ recommended).
                network-secret=
                """);
    }
}
