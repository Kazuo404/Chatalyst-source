package dev.chatalyst.proxy;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.announce.AnnounceDraftManager;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.network.NetworkMonitor;
import dev.chatalyst.redis.RedisSync;
import dev.chatalyst.support.SupportConversationManager;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.proxybridge.ProxyIdentityCodec;
import dev.chatalyst.staff.StaffDirectoryManager;
import dev.chatalyst.staff.NetworkPlayerDirectory;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.CommandNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Sincroniza los canales marcados como Proxy a traves de la red (Velocity/Bungee) usando
 * plugin messaging (canal "BungeeCord", subcanal Forward). Un mensaje escrito en un server
 * se muestra tambien en los demas. Degrada limpio: si el proxy no reenvia o no hay nadie,
 * simplemente no se propaga (el chat local sigue funcionando).
 */
public final class ProxyManager implements PluginMessageListener {

    private static final String BUNGEE = "BungeeCord";
    private static final String NATIVE_CHANNEL = "chatalyst:main";
    private static final String SUBCHANNEL = "chatalyst:chat";

    private final JavaPlugin plugin;
    private final ChannelManager channels;
    private final RedisSync redis;
    private StorageManager storage;
    private NetworkMonitor networkMonitor;
    private AnnounceDraftManager announcements;
    private SupportConversationManager support;
    private StaffDirectoryManager staffDirectory;
    private NetworkPlayerDirectory playerDirectory;
    private volatile boolean enabled;
    private volatile String mode = "legacy";
    // HMAC + anti-replay de los plugin messages (canal nativo y Forward legacy). Estos
    // transportes quedan deshabilitados si no se puede resolver una clave fuerte.
    private volatile dev.chatalyst.proxybridge.NetworkAuth auth = disabledAuth();
    private volatile long lastForgeryWarn;
    private volatile boolean remoteRuleCommandsEnabled;
    private volatile java.util.Set<String> allowedRemoteRuleCommands = java.util.Set.of();
    private volatile long lastRuleCommandWarn;
    private static final long RULE_COMMAND_MAX_SKEW_MS = 120_000L;
    private static final int MAX_SEEN_RULE_COMMANDS = 1_000;
    // Bastan unos miles: las dos copias de un mismo mensaje llegan con milisegundos de
    // diferencia, no hace falta recordar ids durante minutos.
    private static final int MAX_SEEN_CHAT = 4_096;
    private final java.util.LinkedHashMap<java.util.UUID, Boolean> seenChat =
            new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<java.util.UUID, Boolean> seenRuleCommands =
            new java.util.LinkedHashMap<>();

    public ProxyManager(JavaPlugin plugin, ChannelManager channels) {
        this.plugin = plugin;
        this.channels = channels;
        this.redis = new RedisSync(plugin, data -> {
            try {
                receivePayload(data);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to receive Redis sync payload: " + ex.getMessage());
            }
        });
    }

    public synchronized void enable() {
        enabled = plugin.getConfig().getBoolean("Proxy.Enabled", false);
        remoteRuleCommandsEnabled = plugin.getConfig().getBoolean(
                "Proxy.Rule_Commands.Enabled", false);
        java.util.Set<String> allowedCommands = new java.util.LinkedHashSet<>();
        for (String configured : plugin.getConfig().getStringList(
                "Proxy.Rule_Commands.Allowed_Commands")) {
            String label = CommandNames.label(configured);
            if (!label.isEmpty() && allowedCommands.size() < 128) allowedCommands.add(label);
        }
        allowedRemoteRuleCommands = java.util.Set.copyOf(allowedCommands);
        String configuredMode = plugin.getConfig().getString("Proxy.Mode", "legacy");
        mode = configuredMode == null ? ""
                : configuredMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!enabled) return;
        if (!isSupportedMode(mode)) {
            plugin.getLogger().severe("Cross-server forwarding is BLOCKED: Proxy.Mode '"
                    + mode + "' is invalid (use native, legacy, both or redis).");
            enabled = false;
            return;
        }
        NetworkSecrets.Resolved secret = NetworkSecrets.resolve(plugin, usesNative());
        auth = new dev.chatalyst.proxybridge.NetworkAuth(secret.secret());
        if (pluginMessagingMode(mode) && !auth.enabled()) {
            plugin.getLogger().severe("Cross-server forwarding is BLOCKED: " + secret.source()
                    + " must provide a shared secret of at least 16 characters (32+ random"
                    + " characters recommended). Native/legacy plugin messaging never runs"
                    + " without packet authentication.");
            enabled = false;
            return;
        }
        if (auth.enabled()) {
            plugin.getLogger().info("Network packet authentication enabled (source: "
                    + secret.source() + ").");
        }
        Messenger m = plugin.getServer().getMessenger();
        if (usesLegacy()) {
            m.registerOutgoingPluginChannel(plugin, BUNGEE);
            m.registerIncomingPluginChannel(plugin, BUNGEE, this);
        }
        if (usesNative()) {
            m.registerOutgoingPluginChannel(plugin, NATIVE_CHANNEL);
            m.registerIncomingPluginChannel(plugin, NATIVE_CHANNEL, this);
        }
        if (usesRedis()) {
            redis.start();
            if (!redis.isEnabled()) {
                plugin.getLogger().severe("Cross-server forwarding is BLOCKED: Proxy.Mode is"
                        + " redis but Redis.Enabled is false or Redis could not start.");
                enabled = false;
                auth = disabledAuth();
                redis.stop();
                return;
            } else if (storage != null) {
                // Con base de datos compartida, propagamos invalidaciones de cache para que
                // un cambio hecho en un server se vea en los demas sin reiniciar.
                storage.bindNetworkInvalidation(this::forwardInvalidation);
            }
        }
    }

    /** Relee modo/secreto y reconstruye los canales y Redis sin dejar bindings anteriores. */
    public synchronized void reload() {
        disable();
        enable();
    }

    /** Llamar antes de {@link #enable()} para activar la invalidacion de cache en red. */
    public void bindStorage(StorageManager storage) {
        this.storage = storage;
    }

    public void bindNetworkMonitor(NetworkMonitor networkMonitor) {
        this.networkMonitor = networkMonitor;
    }

    public void bindAnnouncements(AnnounceDraftManager announcements) {
        this.announcements = announcements;
    }

    public void bindSupport(SupportConversationManager support) {
        this.support = support;
    }

    public void bindStaffDirectory(StaffDirectoryManager directory) {
        this.staffDirectory = directory;
    }

    public void bindPlayerDirectory(NetworkPlayerDirectory directory) {
        this.playerDirectory = directory;
    }

    public synchronized void disable() {
        enabled = false;
        remoteRuleCommandsEnabled = false;
        allowedRemoteRuleCommands = java.util.Set.of();
        synchronized (seenRuleCommands) {
            seenRuleCommands.clear();
        }
        auth = disabledAuth();
        redis.stop();
        Messenger m = plugin.getServer().getMessenger();
        m.unregisterOutgoingPluginChannel(plugin, BUNGEE);
        m.unregisterIncomingPluginChannel(plugin, BUNGEE, this);
        m.unregisterOutgoingPluginChannel(plugin, NATIVE_CHANNEL);
        m.unregisterIncomingPluginChannel(plugin, NATIVE_CHANNEL, this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Native universal proxy runtimes arbitrate support claims centrally. */
    public boolean usesNativeSupportBroker() {
        return enabled && usesNative();
    }

    public boolean usesNativeStaffBridge() {
        return enabled && usesNative();
    }

    /** Reenvia un mensaje ya formateado a los demas servers de la red. */
    public void forward(String channelName, Component rendered) {
        if (!enabled) return;
        try {
            String json = GsonComponentSerializer.gson().serialize(rendered);
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF("chat");
            payload.writeUTF(channelName);
            payload.writeUTF(json);
            // Id del mensaje logico, AL FINAL y opcional: un backend viejo lee canal y JSON y
            // ignora lo que sobra, y uno nuevo lo usa para no pintar dos veces el mismo
            // mensaje cuando llega por dos rutas (Proxy.Mode=both: nativo + legacy).
            java.util.UUID eventId = java.util.UUID.randomUUID();
            payload.writeLong(eventId.getMostSignificantBits());
            payload.writeLong(eventId.getLeastSignificantBits());
            byte[] data = payloadBytes.toByteArray();
            if (usesNative()) forwardNative(data);
            if (usesLegacy()) forwardLegacy(data);
            if (usesRedis()) redis.publish(data);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward via proxy: " + ex.getMessage());
        }
    }

    /** Pide al servidor donde este {@code playerName} que le entregue un formato de chat. */
    public void forwardFormat(String playerName, String format, String text) {
        if (!enabled) return;
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF(SEND_FORMAT);
            payload.writeUTF(playerName);
            payload.writeUTF(format);
            payload.writeUTF(text);
            forwardData(payloadBytes.toByteArray(), true);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward format: " + ex.getMessage());
        }
    }

    private static final String SEND_FORMAT = "format";

    private void receiveFormat(DataInputStream payload) throws IOException {
        String playerName = payload.readUTF();
        String format = payload.readUTF();
        String text = payload.readUTF();
        FoliaTasks.global(plugin, () -> {
            Player target = plugin.getServer().getPlayerExact(playerName);
            if (target == null) return;
            FoliaTasks.entity(plugin, target, p -> dev.chatalyst.chat.FormatSender.deliver(
                    (dev.chatalyst.ChatalystPlugin) plugin, p, format, text));
        });
    }

    /** Publica una copia plana y acotada del chat para el monitor administrativo de red. */
    public void forwardMonitor(NetworkPacketCodec.MonitorChat packet) {
        if (!enabled) return;
        try {
            forwardData(NetworkPacketCodec.encodeMonitor(packet), true);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward network monitor event: " + ex.getMessage());
        }
    }

    /** Envia un anuncio visual a todos los backends conectados al proxy. */
    public boolean forwardAnnouncement(NetworkPacketCodec.Announcement packet) {
        if (!transportReady()) return false;
        try {
            forwardData(NetworkPacketCodec.encodeAnnouncement(packet), true);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward network announcement: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Lleva el anuncio de un castigo al resto de la red. Rellena el servidor de origen; si
     * el proxy aun no nos dijo nuestro nombre, sale como "backend" (solo es informativo).
     */
    public void forwardPunishment(java.util.UUID eventId, String kind, String staff,
                                  String target, String reason, String duration, long id,
                                  boolean silent) {
        if (!transportReady()) return;
        String serverId = networkMonitor != null ? networkMonitor.serverId() : "";
        if (serverId == null || serverId.isBlank()) serverId = "backend";
        try {
            forwardData(NetworkPacketCodec.encodePunishment(new NetworkPacketCodec.Punishment(
                    eventId, System.currentTimeMillis(), serverId, kind,
                    staff == null ? "" : staff, target, reason == null ? "" : reason,
                    duration == null ? "" : duration, Math.max(0L, id), silent)), true);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward punishment broadcast: " + ex.getMessage());
        }
    }

    /** Forwards an already validated private-support packet to the other backends. */
    public void forwardSupport(byte[] data) {
        if (!enabled) return;
        forwardData(data, true);
    }

    public void forwardStaff(byte[] data) {
        if (!enabled) return;
        forwardData(data, false);
    }

    /**
     * then proxyconsole: sends one already-substituted command to one named backend. The packet
     * is authenticated, locally allowlisted at both ends, target-filtered and cross-route
     * deduplicated before the receiving console can execute it.
     */
    public boolean forwardRuleCommand(String targetServer, String command) {
        if (!transportReady() || !isAllowedRuleCommand(command)) {
            warnBlockedRuleCommand(command, "sender policy");
            return false;
        }
        String sourceServer = networkMonitor != null ? networkMonitor.serverId() : "";
        if (!validTargetServer(targetServer) || sourceServer.isBlank()
                || targetServer.equalsIgnoreCase(sourceServer)
                || targetServer.equalsIgnoreCase("proxy")) {
            warnBlockedRuleCommand(command, "invalid or unavailable target server");
            return false;
        }
        try {
            forwardData(NetworkPacketCodec.encodeProxyCommand(new NetworkPacketCodec.ProxyCommand(
                    java.util.UUID.randomUUID(), System.currentTimeMillis(), sourceServer,
                    targetServer, command)), true);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward proxy command: " + ex.getMessage());
            return false;
        }
    }

    /** Pide al proxy su lista de servidores y poblacion actual. */
    public void requestTopology() {
        if (!enabled) return;
        try {
            // Redis no conoce la topologia de Velocity/Bungee. El request solo se envia por
            // plugin messaging y el proxy responde exclusivamente al backend solicitante.
            forwardData(NetworkPacketCodec.encodeTopologyRequest(), false);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to request proxy topology: " + ex.getMessage());
        }
    }

    /** Asks the native proxy for this backend's exact registered name. */
    public void requestIdentity() {
        if (!enabled || !usesNative()) return;
        try {
            forwardNative(ProxyIdentityCodec.encodeRequest());
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to request proxy server identity: " + ex.getMessage());
        }
    }

    private void forwardData(byte[] data, boolean includeRedis) {
        if (usesNative()) forwardNative(data);
        if (usesLegacy()) forwardLegacy(data);
        if (includeRedis && usesRedis()) redis.publish(data);
    }

    /** Avisa a los demas servers de que estas filas cambiaron en la DB compartida. */
    private void forwardInvalidation(String namespace, java.util.Collection<String> ids) {
        if (!enabled || !usesRedis() || ids.isEmpty()) return;
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF("inv");
            payload.writeUTF(namespace);
            payload.writeInt(ids.size());
            for (String id : ids) payload.writeUTF(id);
            // Solo via Redis: los transportes de plugin messaging necesitan un jugador
            // online como carrier y la coherencia de datos no debe depender de eso.
            redis.publish(payloadBytes.toByteArray());
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to forward cache invalidation: " + ex.getMessage());
        }
    }

    private boolean usesNative() {
        return mode.equals("native") || mode.equals("both");
    }

    private boolean usesLegacy() {
        return mode.equals("legacy") || mode.equals("both");
    }

    private boolean usesRedis() {
        return mode.equals("redis");
    }

    static boolean pluginMessagingMode(String configuredMode) {
        return configuredMode != null && (configuredMode.equalsIgnoreCase("native")
                || configuredMode.equalsIgnoreCase("legacy")
                || configuredMode.equalsIgnoreCase("both"));
    }

    public static boolean isSupportedMode(String configuredMode) {
        if (configuredMode == null) return false;
        return switch (configuredMode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "native", "legacy", "both", "redis" -> true;
            default -> false;
        };
    }

    private boolean transportReady() {
        return enabled && (!usesRedis() || redis.isEnabled())
                && (usesNative() || usesLegacy() || usesRedis());
    }

    private void forwardNative(byte[] payload) {
        byte[] data = auth.seal(payload);
        if (data == null) return; // payload invalido para el sobre (vacio o gigante)
        FoliaTasks.global(plugin, () -> {
            Player carrier = anyOnline();
            if (carrier == null) return;
            FoliaTasks.entity(plugin, carrier, p -> p.sendPluginMessage(plugin, NATIVE_CHANNEL, data));
        });
    }

    private void forwardLegacy(byte[] payload) {
        byte[] data = auth.seal(payload);
        if (data == null) return;
        if (data.length > Short.MAX_VALUE) {
            plugin.getLogger().warning("Proxy message too long (" + data.length
                    + " bytes); not forwarded through legacy Bungee channel.");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ONLINE");
        out.writeUTF(SUBCHANNEL);
        out.writeShort(data.length);
        out.write(data);
        byte[] legacy = out.toByteArray();
        FoliaTasks.global(plugin, () -> {
            Player carrier = anyOnline();
            if (carrier == null) return;
            FoliaTasks.entity(plugin, carrier, p -> p.sendPluginMessage(plugin, BUNGEE, legacy));
        });
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!enabled) return;
        if (channel.equals(NATIVE_CHANNEL)) {
            receiveNative(message);
            return;
        }
        if (!channel.equals(BUNGEE)) return;
        byte[] data = decodeLegacyPayload(message);
        if (data == null) return;
        data = openAuthenticated(data);
        if (data == null) return;
        try {
            receivePayload(data);
        } catch (IOException | RuntimeException ex) {
            // RuntimeException tambien: sin secreto configurado cualquier cliente modificado
            // puede inyectar en este canal, y un fallo de un handler no debe propagarse al
            // dispatcher de plugin messages del servidor (el camino Redis ya se contiene igual).
            plugin.getLogger().warning("Failed to receive proxy message: " + ex.getMessage());
        }
    }

    /** Decodifica el envoltorio Forward sin permitir longitudes, truncado o bytes sobrantes. */
    static byte[] decodeLegacyPayload(byte[] message) {
        if (message == null || message.length == 0) return null;
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(message);
             DataInputStream in = new DataInputStream(bytes)) {
            if (!SUBCHANNEL.equals(in.readUTF())) return null;
            int length = in.readUnsignedShort();
            if (length <= 0 || length > Short.MAX_VALUE || length != in.available()) return null;
            byte[] payload = in.readNBytes(length);
            return payload.length == length ? payload : null;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private void receiveNative(byte[] sealed) {
        byte[] data = openAuthenticated(sealed);
        if (data == null) return;
        try {
            receivePayload(data);
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Failed to receive native proxy message: " + ex.getMessage());
        }
    }

    /**
     * Verifica el sobre HMAC de un plugin message entrante. Devuelve el payload, o null si
     * con secreto activo el paquete no verifica (forjado, corrupto o repetido). El aviso va
     * rate-limited: un cliente malicioso no debe poder inundar el log.
     */
    private byte[] openAuthenticated(byte[] sealed) {
        byte[] data = auth.open(sealed, System.currentTimeMillis());
        if (data == null) {
            long now = System.currentTimeMillis();
            if (now - lastForgeryWarn > 60_000L) {
                lastForgeryWarn = now;
                plugin.getLogger().warning("Dropped an unauthenticated cross-server packet"
                        + " (bad or missing signature). If this repeats, either a backend/proxy"
                        + " has a different Proxy.Network_Secret or a client is forging packets.");
            }
        }
        return data;
    }

    private final java.util.Set<String> unknownPackets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Un backend con otra version manda tipos desconocidos: se avisa una vez por tipo. */
    private void warnUnknownPacket(String type) {
        if (unknownPackets.size() < 32 && unknownPackets.add(type)) {
            plugin.getLogger().warning("Ignoring network packet '" + type + "'. Check that every"
                    + " server and proxy runs the same Chatalyst version.");
        }
    }

    private void receivePayload(byte[] data) throws IOException {
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(data));
        String type = payload.readUTF();
        switch (type) {
            case "inv" -> {
                receiveInvalidation(payload);
                return;
            }
            case NetworkPacketCodec.MONITOR_CHAT -> {
                if (networkMonitor != null) {
                    networkMonitor.receive(NetworkPacketCodec.decodeMonitor(payload));
                }
                return;
            }
            case NetworkPacketCodec.ANNOUNCEMENT -> {
                if (announcements != null) {
                    announcements.receiveNetwork(NetworkPacketCodec.decodeAnnouncement(payload));
                }
                return;
            }
            case NetworkPacketCodec.PUNISHMENT -> {
                NetworkPacketCodec.Punishment packet = NetworkPacketCodec.decodePunishment(payload);
                // Con Proxy.Mode=both llega por dos rutas: se pinta una sola vez.
                if (!firstSighting(seenChat, packet.eventId(), MAX_SEEN_CHAT)) return;
                var chatalyst = dev.chatalyst.ChatalystPlugin.get();
                var staff = chatalyst == null ? null : chatalyst.getStaffModeration();
                if (staff != null && staff.enabled()) {
                    dev.chatalyst.staff.tools.PunishmentBroadcast.receiveNetwork(staff, packet);
                }
                return;
            }
            case NetworkPacketCodec.TOPOLOGY -> {
                if (networkMonitor != null) {
                    networkMonitor.updateTopology(NetworkPacketCodec.decodeTopology(payload));
                }
                return;
            }
            case NetworkPacketCodec.TOPOLOGY_REQUEST -> {
                return; // solo lo consume el proxy; nunca es una orden para un backend
            }
            case NetworkPacketCodec.PROXY_COMMAND -> {
                NetworkPacketCodec.ProxyCommand packet =
                        NetworkPacketCodec.decodeProxyCommand(payload);
                String localServer = networkMonitor != null ? networkMonitor.serverId() : "";
                if (!packet.targetServer().equalsIgnoreCase(localServer)) return;
                if (!acceptRuleCommand(packet)) return;
                if (!isAllowedRuleCommand(packet.command())) {
                    warnBlockedRuleCommand(packet.command(), "receiver policy");
                    return;
                }
                // Ejecuta como consola en ESTE backend. El paquete llego autenticado (HMAC):
                // ademas, necesita opt-in y allowlist local. Se despacha en el hilo global
                // porque dispatchCommand no es seguro desde el subscriber de Redis.
                FoliaTasks.global(plugin, () -> {
                    var chatalyst = dev.chatalyst.ChatalystPlugin.get();
                    var log = chatalyst != null ? chatalyst.getLogManager() : null;
                    if (log != null) {
                        log.log("RULE", "[network:" + packet.sourceServer() + "]",
                                "proxyconsole " + log.redactSensitiveCommand(packet.command()));
                    }
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), packet.command());
                });
                return;
            }
            case ProxyIdentityCodec.RESPONSE -> {
                if (networkMonitor != null) {
                    networkMonitor.updateIdentity(ProxyIdentityCodec.decodeResponse(payload));
                }
                return;
            }
            case ProxyIdentityCodec.REQUEST -> {
                return; // only the native proxy may answer this request
            }
            case SupportPacketCodec.REQUEST -> {
                if (support != null) support.receiveRequest(SupportPacketCodec.decodeRequest(payload));
                return;
            }
            case SupportPacketCodec.CLAIM -> {
                if (support != null) support.receiveClaim(SupportPacketCodec.decodeClaim(payload));
                return;
            }
            case SupportPacketCodec.OPEN -> {
                if (support != null) support.receiveOpen(SupportPacketCodec.decodeOpen(payload));
                return;
            }
            case SupportPacketCodec.MESSAGE -> {
                if (support != null) support.receiveMessage(SupportPacketCodec.decodeMessage(payload));
                return;
            }
            case SupportPacketCodec.CLOSE -> {
                if (support != null) support.receiveClose(SupportPacketCodec.decodeClose(payload));
                return;
            }
            case SupportPriorityPacketCodec.ESCALATE -> {
                if (support != null) {
                    support.receiveEscalation(SupportPriorityPacketCodec.decode(payload));
                }
                return;
            }
            case StaffNetworkCodec.PRESENCE -> {
                if (staffDirectory != null) {
                    staffDirectory.receive(StaffNetworkCodec.decodePresence(payload));
                }
                return;
            }
            case StaffNetworkCodec.OFFLINE -> {
                if (staffDirectory != null) {
                    staffDirectory.receive(StaffNetworkCodec.decodeOffline(payload));
                }
                return;
            }
            case StaffNetworkCodec.PLAYER_PRESENCE -> {
                if (playerDirectory != null) {
                    playerDirectory.receive(StaffNetworkCodec.decodePlayerPresence(payload));
                }
                return;
            }
            case StaffNetworkCodec.PLAYER_OFFLINE -> {
                if (playerDirectory != null) {
                    playerDirectory.receive(StaffNetworkCodec.decodePlayerOffline(payload));
                }
                return;
            }
            case StaffNetworkCodec.TELEPORT_ARRIVAL -> {
                if (staffDirectory != null) {
                    staffDirectory.arrive(StaffNetworkCodec.decodeArrival(payload));
                }
                return;
            }
            case StaffNetworkCodec.TELEPORT_RESULT -> {
                if (staffDirectory != null) {
                    staffDirectory.result(StaffNetworkCodec.decodeResult(payload));
                }
                return;
            }
            case StaffNetworkCodec.TELEPORT_REQUEST -> {
                return;
            }
            case SEND_FORMAT -> {
                receiveFormat(payload);
                return;
            }
            case "chat" -> {
                // Continua abajo con el formato historico de chat cross-server.
            }
            default -> {
                warnUnknownPacket(type);
                return;
            }
        }
        String channelName = payload.readUTF();
        String json = payload.readUTF();
        if (payload.available() >= 16) {
            java.util.UUID eventId = new java.util.UUID(payload.readLong(), payload.readLong());
            if (!firstSighting(seenChat, eventId, MAX_SEEN_CHAT)) return;
        }
        // JSON de otro servidor (posible mismatch de version o payload corrupto/malicioso): la
        // deserializacion puede lanzar RuntimeException, que los catch de IOException de arriba
        // NO atraparian — reventaria el hilo del subscriber de Redis. Se contiene aqui.
        Component comp;
        try {
            comp = GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Dropped a malformed cross-server chat payload: " + ex.getMessage());
            return;
        }
        showLocal(channelName, comp);
    }

    private boolean isAllowedRuleCommand(String command) {
        return remoteRuleCommandsEnabled && !allowedRemoteRuleCommands.isEmpty()
                && CommandNames.matches(command, allowedRemoteRuleCommands);
    }

    private static boolean validTargetServer(String targetServer) {
        if (targetServer == null || targetServer.isBlank() || targetServer.length() > 64) {
            return false;
        }
        for (int i = 0; i < targetServer.length(); i++) {
            char c = targetServer.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    /** Marca un id como visto; false si ya habia llegado por otra ruta. Acotado por FIFO. */
    private static boolean firstSighting(java.util.LinkedHashMap<java.util.UUID, Boolean> seen,
                                         java.util.UUID eventId, int cap) {
        synchronized (seen) {
            if (seen.containsKey(eventId)) return false;
            while (seen.size() >= cap) {
                var iterator = seen.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            seen.put(eventId, Boolean.TRUE);
            return true;
        }
    }

    /** Cross-route dedupe is required in Proxy.Mode=both, where one packet can arrive twice. */
    private boolean acceptRuleCommand(NetworkPacketCodec.ProxyCommand packet) {
        long now = System.currentTimeMillis();
        if (packet.timestamp() < now - RULE_COMMAND_MAX_SKEW_MS
                || packet.timestamp() > now + RULE_COMMAND_MAX_SKEW_MS) return false;
        synchronized (seenRuleCommands) {
            if (seenRuleCommands.containsKey(packet.eventId())) return false;
            while (seenRuleCommands.size() >= MAX_SEEN_RULE_COMMANDS) {
                var iterator = seenRuleCommands.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            seenRuleCommands.put(packet.eventId(), Boolean.TRUE);
            return true;
        }
    }

    private void warnBlockedRuleCommand(String command, String policy) {
        long now = System.currentTimeMillis();
        if (now - lastRuleCommandWarn <= 60_000L) return;
        lastRuleCommandWarn = now;
        plugin.getLogger().warning("Blocked a rule proxyconsole action by " + policy
                + ". Enable Proxy.Rule_Commands and explicitly allow command label '"
                + CommandNames.baseLabel(command) + "' on every backend that may execute it.");
    }

    private void receiveInvalidation(DataInputStream payload) throws IOException {
        if (storage == null) return;
        String namespace = payload.readUTF();
        int count = payload.readInt();
        if (count <= 0 || count > 4096) return; // corrupto o abusivo
        java.util.List<String> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(payload.readUTF());
        // Llega en el hilo del subscriber de Redis: el refresh hace SQL bloqueante, OK aqui.
        storage.onRemoteInvalidation(namespace, ids);
    }

    private void showLocal(String channelName, Component comp) {
        Channel ch = channels.byName(channelName);
        if (ch == null) return;
        // listens() aplica permiso, mute de canal, Auto_Listen y el modo exclusivo, igual
        // que para los mensajes locales.
        FoliaTasks.onlinePlayers(plugin,
                p -> channels.listens(p, ch),
                p -> p.sendMessage(comp));
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(comp));
    }

    private Player anyOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            return p;
        }
        return null;
    }

    /** A deliberately too-short supplied secret makes NetworkAuth reject every packet. */
    private static dev.chatalyst.proxybridge.NetworkAuth disabledAuth() {
        return new dev.chatalyst.proxybridge.NetworkAuth("disabled");
    }
}
