package dev.chatalyst.support;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/** Folia-safe, localized delivery for support requests and private sessions. */
final class SupportDelivery {

    private static final String SUPPORT_PERMISSION = "chatalyst.support.staff";
    private final ChatalystPlugin plugin;
    private final ChannelManager channels;
    private final SupportTierPolicy tiers;

    SupportDelivery(ChatalystPlugin plugin, ChannelManager channels, SupportTierPolicy tiers) {
        this.plugin = plugin;
        this.channels = channels;
        this.tiers = tiers;
    }

    boolean isStaff(Player player) {
        return player.hasPermission(SUPPORT_PERMISSION)
                || player.hasPermission("chatalyst.channel.staff");
    }

    void request(SupportConversationState.Request request, String stage) {
        FoliaTasks.onlinePlayers(plugin,
                player -> isStaff(player) && tiers.receives(player, stage),
                player -> player.sendMessage(Lang.get(player,
                "support.request-alert", "player", MM.escape(request.userName()),
                "server", MM.escape(request.originServer()), "group", MM.escape(request.group()),
                "stage", MM.escape(stage), "message", MM.escape(request.message()),
                "id", request.id())));
    }

    void opened(SupportConversationState.Session session, boolean restored) {
        to(session.userId(), player -> player.sendMessage(Lang.get(player,
                restored ? "support.restored-user" : "support.opened-user",
                "staff", MM.escape(session.staffName()))));
        to(session.staffId(), player -> player.sendMessage(Lang.get(player,
                restored ? "support.restored-staff" : "support.opened-staff",
                "player", MM.escape(session.userName()))));
        if (!restored) notifyTaken(session);
    }

    void message(SupportConversationState.Session session, String senderName, String message) {
        to(session.userId(), player -> player.sendMessage(Lang.get(player,
                "support.private-user", "sender", MM.escape(senderName),
                "message", MM.escape(message))));
        to(session.staffId(), player -> player.sendMessage(Lang.get(player,
                "support.private-staff", "sender", MM.escape(senderName),
                "message", MM.escape(message))));
    }

    void closed(SupportConversationState.Session session, boolean timeout) {
        to(session.userId(), player -> {
            Channel general = channels.getDefault();
            if (general != null) channels.setWriteChannel(player, general);
            player.sendMessage(Lang.get(player,
                timeout ? "support.timeout-user" : "support.closed-user"));
        });
        to(session.staffId(), player -> player.sendMessage(Lang.get(player,
                timeout ? "support.timeout-staff" : "support.closed-staff")));
    }

    void requestExpired(UUID userId) {
        to(userId, player -> player.sendMessage(Lang.get(player, "support.request-expired")));
    }

    private void notifyTaken(SupportConversationState.Session session) {
        FoliaTasks.onlinePlayers(plugin,
                player -> isStaff(player) && !player.getUniqueId().equals(session.staffId()),
                player -> player.sendMessage(Lang.get(player, "support.request-taken",
                        "player", MM.escape(session.userName()),
                        "staff", MM.escape(session.staffName()))));
    }

    private void to(UUID id, Consumer<Player> action) {
        FoliaTasks.global(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) FoliaTasks.entity(plugin, player, action);
        });
    }
}
