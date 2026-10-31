package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /freeze and /unfreeze review-state commands. */
final class FreezeCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    FreezeCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)
                || !StaffCommandSupport.require(sender, "chatalyst.command.freeze")) return true;
        boolean unfreeze = command.getName().equalsIgnoreCase("unfreeze");
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, unfreeze
                    ? "staff-moderation.unfreeze-usage" : "staff-moderation.freeze-usage",
                    "label", label));
            return true;
        }
        if (unfreeze) {
            OfflinePlayer target = NickManager.resolveOffline(args[0]);
            if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
            return unfreeze(sender, target, args[0]);
        }
        Player online = NickManager.online(args[0]);
        if (online != null) return freeze(sender, online, args);
        // Congelar en ausencia: el registro es durable por UUID, asi que al volver a entrar
        // el jugador ve el aviso, queda estatico, y salirse activa el ban del FreezeGuard.
        OfflinePlayer offline = NickManager.resolveOffline(args[0]);
        if (offline == null || !offline.hasPlayedBefore()) {
            return StaffCommandSupport.unknown(sender, args[0]);
        }
        return freezeOffline(sender, offline, args);
    }

    private boolean freeze(CommandSender sender, Player target, String[] args) {
        // Sin motivo escrito se persiste el TOKEN default: cada espectador lo ve en su idioma.
        String raw = args.length > 1 ? StaffCommandSupport.join(args, 1)
                : ModerationFormatter.freezeDefaultToken();
        String reason = module.formatter().reason(sender, raw);
        String actorName = sender.getName();
        String actorId = sender instanceof Player actor
                ? actor.getUniqueId().toString() : "console";
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.freeze")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    // The online access check completes on the target's entity scheduler.
                    String targetName = target.getName();
                    if (module.freezes().isFrozen(target.getUniqueId())) {
                        onSender(sender, current -> current.sendMessage(Lang.get(current,
                                "staff-moderation.freeze-already",
                                "player", MM.escape(targetName))));
                        return;
                    }
                    try {
                        module.freezes().freeze(target.getUniqueId(), reason, actorName, actorId);
                    } catch (RuntimeException storageFailure) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "plugin.storage-unavailable")));
                        return;
                    }
                    FreezeHold.apply(module.plugin(), target);
                    module.history().record(target.getUniqueId(), "freeze", actorName,
                            module.formatter().plainReason(reason));
                    module.freezeNotice().show(target, new FreezeRecord(reason, actorName, actorId));
                    FreezeAlerts.notifyStaff(module, targetName, reason, actorName, false);
                    onSender(sender, current -> {
                        module.audit(current, "freeze", targetName + " "
                                + module.formatter().plainReason(reason));
                        current.sendMessage(module.formatter().feedback(current,
                                "staff-moderation.freeze-done", reason,
                                "player", MM.escape(targetName)));
                    });
                });
        return true;
    }

    private boolean freezeOffline(CommandSender sender, OfflinePlayer target, String[] args) {
        String raw = args.length > 1 ? StaffCommandSupport.join(args, 1)
                : ModerationFormatter.freezeDefaultToken();
        String reason = module.formatter().reason(sender, raw);
        String actorName = sender.getName();
        String actorId = sender instanceof Player actor
                ? actor.getUniqueId().toString() : "console";
        String targetName = target.getName() == null ? args[0] : target.getName();
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.freeze")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    if (module.freezes().isFrozen(target.getUniqueId())) {
                        onSender(sender, current -> current.sendMessage(Lang.get(current,
                                "staff-moderation.freeze-already",
                                "player", MM.escape(targetName))));
                        return;
                    }
                    try {
                        module.freezes().freeze(target.getUniqueId(), reason, actorName, actorId);
                    } catch (RuntimeException storageFailure) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "plugin.storage-unavailable")));
                        return;
                    }
                    module.history().record(target.getUniqueId(), "freeze", actorName,
                            module.formatter().plainReason(reason));
                    FreezeAlerts.notifyStaff(module, targetName, reason, actorName, false);
                    onSender(sender, current -> {
                        module.audit(current, "freeze offline", targetName + " "
                                + module.formatter().plainReason(reason));
                        current.sendMessage(module.formatter().feedback(current,
                                "staff-moderation.freeze-done-offline", reason,
                                "player", MM.escape(targetName)));
                    });
                });
        return true;
    }

    private boolean unfreeze(CommandSender sender, OfflinePlayer target, String inputName) {
        String name = target.getName() == null ? inputName : target.getName();
        FreezeManager.RemovalResult result =
                module.freezes().unfreezeResult(target.getUniqueId());
        sender.sendMessage(result == FreezeManager.RemovalResult.REMOVED
                ? Lang.get(sender, "staff-moderation.unfreeze-done",
                        "player", MM.escape(name))
                : result == FreezeManager.RemovalResult.NOT_FOUND
                ? Lang.get(sender, "staff-moderation.unfreeze-none",
                        "player", MM.escape(name))
                : Lang.get(sender, "plugin.storage-unavailable"));
        if (result == FreezeManager.RemovalResult.REMOVED) {
            module.audit(sender, "unfreeze", name);
            module.history().record(target.getUniqueId(), "unfreeze", sender.getName(), "");
            Player online = target.getPlayer();
            if (online != null) {
                FreezeHold.release(module.plugin(), online);
                module.freezeNotice().clear(online);
                FoliaTasks.entity(module.plugin(), online, current -> current.sendMessage(
                        Lang.get(current, "staff-moderation.unfreeze-target")));
            }
        }
        return true;
    }

    private void onSender(CommandSender sender,
                          java.util.function.Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(module.plugin(), player, action::accept);
        } else {
            FoliaTasks.global(module.plugin(), () -> action.accept(sender));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1
                ? StaffCommandSupport.prefix(args[0], StaffCommandSupport.onlineNames(sender))
                : List.of();
    }
}
