package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.proxybridge.StaffNetworkCodec;
import dev.chatalyst.support.SupportConversationManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Vanish;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.UUID;

/** Authorizes and completes local or proxy-assisted administrative teleports. */
final class NetworkTeleportService {

    private static final String STAFF_PERMISSION = "chatalyst.admin.staff.teleport";
    private static final String PLAYER_PERMISSION = "chatalyst.admin.players.teleport";
    private final ChatalystPlugin plugin;
    private final ProxyManager proxy;
    private final SupportConversationManager support;

    NetworkTeleportService(ChatalystPlugin plugin, ProxyManager proxy,
            SupportConversationManager support) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.support = support;
    }

    void request(Player requester, UUID targetId, String targetName, String server,
            String scope) {
        if (!StaffAccess.has(requester, permission(scope))) {
            requester.sendMessage(Lang.get(requester, "common.no-permission"));
            return;
        }
        Player local = plugin.getServer().getPlayer(targetId);
        if (local != null) {
            complete(requester, local, scope);
            return;
        }
        if (!proxy.usesNativeStaffBridge()) {
            requester.sendMessage(Lang.get(requester, "staff-directory.unavailable"));
            return;
        }
        try {
            proxy.forwardStaff(StaffNetworkCodec.encode(new StaffNetworkCodec.TeleportRequest(
                UUID.randomUUID(), requester.getUniqueId(), targetId, scope)));
            requester.sendMessage(Lang.get(requester, "staff-directory.connecting",
                "player", targetName, "server", server));
        } catch (IOException ex) {
            requester.sendMessage(Lang.get(requester, "staff-directory.connect-failed"));
        }
    }

    void arrive(StaffNetworkCodec.TeleportArrival packet) {
        FoliaTasks.globalLater(plugin, () -> {
            Player requester = plugin.getServer().getPlayer(packet.requesterId());
            Player target = plugin.getServer().getPlayer(packet.targetId());
            if (requester == null) return;
            FoliaTasks.entity(plugin, requester, player -> arrive(player, target, packet.scope()));
        }, 10L);
    }

    void result(StaffNetworkCodec.TeleportResult packet) {
        Player player = plugin.getServer().getPlayer(packet.requesterId());
        if (player == null) return;
        FoliaTasks.entity(plugin, player, value -> value.sendMessage(Lang.get(value,
                "staff-directory." + packet.result())));
    }

    private void arrive(Player requester, Player target, String scope) {
        if (!authorized(requester, scope)) {
            requester.sendMessage(Lang.get(requester, "common.no-permission"));
            return;
        }
        if (target == null) {
            requester.sendMessage(Lang.get(requester, "staff-directory.target-offline"));
            return;
        }
        complete(requester, target, scope);
    }

    private void complete(Player requester, Player target, String scope) {
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            requester.sendMessage(Lang.get(requester, "staff-directory.self"));
            return;
        }
        // Permisos del solicitante se leen en SU region; estado/ubicacion del objetivo en la
        // region del objetivo. La llamada de teleport vuelve despues a la region solicitante.
        boolean canSeeVanished = StaffAccess.canSeeVanished(requester);
        FoliaTasks.entity(plugin, target, selected -> {
            if (!visibleTarget(selected, scope, canSeeVanished)) {
                notifyUnavailable(requester);
                return;
            }
            String name = selected.getName();
            org.bukkit.Location destination = selected.getLocation().clone();
            FoliaTasks.entity(plugin, requester, player -> {
                if (!authorized(player, scope)) {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                    return;
                }
                player.teleportAsync(destination).thenAccept(success ->
                        notifyResult(player, success, name));
            });
        }, () -> notifyUnavailable(requester));
    }

    private void notifyResult(Player requester, boolean success, String targetName) {
        FoliaTasks.entity(plugin, requester, player -> player.sendMessage(Lang.get(player,
                success ? "staff-directory.teleported" : "staff-directory.connect-failed",
                "player", targetName)));
    }

    private boolean visibleTarget(Player target, String scope, boolean canSeeVanished) {
        boolean correctType = !"staff".equals(scope) || support.isStaff(target);
        boolean visible = !Vanish.isVanished(target) || canSeeVanished;
        return correctType && visible;
    }

    private void notifyUnavailable(Player requester) {
        FoliaTasks.entity(plugin, requester, player -> player.sendMessage(
                Lang.get(player, "staff-directory.target-offline")));
    }

    private static String permission(String scope) {
        return "staff".equals(scope) ? STAFF_PERMISSION : PLAYER_PERMISSION;
    }

    private boolean authorized(Player requester, String scope) {
        String server = plugin.getNetworkMonitor().serverId();
        boolean serverAllowed = StaffAccess.has(requester, "chatalyst.admin.players.server.*")
                || StaffAccess.has(requester, "chatalyst.admin.players.server." + server);
        return StaffAccess.has(requester, permission(scope))
                && (!"player".equals(scope) || serverAllowed);
    }
}
