package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.staff.AltTracker;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetSocketAddress;

/**
 * La otra mitad de /ipmute: quien entra por una conexion silenciada queda silenciado, aunque
 * sea una cuenta nueva que nunca se habia visto. Sin esto, un /ipmute solo alcanzaba a las
 * cuentas ya conocidas y la cuenta de recambio hablaba al minuto.
 *
 * <p>Se respeta lo mismo que en el comando: el OP exento y {@code chatalyst.bypass.mute} no
 * caen por compartir conexion, y un mute propio que ya dure mas no se acorta.
 */
public final class IpMuteListener implements Listener {

    private final StaffModerationModule module;

    public IpMuteListener(StaffModerationModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!module.enabled()) return;
        Player player = event.getPlayer();
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return;
        String hash = AltTracker.hashIp(address.getAddress().getHostAddress());
        MuteManager mutes = module.plugin().getMuteManager();
        if (hash == null || mutes == null) return;
        LookupSupport.async(module, () -> {
            MuteManager.IpMute ipMute = mutes.ipMuteOf(hash);
            if (ipMute == null) return;
            Mute current = mutes.getMute(player.getUniqueId());
            if (current != null && !IpMuteCommand.lastsLess(current.until, ipMute.until())) return;
            // Los permisos se miran en la region del jugador; la escritura durable vuelve
            // fuera del hilo del servidor (en MySQL es un viaje a la base por cada entrada).
            FoliaTasks.entity(module.plugin(), player, joined -> {
                if (joined.isOp() && StaffAccess.exemptsOps()) return;
                if (joined.hasPermission("chatalyst.bypass.mute")) return;
                String name = joined.getName();
                LookupSupport.async(module, () -> apply(mutes, joined, name, ipMute, hash));
            });
        });
    }

    private void apply(MuteManager mutes, Player joined, String name, MuteManager.IpMute ipMute,
                       String hash) {
        // Hasta el MISMO instante que la conexion: asi la siguiente entrada ya lo encuentra
        // silenciado "hasta lo mismo" y no lo vuelve a silenciar (ni a apuntarlo en el
        // historial ni a avisar al staff) cada vez que entra.
        if (!mutes.muteUntil(joined, ipMute.until(), ipMute.reason(), ipMute.by())) return;
        module.history().record(joined.getUniqueId(), "mute", ipMute.by(),
                module.formatter().plainReason(ipMute.reason()) + " (ipmute)");
        FoliaTasks.entity(module.plugin(), joined, mutes::notifyIfMuted);
        FoliaTasks.onlinePlayers(module.plugin(),
                viewer -> StaffAccess.has(viewer, "chatalyst.command.ipmute"),
                viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-lookup.ipmute-applied-join", "player", MM.escape(name),
                        "id", SharedConnections.shortId(hash))));
    }
}
