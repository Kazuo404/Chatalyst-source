package dev.chatalyst.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.chatalyst.proxybridge.NetworkAuth;
import dev.chatalyst.proxybridge.SupportBroker;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.proxybridge.ProxyIdentityCodec;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** Native Velocity bridge: forwards backend Chatalyst plugin messages to sibling servers. */
public final class ChatalystVelocityPlugin {

    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("chatalyst:main");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final SupportBroker support = new SupportBroker();
    private volatile NetworkAuth auth = new NetworkAuth("");
    private volatile long lastForgeryWarn;

    @Inject
    public ChatalystVelocityPlugin(ProxyServer server, Logger logger,
                                   @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadAuth();
        if (!auth.enabled()) {
            logger.error("Chatalyst Velocity bridge disabled: authenticated forwarding"
                    + " requires a shared secret of at least 16 characters.");
            return;
        }
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("Chatalyst Velocity bridge enabled (authenticated).");
    }

    private void loadAuth() {
        String explicit = "";
        try {
            explicit = NetworkAuth.loadSecretFile(dataDirectory.resolve("network-secret.properties"));
        } catch (IOException ex) {
            logger.warn("Could not read network-secret.properties: {}", ex.getMessage());
        }
        if (!explicit.isBlank()) {
            auth = new NetworkAuth(explicit);
            if (auth.misconfigured()) {
                logger.error("Chatalyst network forwarding is BLOCKED: network-secret.properties"
                        + " must contain at least 16 characters (32+ random recommended).");
            } else {
                logger.info("Chatalyst network authentication enabled (source: network-secret.properties).");
            }
            return;
        }
        // Sin override manual: deriva del forwarding secret de Velocity, el mismo material
        // que los backends auto-detectan en su paper-global.yml. Cero configuracion.
        String forwarding = readForwardingSecret();
        auth = new NetworkAuth(NetworkAuth.derive(forwarding));
        if (auth.misconfigured()) {
            logger.error("Chatalyst network forwarding is BLOCKED: Velocity's forwarding"
                    + " secret must contain at least 16 characters.");
        } else if (auth.enabled()) {
            logger.info("Chatalyst network authentication enabled (source: Velocity forwarding secret).");
        } else {
            logger.error("Chatalyst network packets require authentication. Use modern forwarding"
                    + " (auto-detected), or set the same secret in {} and in Proxy.Network_Secret"
                    + " of every backend config.yml.",
                    dataDirectory.resolve("network-secret.properties"));
        }
    }

    /** Forwarding secret del proxy: velocity.toml -> forwarding-secret-file (por defecto
     *  "forwarding.secret"), relativo a la raiz del proxy (dos niveles sobre plugins/chatalyst). */
    private String readForwardingSecret() {
        try {
            Path root = dataDirectory.toAbsolutePath().normalize().getParent();
            root = root == null ? null : root.getParent();
            if (root == null) return "";
            String fileName = "forwarding.secret";
            Path toml = root.resolve("velocity.toml");
            if (java.nio.file.Files.isRegularFile(toml)) {
                for (String line : java.nio.file.Files.readAllLines(toml)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("forwarding-secret-file")) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) continue;
                    String value = trimmed.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isBlank()) fileName = value;
                    break;
                }
            }
            Path secretFile = root.resolve(fileName).normalize();
            if (!java.nio.file.Files.isRegularFile(secretFile)) return "";
            return java.nio.file.Files.readString(secretFile).trim();
        } catch (Exception ex) {
            logger.warn("Could not read the Velocity forwarding secret: {}", ex.getMessage());
            return "";
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        server.getChannelRegistrar().unregister(CHANNEL);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        if (!auth.enabled()) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        if (!(event.getSource() instanceof ServerConnection source)) {
            // Un jugador (cliente modificado) intento hablar por el canal del bridge.
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        RegisteredServer origin = source.getServer();
        byte[] data = auth.open(event.getData(), System.currentTimeMillis());
        if (data == null) {
            long now = System.currentTimeMillis();
            if (now - lastForgeryWarn > 60_000L) {
                lastForgeryWarn = now;
                logger.warn("Dropped an unauthenticated Chatalyst packet from {} (secret"
                        + " mismatch, replay, or a forged client packet relayed by the backend).",
                        origin.getServerInfo().getName());
            }
            return;
        }

        if (ProxyIdentityCodec.isRequest(data)) {
            sendIdentity(source);
            return;
        }
        if (ProxyIdentityCodec.isResponse(data)) {
            logger.warn("Dropped backend-authored Chatalyst identity response from {}.",
                    origin.getServerInfo().getName());
            return;
        }
        if (isTopologyRequest(data)) {
            sendTopology(source);
            return;
        }
        if (handleStaffTeleport(data, source)) return;

        SupportBroker.Decision decision = support.inspect(data, origin.getServerInfo().getName());
        if (decision.consumed()) {
            if (decision.broadcast() != null) {
                byte[] sealed = auth.seal(decision.broadcast());
                if (sealed == null) return;
                for (RegisteredServer target : server.getAllServers()) {
                    target.sendPluginMessage(CHANNEL, sealed);
                }
            }
            return;
        }

        // Re-sellado una sola vez para todos los destinos (cada backend dedupe su nonce).
        byte[] sealed = auth.seal(data);
        if (sealed == null) return;
        for (RegisteredServer target : server.getAllServers()) {
            if (target.equals(origin)) continue;
            target.sendPluginMessage(CHANNEL, sealed);
        }
    }

    private void sendIdentity(ServerConnection source) {
        try {
            String serverId = source.getServerInfo().getName();
            byte[] sealed = auth.seal(ProxyIdentityCodec.encodeResponse(serverId));
            if (sealed != null) source.sendPluginMessage(CHANNEL, sealed);
        } catch (IOException ex) {
            logger.warn("Could not send Chatalyst backend identity: {}", ex.getMessage());
        }
    }

    private boolean isTopologyRequest(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return "topology_req".equals(in.readUTF());
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean handleStaffTeleport(byte[] data, ServerConnection source) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!StaffNetworkCodec.TELEPORT_REQUEST.equals(in.readUTF())) return false;
            StaffNetworkCodec.TeleportRequest request = StaffNetworkCodec.decodeRequest(in);
            var requester = server.getPlayer(request.requesterId()).orElse(null);
            var requesterServer = requester == null
                    ? java.util.Optional.<ServerConnection>empty() : requester.getCurrentServer();
            // El plugin message viaja sobre cualquier carrier del backend. Autorizamos por
            // UUID embebido + backend de origen, no por la identidad accidental del carrier.
            if (requester == null || requesterServer.isEmpty()
                    || !requesterServer.get().getServer().equals(source.getServer())) {
                sendResult(request.requesterId(), "connect-failed");
                return true;
            }
            var target = server.getPlayer(request.targetId()).orElse(null);
            var destination = target == null ? null : target.getCurrentServer().orElse(null);
            if (destination == null) {
                sendResult(request.requesterId(), "target-offline");
                return true;
            }
            RegisteredServer targetServer = destination.getServer();
            if (targetServer.equals(source.getServer())) {
                sendArrival(targetServer, request.requesterId(), request.targetId(), request.scope());
                return true;
            }
            requester.createConnectionRequest(targetServer).connect().thenAccept(result -> {
                if (result.isSuccessful()) {
                    requester.getCurrentServer().ifPresent(connection -> sendArrival(
                            connection.getServer(), request.requesterId(), request.targetId(),
                            request.scope()));
                } else {
                    sendResult(request.requesterId(), "connect-failed");
                }
            });
            return true;
        } catch (IOException | RuntimeException ex) {
            logger.warn("Dropped invalid staff teleport request: {}", ex.getMessage());
            return true;
        }
    }

    private void sendArrival(RegisteredServer destination, UUID requester, UUID target,
            String scope) {
        try {
            byte[] sealed = auth.seal(StaffNetworkCodec.encode(
                    new StaffNetworkCodec.TeleportArrival(UUID.randomUUID(), requester, target,
                            scope)));
            if (sealed == null) {
                sendResult(requester, "connect-failed");
                return;
            }
            destination.sendPluginMessage(CHANNEL, sealed);
        } catch (IOException ex) {
            sendResult(requester, "connect-failed");
        }
    }

    private void sendResult(UUID requester, String result) {
        try {
            byte[] sealed = auth.seal(StaffNetworkCodec.encode(new StaffNetworkCodec.TeleportResult(
                    UUID.randomUUID(), requester, result)));
            if (sealed == null) return;
            for (RegisteredServer target : server.getAllServers()) {
                target.sendPluginMessage(CHANNEL, sealed);
            }
        } catch (IOException ignored) {
        }
    }

    private void sendTopology(ServerConnection source) {
        try {
            var servers = server.getAllServers().stream()
                    .sorted(Comparator.comparing(s -> s.getServerInfo().getName(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("topology");
                out.writeInt(servers.size());
                for (RegisteredServer registered : servers) {
                    out.writeUTF(registered.getServerInfo().getName());
                    out.writeInt(registered.getPlayersConnected().size());
                }
            }
            byte[] sealed = auth.seal(bytes.toByteArray());
            if (sealed != null) source.sendPluginMessage(CHANNEL, sealed);
        } catch (IOException ex) {
            logger.warn("Could not send Chatalyst network topology: {}", ex.getMessage());
        }
    }
}
