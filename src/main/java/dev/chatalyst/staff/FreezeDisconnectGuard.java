package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Delayed, network-aware permanent-ban policy for abandoning an active review. */
final class FreezeDisconnectGuard {

    private static final String ROOT = "Staff_Moderation.Freeze.";
    private final StaffModerationModule module;

    FreezeDisconnectGuard(StaffModerationModule module) {
        this.module = module;
    }

    void schedule(Player player, org.bukkit.event.player.PlayerQuitEvent.QuitReason reason) {
        if (!module.plugin().getConfig().getBoolean(ROOT + "Ban_On_Disconnect", true)
                || !module.freezes().isFrozen(player.getUniqueId())) return;
        if (player.isOp() && StaffAccess.exemptsOps()) {
            module.plugin().getLogger().info("Skipping the freeze disconnect ban for operator "
                    + player.getName() + " (Staff_Moderation.Exempt_Ops is enabled).");
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        // Expulsado NO es "se fue": lo saco el servidor (kick del staff, AFK, anticheat), no el.
        // Antes eso acababa en ban permanente. El freeze se conserva y sigue congelado al volver.
        if (!banOnLeave(reason, module.plugin().getConfig().getBoolean(ROOT + "Ban_On_Kick", false))) {
            FoliaTasks.onlinePlayers(module.plugin(),
                    viewer -> StaffAccess.has(viewer, "chatalyst.command.freeze"),
                    viewer -> viewer.sendMessage(Lang.get(viewer,
                            "staff-moderation.freeze-kicked-kept", "player", MM.escape(name))));
            module.plugin().getLogger().info(name + " was kicked during a freeze: no ban, the "
                    + "freeze stays active for their next join.");
            return;
        }
        boolean spanish = Lang.isSpanish(player);
        long presenceRevision = presenceRevision(id);
        long seconds = Math.max(1L, Math.min(60L, module.plugin().getConfig()
                .getLong(ROOT + "Disconnect_Grace_Seconds", 3L)));
        // Detección visible al instante: el staff ve la desconexión y la cuenta atrás en el
        // momento, no recién cuando el ban cae tras el periodo de gracia.
        FoliaTasks.onlinePlayers(module.plugin(),
                viewer -> StaffAccess.has(viewer, "chatalyst.command.freeze"),
                viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-moderation.freeze-disconnect-pending",
                        "player", MM.escape(name), "seconds", seconds)));
        module.plugin().getServer().getConsoleSender().sendMessage(Lang.get(
                module.plugin().getServer().getConsoleSender(),
                "staff-moderation.freeze-disconnect-pending",
                "player", MM.escape(name), "seconds", seconds));
        FoliaTasks.globalLater(module.plugin(),
                () -> enforce(id, name, spanish, presenceRevision), seconds * 20L);
    }

    /**
     * Si esta salida cuenta como abandonar la revision. Solo una expulsion (KICKED) queda fuera,
     * y solo con Ban_On_Kick apagado: cortar la conexion o un timeout siguen siendo irse.
     */
    static boolean banOnLeave(org.bukkit.event.player.PlayerQuitEvent.QuitReason reason,
                              boolean banOnKick) {
        return banOnKick || reason != org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED;
    }

    private void enforce(UUID id, String lastKnownName, boolean spanish,
            long presenceRevision) {
        if (!module.enabled() || Bukkit.getPlayer(id) != null
                || presentOnNetwork(id, presenceRevision)) return;
        FreezeRecord freeze = module.freezes().consume(id);
        if (freeze == null) return;

        String reason = module.formatter().freezeDisconnectReason(spanish, freeze.reason());
        OfflinePlayer target = Bukkit.getOfflinePlayer(id);
        try {
            module.bans().ban(target, TimeUtil.PERMANENT, reason, "FreezeGuard");
        } catch (RuntimeException ex) {
            // BanManager persists the Chatalyst record before mirroring Paper's native list.
            // Restore the freeze only when neither layer retained a usable ban record.
            if (module.bans().get(id) == null) {
                module.freezes().freeze(id, freeze.reason(), freeze.staff(), freeze.staffUuid());
                String failure = "Could not ban " + lastKnownName
                + " after leaving a freeze; review state was restored: " + ex.getMessage();
                module.plugin().getLogger().severe(failure);
                return;
            }
        }
        String name = target.getName() == null ? lastKnownName : target.getName();
        // El motivo compuesto queda en el REGISTRO del ban (la pantalla del baneado, en su
        // idioma). Al staff se le difunde el motivo ORIGINAL del freeze: la envoltura
        // "abandonó la revisión" ya la pone la clave lang en el idioma de cada espectador.
        module.history().record(id, "freeze-ban", "FreezeGuard",
                module.formatter().plainReason(freeze.reason()));

        FoliaTasks.onlinePlayers(module.plugin(),
                viewer -> StaffAccess.has(viewer, "chatalyst.command.freeze"),
                viewer -> notify(viewer, name, freeze.reason()));
        module.plugin().getServer().getConsoleSender().sendMessage(module.formatter().feedback(
                module.plugin().getServer().getConsoleSender(),
                "staff-moderation.freeze-disconnect-banned", freeze.reason(),
                "player", MM.escape(name)));
        if (module.plugin().getStaffNotifier() != null) {
            module.plugin().getStaffNotifier().alert(name
                    + " disconnected during an active freeze and was permanently banned. Reason: "
                    + module.formatter().plainReason(freeze.reason()));
        }
        module.audit(Bukkit.getConsoleSender(), "freeze disconnect permaban",
                name + " " + module.formatter().plainReason(freeze.reason()));
    }

    private void notify(org.bukkit.command.CommandSender viewer, String name, String reason) {
        viewer.sendMessage(module.formatter().feedback(viewer,
                "staff-moderation.freeze-disconnect-banned", reason,
                "player", MM.escape(name)));
    }

    private long presenceRevision(UUID id) {
        NetworkPlayerDirectory directory = module.plugin().getNetworkPlayerDirectory();
        return directory == null ? 0L : directory.presenceRevision(id);
    }

    private boolean presentOnNetwork(UUID id, long scheduledRevision) {
        if (!module.plugin().getConfig().getBoolean(ROOT + "Check_Network_Presence", true)) {
            return false;
        }
        NetworkPlayerDirectory directory = module.plugin().getNetworkPlayerDirectory();
        return directory != null && (directory.isPresent(id)
                || directory.reconnectedAfter(id, scheduledRevision));
    }
}
