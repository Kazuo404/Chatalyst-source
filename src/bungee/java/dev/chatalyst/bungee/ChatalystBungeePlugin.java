package dev.chatalyst.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import dev.chatalyst.proxybridge.NetworkAuth;
import dev.chatalyst.proxybridge.SupportBroker;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.proxybridge.ProxyIdentityCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;

/** Native Bungee bridge: forwards backend Chatalyst plugin messages to sibling servers. */
public final class ChatalystBungeePlugin extends Plugin implements Listener {

    private static final String CHANNEL = "chatalyst:main";
    private final SupportBroker support = new SupportBroker();
    private volatile NetworkAuth auth = new NetworkAuth("");
    private volatile long lastForgeryWarn;

    @Override
    public void onEnable() {
        loadAuth();
        if (!auth.enabled()) {
            getLogger().severe("Chatalyst Bungee bridge disabled: authenticated forwarding"
                    + " requires a shared secret of at least 16 characters.");
            return;
        }
        ProxyServer proxy = getProxy();
        proxy.registerChannel(CHANNEL);
        proxy.getPluginManager().registerListener(this, this);
        getLogger().info("Chatalyst Bungee bridge enabled (authenticated).");
    }

    private void loadAuth() {
        try {
            auth = new NetworkAuth(NetworkAuth.loadSecretFile(
                    getDataFolder().toPath().resolve("network-secret.properties")));
        } catch (IOException ex) {
            getLogger().warning("Could not read network-secret.properties: " + ex.getMessage());
            auth = new NetworkAuth("");
        }
        if (auth.misconfigured()) {
            getLogger().severe("Chatalyst network forwarding is BLOCKED: network-secret.properties"
                    + " must contain at least 16 characters (32+ random recommended).");
        } else if (!auth.enabled()) {
            // BungeeCord no tiene forwarding secret, asi que aqui no hay auto-deteccion:
            // el secreto manual es la unica via en redes Bungee.
            getLogger().severe("Chatalyst network packets require authentication. Set the same"
                    + " secret in " + getDataFolder() + "/network-secret.properties and in"
                    + " Proxy.Network_Secret of every backend config.yml.");
        }
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equals(CHANNEL)) return;
        if (!auth.enabled()) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getSender() instanceof Server source)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        ServerInfo origin = source.getInfo();
        byte[] data = auth.open(event.getData(), System.currentTimeMillis());
        if (data == null) {
            long now = System.currentTimeMillis();
            if (now - lastForgeryWarn > 60_000L) {
                lastForgeryWarn = now;
                getLogger().warning("Dropped an unauthenticated Chatalyst packet from "
                        + origin.getName() + " (secret mismatch, replay, or a forged client"
                        + " packet relayed by the backend).");
            }
            return;
        }

        if (ProxyIdentityCodec.isRequest(data)) {
            sendIdentity(source);
            return;
        }
        if (ProxyIdentityCodec.isResponse(data)) {
            getLogger().warning("Dropped backend-authored Chatalyst identity response from "
                    + origin.getName() + '.');
            return;
        }
        if (isTopologyRequest(data)) {
            sendTopology(source);
            return;
        }
        if (handleStaffTeleport(data, source)) return;

        SupportBroker.Decision decision = support.inspect(data, origin.getName());
        if (decision.consumed()) {
            if (decision.broadcast() != null) {
                byte[] sealed = auth.seal(decision.broadcast());
                if (sealed == null) return;
                for (ServerInfo target : getProxy().getServers().values()) {
                    target.sendData(CHANNEL, sealed);
                }
            }
            return;
        }

        // Re-sellado una sola vez para todos los destinos (cada backend dedupe su nonce).
        byte[] sealed = auth.seal(data);
        if (sealed == null) return;
        for (ServerInfo target : getProxy().getServers().values()) {
            if (target.equals(origin)) continue;
            target.sendData(CHANNEL, sealed);
        }
    }

    private void sendIdentity(Server source) {
        try {
            byte[] sealed = auth.seal(ProxyIdentityCodec.encodeResponse(source.getInfo().getName()));
            if (sealed != null) source.sendData(CHANNEL, sealed);
        } catch (IOException ex) {
            getLogger().warning("Could not send Chatalyst backend identity: " + ex.getMessage());
        }
    }

    private boolean isTopologyRequest(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return "topology_req".equals(in.readUTF());
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean handleStaffTeleport(byte[] data, Server source) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!StaffNetworkCodec.TELEPORT_REQUEST.equals(in.readUTF())) return false;
            StaffNetworkCodec.TeleportRequest request = StaffNetworkCodec.decodeRequest(in);
            var requester = getProxy().getPlayer(request.requesterId());
            if (requester == null || requester.getServer() == null
                    || !requester.getServer().getInfo().equals(source.getInfo())) {
                sendResult(request.requesterId(), "connect-failed");
                return true;
            }
            var target = getProxy().getPlayer(request.targetId());
            ServerInfo destination = target == null || target.getServer() == null
                    ? null : target.getServer().getInfo();
            if (destination == null) {
                sendResult(request.requesterId(), "target-offline");
                return true;
            }
            if (destination.equals(source.getInfo())) {
                sendArrival(destination, request.requesterId(), request.targetId(), request.scope());
                return true;
            }
            requester.connect(destination, (success, error) -> {
                if (Boolean.TRUE.equals(success)) {
                    sendArrival(destination, request.requesterId(), request.targetId(),
                            request.scope());
                } else {
                    sendResult(request.requesterId(), "connect-failed");
                }
            });
            return true;
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Dropped invalid staff teleport request: " + ex.getMessage());
            return true;
        }
    }

    private void sendArrival(ServerInfo destination, UUID requester, UUID target, String scope) {
        try {
            byte[] sealed = auth.seal(StaffNetworkCodec.encode(
                    new StaffNetworkCodec.TeleportArrival(UUID.randomUUID(), requester, target,
                            scope)));
            if (sealed == null) {
                sendResult(requester, "connect-failed");
                return;
            }
            destination.sendData(CHANNEL, sealed);
        } catch (IOException ex) {
            sendResult(requester, "connect-failed");
        }
    }

    private void sendResult(UUID requester, String result) {
        try {
            byte[] sealed = auth.seal(StaffNetworkCodec.encode(new StaffNetworkCodec.TeleportResult(
                    UUID.randomUUID(), requester, result)));
            if (sealed == null) return;
            for (ServerInfo target : getProxy().getServers().values()) {
                target.sendData(CHANNEL, sealed);
            }
        } catch (IOException ignored) {
        }
    }

    private void sendTopology(Server source) {
        try {
            var servers = getProxy().getServers().values().stream()
                    .sorted(Comparator.comparing(ServerInfo::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("topology");
                out.writeInt(servers.size());
                for (ServerInfo server : servers) {
                    out.writeUTF(server.getName());
                    out.writeInt(server.getPlayers().size());
                }
            }
            byte[] sealed = auth.seal(bytes.toByteArray());
            if (sealed != null) source.sendData(CHANNEL, sealed);
        } catch (IOException ex) {
            getLogger().warning("Could not send Chatalyst network topology: " + ex.getMessage());
        }
    }
}
