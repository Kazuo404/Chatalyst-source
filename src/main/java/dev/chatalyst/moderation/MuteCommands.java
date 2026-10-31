package dev.chatalyst.moderation;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mute [-s] <jugador> [tiempo] [razon]   (tiempo: 10m, 1h, 1d, perm; por defecto permanente)
 * /unmute <jugador>
 *
 * <p>El flag -s aplica un shadow mute (soft): el muteado sigue viendo sus propios mensajes
 * pero nadie mas los recibe, y no se le notifica nada.
 */
public final class MuteCommands implements CommandExecutor, TabCompleter {

    private final MuteManager mutes;
    private final StaffModerationModule moderation;

    public MuteCommands(MuteManager mutes, StaffModerationModule moderation) {
        this.mutes = mutes;
        this.moderation = moderation;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        boolean unmute = command.getName().equalsIgnoreCase("unmute");
        if (!StaffAccess.has(sender, "chatalyst.command.mute")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        // Flag -s (shadow/soft mute) en las tres primeras posiciones (antes del nombre, tras el
        // nombre o tras la duracion). Mas alla ya es el MOTIVO: "/mute X spam -s fue sin
        // querer" no puede convertirse en un shadow mute por una palabra del texto.
        boolean soft = false;
        if (!unmute) {
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (i < 3 && !soft && (a.equalsIgnoreCase("-s") || a.equalsIgnoreCase("-soft"))) {
                    soft = true;
                } else {
                    rest.add(a);
                }
            }
            if (soft) args = rest.toArray(new String[0]);
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, unmute ? "mute.usage-unmute" : "mute.usage", "label", label));
            return true;
        }
        OfflinePlayer target = dev.chatalyst.nick.NickManager.resolveOffline(args[0]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[0]));
            return true;
        }

        if (unmute) {
            String inputName = args[0];
            Runnable remove = () -> {
                MuteManager.RemovalResult result =
                        mutes.unmuteResult(target.getUniqueId());
                // Levantar una sancion tambien es historial: sin esto nadie sabia QUIEN la quito.
                if (result == MuteManager.RemovalResult.REMOVED) {
                    moderation.history().record(target.getUniqueId(), "unmute",
                            sender.getName(), "");
                }
                onSender(sender, current -> {
                    current.sendMessage(result == MuteManager.RemovalResult.REMOVED
                            ? Lang.get(current, "mute.unmute-done", "player", inputName)
                            : result == MuteManager.RemovalResult.NOT_FOUND
                            ? Lang.get(current, "mute.unmute-none")
                            : Lang.get(current, "plugin.storage-unavailable"));
                    if (result == MuteManager.RemovalResult.REMOVED
                            && dev.chatalyst.ChatalystPlugin.get() != null
                            && dev.chatalyst.ChatalystPlugin.get()
                                    .getStaffSecurityListener() != null) {
                        dev.chatalyst.ChatalystPlugin.get().getStaffSecurityListener()
                                .audit(current, "unmute", inputName);
                    }
                });
            };
            Player online = target.getPlayer();
            if (online != null) {
                FoliaTasks.entity(moderation.plugin(), online, ignored -> remove.run(),
                        () -> FoliaTasks.global(moderation.plugin(), remove));
            } else {
                FoliaTasks.global(moderation.plugin(), remove);
            }
            return true;
        }

        long duration = TimeUtil.PERMANENT;
        int reasonStart = 1;
        if (args.length >= 2) {
            long parsed = TimeUtil.parseDuration(args[1]);
            if (parsed != 0) {
                duration = parsed;
                reasonStart = 2;
            } else if (TimeUtil.looksLikeDuration(args[1])) {
                sender.sendMessage(Lang.get(sender, "staff-moderation.invalid-duration"));
                return true;
            }
        }
        // Sin motivo escrito se persiste el TOKEN default: cada espectador lo ve en su idioma.
        String rawReason = args.length > reasonStart
                ? String.join(" ", java.util.Arrays.copyOfRange(args, reasonStart, args.length))
                : moderation.formatter().defaultToken(
                        dev.chatalyst.staff.ModerationFormatter.Screen.MUTE);
        String reason = moderation.formatter().reason(sender, rawReason);
        long selectedDuration = duration;
        boolean selectedSoft = soft;
        String inputName = args[0];
        String actorName = sender.getName();
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.mute")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "mute.cannot")));
                        return;
                    }
                    Player online = target.getPlayer();
                    if (online != null) {
                        FoliaTasks.entity(moderation.plugin(), online,
                                current -> performMute(sender, target, current,
                                        selectedDuration, reason, actorName,
                                        selectedSoft, inputName),
                                () -> FoliaTasks.global(moderation.plugin(), () ->
                                        performMute(sender, target, null, selectedDuration,
                                                reason, actorName, selectedSoft, inputName)));
                    } else {
                        FoliaTasks.global(moderation.plugin(), () -> performMute(sender,
                                target, null, selectedDuration, reason, actorName,
                                selectedSoft, inputName));
                    }
                });
        return true;
    }

    private void performMute(CommandSender sender, OfflinePlayer target, Player online,
                             long duration, String reason, String actorName,
                             boolean soft, String inputName) {
        if (!mutes.mute(target, duration, reason, actorName, soft)) {
            onSender(sender, current -> current.sendMessage(
                    Lang.get(current, "plugin.storage-unavailable")));
            return;
        }
        moderation.history().record(target.getUniqueId(), soft ? "softmute" : "mute",
                actorName, moderation.formatter().plainReason(reason));
        // El shadow mute nunca se anuncia al objetivo: enterarse arruinaria el efecto.
        if (online != null && !soft) {
            Mute mute = mutes.getMute(target.getUniqueId());
            long until = mute == null ? TimeUtil.PERMANENT : mute.until;
            online.sendMessage(moderation.formatter().screen(online,
                    dev.chatalyst.staff.ModerationFormatter.Screen.MUTE,
                    online.getName(), actorName, until, reason));
        }
        onSender(sender, current -> {
            String durationText = duration == TimeUtil.PERMANENT
                    ? Lang.raw(current, "mute.duration-permanent")
                    : TimeUtil.formatRemaining(TimeUtil.deadline(duration));
            if (dev.chatalyst.ChatalystPlugin.get() != null
                    && dev.chatalyst.ChatalystPlugin.get().getStaffSecurityListener() != null) {
                dev.chatalyst.ChatalystPlugin.get().getStaffSecurityListener().audit(current,
                        soft ? "softmute" : "mute",
                        inputName + " " + durationText + " " + reason);
            }
            current.sendMessage(moderation.formatter().feedback(current,
                    soft ? "mute.done-soft" : "mute.done", reason,
                    "player", dev.chatalyst.util.MM.escape(inputName),
                    "duration", durationText));
        });
    }

    private void onSender(CommandSender sender,
                          java.util.function.Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(moderation.plugin(), player, action::accept);
        } else {
            FoliaTasks.global(moderation.plugin(), () -> action.accept(sender));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, "chatalyst.command.mute")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(p)
                        && !StaffAccess.canSeeVanished(sender)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
            }
        } else if (args.length == 2 && !command.getName().equalsIgnoreCase("unmute")) {
            out.addAll(List.of("10m", "1h", "1d", "7d", "perm", "-s"));
        }
        return out;
    }
}
