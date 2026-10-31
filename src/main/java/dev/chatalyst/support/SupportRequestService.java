package dev.chatalyst.support;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.proxy.SupportPacketCodec;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Creates natural-language requests and performs permission-checked staff claims. */
final class SupportRequestService {

    private final SupportConversationState state;
    private final SupportDelivery delivery;
    private final SupportNetworkCoordinator network;
    private final SupportRuntime runtime;
    private final SupportEscalation escalation;

    SupportRequestService(SupportConversationState state, SupportDelivery delivery,
            SupportNetworkCoordinator network, SupportRuntime runtime,
            SupportEscalation escalation) {
        this.state = state;
        this.delivery = delivery;
        this.network = network;
        this.runtime = runtime;
        this.escalation = escalation;
    }

    SupportConversationManager.RequestResult request(Player user, String group, String rawMessage) {
        if (!runtime.enabled()) return SupportConversationManager.RequestResult.DISABLED;
        if (state.sessionFor(user.getUniqueId()).isPresent()) {
            return SupportConversationManager.RequestResult.IN_SESSION;
        }
        String message = runtime.clean(rawMessage);
        if (message.isEmpty()) return SupportConversationManager.RequestResult.DISABLED;
        long now = System.currentTimeMillis();
        var request = new SupportConversationState.Request(UUID.randomUUID(), now,
                runtime.serverId(), user.getUniqueId(), user.getName(), limit(group, 32), message);
        if (!state.submit(request)) return SupportConversationManager.RequestResult.ALREADY_WAITING;
        escalation.start(request);
        return SupportConversationManager.RequestResult.CREATED;
    }

    void accept(Player staff, UUID requestId) {
        if (!delivery.isStaff(staff)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        Optional<SupportConversationState.Request> request = state.pending(requestId);
        // Atender la peticion propia abriria una conversacion consigo mismo, y de paso la
        // quitaria de la cola del resto del staff.
        if (request.isEmpty() || request.get().userId().equals(staff.getUniqueId())) {
            staff.sendMessage(Lang.get(staff, "support.request-unavailable"));
            return;
        }
        var claim = new SupportPacketCodec.Claim(UUID.randomUUID(), System.currentTimeMillis(),
                requestId, staff.getUniqueId(), staff.getName(), runtime.serverId());
        network.sendClaim(claim);
        staff.sendMessage(Lang.get(staff, "support.claim-sent"));
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
