package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.proxy.SupportPacketCodec;
import dev.chatalyst.proxy.SupportPriorityPacketCodec;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Coordinates private player-to-staff support sessions across all proxy backends. */
public final class SupportConversationManager {

    public enum RequestResult { CREATED, ALREADY_WAITING, IN_SESSION, DISABLED }

    private final SupportConversationState state = new SupportConversationState();
    private final SupportDelivery delivery;
    private final SupportNetworkCoordinator network;
    private final SupportRuntime runtime;
    private final SupportRequestService requests;
    private final SupportTierPolicy tiers;
    private final SupportEscalation escalation;

    public SupportConversationManager(ChatalystPlugin plugin, ChannelManager channels,
            ProxyManager proxy) {
        this.tiers = new SupportTierPolicy(plugin);
        this.delivery = new SupportDelivery(plugin, channels, tiers);
        this.network = new SupportNetworkCoordinator(plugin, proxy, state, delivery);
        this.runtime = new SupportRuntime(plugin, state, delivery, network);
        this.escalation = new SupportEscalation(plugin, state, delivery, network, tiers);
        this.requests = new SupportRequestService(state, delivery, network, runtime, escalation);
    }

    public void load() {
        tiers.load();
        escalation.load();
        runtime.load();
    }

    public void start() {
        runtime.start();
    }

    public void stop() {
        runtime.stop();
    }

    public RequestResult request(Player user, String group, String rawMessage) {
        return requests.request(user, group, rawMessage);
    }

    public void accept(Player staff, UUID requestId) {
        requests.accept(staff, requestId);
    }

    /**
     * Cierra la conversacion de quien lo pide, sea el staff o el jugador atendido.
     *
     * <p>Antes solo podia cerrarla el staff. Mientras dura, TODO lo que el jugador escribe en
     * el chat se desvia a esa conversacion, y cada mensaje suyo renueva el plazo de
     * inactividad: si quien le atendia se desconectaba sin cerrar, el jugador se quedaba sin
     * chat publico y sin forma de salir, hablandole a nadie.
     */
    public void close(Player participant) {
        Optional<SupportConversationState.Session> current =
                state.sessionFor(participant.getUniqueId());
        if (current.isEmpty()) {
            participant.sendMessage(Lang.get(participant, "support.no-active-session"));
            return;
        }
        closeAndForward(current.get(), participant.getUniqueId(), "closed");
    }

    public void status(Player player) {
        Optional<SupportConversationState.Session> session = state.sessionFor(player.getUniqueId());
        if (session.isPresent()) {
            String peer = session.get().userId().equals(player.getUniqueId())
                ? session.get().staffName() : session.get().userName();
            player.sendMessage(Lang.get(player, "support.status-active", "player", MM.escape(peer)));
            return;
        }
        player.sendMessage(Lang.get(player, "support.status-idle"));
    }

    public void sync(Player player) {
        if (!runtime.enabled() || !network.enabled()) return;
        network.sendSync(player.getUniqueId());
    }

    /** Keeps support routing aligned when the native proxy resolves this backend name. */
    public void updateServerId(String serverId) {
        runtime.updateServerId(serverId);
    }

    /** Returns true when normal chat was consumed by an active private support session. */
    public boolean handleChat(Player sender, String rawMessage) {
        Optional<SupportConversationState.Session> current = state.sessionFor(sender.getUniqueId());
        if (current.isEmpty()) return false;
        String message = runtime.clean(rawMessage);
        if (message.isEmpty()) return true;
        long now = System.currentTimeMillis();
        var packet = new SupportPacketCodec.Message(UUID.randomUUID(), now, current.get().id(),
                sender.getUniqueId(), sender.getName(), message);
        network.sendMessage(packet);
        return true;
    }

    public void receiveRequest(SupportPacketCodec.Request packet) {
        network.receiveRequest(packet);
    }

    public void receiveClaim(SupportPacketCodec.Claim packet) {
        network.receiveClaim(packet);
    }

    public void receiveOpen(SupportPacketCodec.Open packet) {
        network.receiveOpen(packet);
    }

    public void receiveMessage(SupportPacketCodec.Message packet) {
        network.receiveMessage(packet);
    }

    public void receiveClose(SupportPacketCodec.Close packet) {
        network.receiveClose(packet);
    }

    public void receiveEscalation(SupportPriorityPacketCodec.Escalate packet) {
        network.receiveEscalation(packet, escalation::receive);
    }

    public SupportTierPolicy tiers() {
        return tiers;
    }

    public boolean isStaff(Player player) {
        return delivery.isStaff(player);
    }

    public String tier(Player player) {
        return tiers.tierFor(player);
    }

    private void closeAndForward(SupportConversationState.Session session, UUID closedBy,
            String reason) {
        if (state.close(session.id()).isEmpty()) return;
        delivery.closed(session, reason.equals("timeout"));
        network.sendClose(session.id(), closedBy, reason);
    }

}
