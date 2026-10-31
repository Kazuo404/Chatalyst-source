package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Personal and delegated Chatalyst vanish command. */
final class VanishCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    VanishCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)
                || !StaffCommandSupport.require(sender, "chatalyst.command.vanish")) return true;
        if (!(sender instanceof Player) && args.length == 0) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.vanish-usage", "label", label));
            return true;
        }

        Player target = sender instanceof Player player ? player : null;
        int stateArg = 0;
        if (args.length > 0 && !isState(args[0])) {
            if (!StaffCommandSupport.require(sender, "chatalyst.command.vanish.others")) return true;
            target = NickManager.online(args[0]);
            if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
            stateArg = 1;
        }
        if (target == null) return true;
        Player selectedTarget = target;
        int selectedStateArg = stateArg;
        FoliaTasks.entity(module.plugin(), selectedTarget, current -> {
            if (current.isOp() && StaffAccess.exemptsOps() && !sender.equals(current)) {
                onSender(sender, actor -> actor.sendMessage(
                        Lang.get(actor, "staff-moderation.protected-target")));
                return;
            }
            boolean fallback = !module.vanish().isOwned(current);
            boolean next = selectedStateArg < args.length
                    ? state(args[selectedStateArg], fallback) : fallback;
            if (!module.vanish().set(current, next)) {
                onSender(sender, actor -> actor.sendMessage(
                        Lang.get(actor, "plugin.storage-unavailable")));
                return;
            }
            if (module.staffMode().isActive(current)) module.staffMode().giveKit(current);
            String targetName = current.getName();
            if (!sender.equals(current)) {
                current.sendMessage(Lang.get(current, next
                        ? "staff-moderation.vanish-on" : "staff-moderation.vanish-off"));
            }
            onSender(sender, actor -> {
                actor.sendMessage(Lang.get(actor, next
                        ? "staff-moderation.vanish-on-target"
                        : "staff-moderation.vanish-off-target",
                        "player", MM.escape(targetName)));
                module.audit(actor, next ? "vanish on" : "vanish off", targetName);
            });
        }, () -> onSender(sender, actor -> actor.sendMessage(
                Lang.get(actor, "common.player-offline"))));
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
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("on", "off"));
            if (StaffAccess.has(sender, "chatalyst.command.vanish.others")) {
                values.addAll(StaffCommandSupport.onlineNames(sender));
            }
            return StaffCommandSupport.prefix(args[0], values);
        }
        return args.length == 2
                ? StaffCommandSupport.prefix(args[1], List.of("on", "off")) : List.of();
    }

    private static boolean isState(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "off", "true", "false", "activar", "desactivar" -> true;
            default -> false;
        };
    }

    private static boolean state(String value, boolean fallback) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "activar" -> true;
            case "off", "false", "desactivar" -> false;
            default -> fallback;
        };
    }
}
