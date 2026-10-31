package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.staff.tools.PunishmentBroadcast;
import dev.chatalyst.staff.tools.PunishmentIds;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** Fancy /kick, /ban, /tempban and /unban. */
final class PunishmentCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    PunishmentCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)) return true;
        // -s (silencioso): el castigo no se anuncia al servidor, solo al
        // staff. Se quita de los argumentos antes de nada para que no acabe en el motivo.
        PunishmentBroadcast.Parsed parsed = PunishmentBroadcast.parse(args);
        String[] rest = parsed.args();
        boolean silent = parsed.silent();
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "kick" -> kick(sender, label, rest, silent);
            case "warn" -> warn(sender, label, rest, silent);
            case "ban", "tempban" -> ban(sender, command.getName(), label, rest, silent);
            case "unban" -> unban(sender, label, rest, silent);
            default -> false;
        };
    }

    /** Al staff que castiga: el numero con el que quedo, para citarlo en una apelacion. */
    static void tellId(CommandSender current, long id) {
        if (id <= PunishmentIds.NONE) return;
        String line = Lang.raw(current, "staff-tools.assigned-id", "id", PunishmentIds.label(id));
        if (!line.equals("staff-tools.assigned-id")) current.sendMessage(MM.full(line));
    }

    /**
     * Amonestacion manual: avisa al jugador con el motivo (en su idioma), suma puntos de
     * aviso (que disparan umbrales y escalacion como cualquier infraccion automatica) y
     * queda en el historial. El motivo es OBLIGATORIO: una amonestacion sin porque no educa.
     */
    private boolean warn(CommandSender sender, String label, String[] args, boolean silent) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.warn")) return true;
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.warn-usage", "label", label));
            PunishmentSyntax.help(sender);
            return true;
        }
        Player target = NickManager.online(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String reason = module.formatter().reason(sender, StaffCommandSupport.join(args, 1));
        String actorName = sender.getName();
        int points = Math.max(0, module.plugin().getConfig()
                .getInt("Warning_Points.Manual_Warn_Points", 1));
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.warn")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    String targetName = target.getName();
                    long id = module.history().record(target.getUniqueId(), "warn", actorName,
                            module.formatter().plainReason(reason), PunishmentIds.NONE, silent);
                    target.sendMessage(module.formatter().feedback(target,
                            "staff-moderation.warn-received", reason,
                            "staff", MM.escape(actorName)));
                    // add() dispara los umbrales configurados y la escalacion a ban.
                    if (points > 0 && module.plugin().getWarningPoints() != null) {
                        module.plugin().getWarningPoints().add(target, points);
                    }
                    PunishmentBroadcast.announce(module, PunishmentBroadcast.Kind.WARN, sender,
                            target.getUniqueId(), targetName, reason, "", id, silent);
                    onSender(sender, current -> {
                        tellId(current, id);
                        module.audit(current, "warn", targetName + " "
                                + module.formatter().plainReason(reason));
                        current.sendMessage(module.formatter().feedback(current,
                                "staff-moderation.warn-done", reason,
                                "player", MM.escape(targetName),
                                "points", module.plugin().getWarningPoints() == null
                                        ? 0 : module.plugin().getWarningPoints().get(target.getUniqueId())));
                    });
                });
        return true;
    }

    private boolean kick(CommandSender sender, String label, String[] args, boolean silent) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.kick")) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.kick-usage", "label", label));
            PunishmentSyntax.help(sender);
            return true;
        }
        Player target = NickManager.online(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        // Sin motivo escrito se persiste el TOKEN default: cada espectador lo ve en su idioma.
        String raw = args.length > 1 ? StaffCommandSupport.join(args, 1)
                : module.formatter().defaultToken(ModerationFormatter.Screen.KICK);
        String reason = module.formatter().reason(sender, raw);
        String actorName = sender.getName();
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.kick")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    // canAffectAsync completes this online branch on the target's entity
                    // scheduler, so permission checks, formatting and kick share its region.
                    String targetName = target.getName();
                    long id = module.history().record(target.getUniqueId(), "kick", actorName,
                            module.formatter().plainReason(reason), PunishmentIds.NONE, silent);
                    target.kick(module.formatter().screen(target,
                            ModerationFormatter.Screen.KICK, targetName, actorName,
                            TimeUtil.PERMANENT, reason, id, System.currentTimeMillis()));
                    // Acumulador: N expulsiones recientes escalan a un ban automatico.
                    // Se pasa el Player (aun valido este tick) para respetar exenciones.
                    module.escalation().afterKick(target);
                    PunishmentBroadcast.announce(module, PunishmentBroadcast.Kind.KICK, sender,
                            target.getUniqueId(), targetName, reason, "", id, silent);
                    onSender(sender, current -> {
                        tellId(current, id);
                        module.audit(current, "kick", targetName + " "
                                + module.formatter().plainReason(reason));
                        current.sendMessage(module.formatter().feedback(current,
                                "staff-moderation.kick-done", reason,
                                "player", MM.escape(targetName)));
                    });
                });
        return true;
    }

    private boolean ban(CommandSender sender, String name, String label, String[] args,
                        boolean silent) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.ban")) return true;
        boolean temporary = name.equalsIgnoreCase("tempban");
        if (args.length < (temporary ? 2 : 1)) {
            sender.sendMessage(Lang.get(sender, temporary
                    ? "staff-moderation.tempban-usage" : "staff-moderation.ban-usage",
                    "label", label));
            PunishmentSyntax.help(sender);
            return true;
        }
        OfflinePlayer target = StaffCommandSupport.resolveBanTarget(module, args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        long duration = temporary ? TimeUtil.parseDuration(args[1]) : TimeUtil.PERMANENT;
        if (temporary && (duration == 0 || duration == TimeUtil.PERMANENT)) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.invalid-duration"));
            return true;
        }
        int reasonStart = temporary ? 2 : 1;
        // /ban Pepe 7d motivo = temporal (y como ya hacia /banip). Lo que
        // no parece un tiempo es el principio del motivo: "/ban Pepe hacks" sigue siendo perm.
        if (!temporary && args.length >= 2) {
            long parsed = TimeUtil.parseDuration(args[1]);
            if (parsed != 0) {
                reasonStart = 2;
                if (parsed != TimeUtil.PERMANENT) {
                    temporary = true;
                    duration = parsed;
                }
            } else if (TimeUtil.looksLikeDuration(args[1])) {
                sender.sendMessage(Lang.get(sender, "staff-moderation.invalid-duration"));
                return true;
            }
        }
        // Sin motivo escrito se persiste el TOKEN default: cada espectador lo ve en su idioma.
        String raw = args.length > reasonStart ? StaffCommandSupport.join(args, reasonStart)
                : module.formatter().defaultToken(ModerationFormatter.Screen.BAN);
        String reason = module.formatter().reason(sender, raw);
        String requestedName = args[0];
        long banDuration = duration;
        boolean timed = temporary;

        String actorName = sender.getName();
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.ban")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    // LuckPerms may complete an offline lookup on its own executor. Paper's
                    // native ban list belongs on the global scheduler; an online target is
                    // instead handled on its entity scheduler.
                    Player online = target.getPlayer();
                    if (online != null) {
                        FoliaTasks.entity(module.plugin(), online,
                                current -> performBan(sender, actorName, current, banDuration,
                                        reason, timed, requestedName, silent),
                                () -> FoliaTasks.global(module.plugin(), () -> performBan(
                                        sender, actorName, target, banDuration, reason,
                                        timed, requestedName, silent)));
                    } else {
                        FoliaTasks.global(module.plugin(), () -> performBan(sender, actorName,
                                target, banDuration, reason, timed, requestedName, silent));
                    }
                });
        return true;
    }

    private void performBan(CommandSender sender, String actorName, OfflinePlayer target,
                            long duration, String reason, boolean temporary,
                            String requestedName, boolean silent) {
        // Un /tempban 1h sobre alguien con ban permanente (o de 30 dias) lo sustituia y lo
        // dejaba salir en una hora. Acortar un ban es una decision aparte: /unban y volver a
        // banear. La misma regla que ya usaba /banip para la cuenta.
        BanRecord existing = module.bans().get(target.getUniqueId());
        if (!IpBanCommand.replacesAccountBan(existing, duration)) {
            String shown = target.getName() == null ? requestedName : target.getName();
            onSender(sender, current -> current.sendMessage(Lang.get(current,
                    "staff-moderation.ban-longer-exists",
                    "player", MM.escape(shown),
                    "duration", StaffCommandSupport.remaining(current, existing.until()))));
            return;
        }
        long id = PunishmentIds.NONE;
        try {
            id = module.bans().ban(target, duration, reason, actorName);
        } catch (RuntimeException failure) {
            if (module.bans().get(target.getUniqueId()) == null) {
                module.plugin().getLogger().severe("Could not persist ban for "
                        + requestedName + ": " + failure.getMessage());
                onSender(sender, current -> current.sendMessage(
                        Lang.get(current, "plugin.storage-unavailable")));
                return;
            }
            // El registro durable de Chatalyst sigue bloqueando el login aunque el espejo
            // nativo de Paper haya fallado.
            module.plugin().getLogger().warning("Ban persisted, but Paper's native ban mirror"
                    + " failed for " + requestedName + ": " + failure.getMessage());
        }
        BanRecord ban = module.bans().get(target.getUniqueId());
        String targetName = target.getName() == null ? requestedName : target.getName();
        // El MISMO numero que la pantalla de ban: lo que el jugador cite al apelar es lo que
        // el staff encuentra en /history. Si el numero no se pudo guardar con el ban, se
        // pide uno para el historial.
        BanManager.BanStamp stamp = module.bans().stamp(target.getUniqueId());
        long number = module.history().record(target.getUniqueId(),
                temporary ? "tempban" : "ban", actorName,
                module.formatter().plainReason(reason),
                stamp == null ? id : stamp.id(), silent);
        // Antes de expulsarlo: su PlayerQuitEvent no debe activar el ban del FreezeGuard.
        module.endFreezeForBan(target.getUniqueId());

        Player online = target.getPlayer();
        if (online != null && ban != null) {
            FoliaTasks.entity(module.plugin(), online, player -> player.kick(
                    module.formatter().screen(player, ModerationFormatter.Screen.BAN,
                            player.getName(), actorName, ban.until(), reason, number,
                            System.currentTimeMillis())));
        }
        PunishmentBroadcast.announce(module, temporary
                        ? PunishmentBroadcast.Kind.TEMPBAN : PunishmentBroadcast.Kind.BAN,
                sender, target.getUniqueId(), targetName, reason,
                temporary ? TimeUtil.formatElapsed(duration) : "", number, silent);
        onSender(sender, current -> {
            tellId(current, number);
            module.audit(current, temporary ? "tempban" : "ban", targetName + " "
                    + module.formatter().plainReason(reason));
            String durationText = duration == TimeUtil.PERMANENT
                    ? Lang.raw(current, "staff-moderation.duration-permanent")
                    : TimeUtil.formatRemaining(TimeUtil.deadline(duration));
            current.sendMessage(module.formatter().feedback(current,
                    "staff-moderation.ban-done", reason,
                    "player", MM.escape(targetName), "duration", durationText));
        });
    }

    private void onSender(CommandSender sender,
                          java.util.function.Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(module.plugin(), player, action::accept);
        } else {
            FoliaTasks.global(module.plugin(), () -> action.accept(sender));
        }
    }

    private boolean unban(CommandSender sender, String label, String[] args, boolean silent) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.ban")) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.unban-usage", "label", label));
            PunishmentSyntax.help(sender);
            return true;
        }
        OfflinePlayer target = StaffCommandSupport.resolveBanTarget(module, args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String inputName = args[0];
        Runnable remove = () -> {
            BanManager.RemovalResult accountResult = module.bans().unbanResult(target);
            // Tambien la conexion que baneo esta cuenta (/banip): si no, el desbaneo no servia.
            int connections = module.ipBans() == null ? 0
                    : module.ipBans().unbanAccount(target.getUniqueId());
            BanManager.RemovalResult result = connections > 0
                    && accountResult == BanManager.RemovalResult.NOT_FOUND
                    ? BanManager.RemovalResult.REMOVED : accountResult;
            if (result == BanManager.RemovalResult.REMOVED) {
                module.history().record(target.getUniqueId(), "unban", sender.getName(),
                        connections > 0 ? "+ipban" : "", PunishmentIds.NONE, silent);
                String shown = target.getName() == null ? inputName : target.getName();
                PunishmentBroadcast.announce(module, PunishmentBroadcast.Kind.UNBAN, sender,
                        target.getUniqueId(), shown, "", "", PunishmentIds.NONE, silent);
            }
            onSender(sender, current -> {
                current.sendMessage(result == BanManager.RemovalResult.REMOVED
                        ? Lang.get(current, "staff-moderation.unban-done",
                                "player", MM.escape(inputName))
                        : result == BanManager.RemovalResult.NOT_FOUND
                        ? Lang.get(current, "staff-moderation.unban-none",
                                "player", MM.escape(inputName))
                        : Lang.get(current, "plugin.storage-unavailable"));
                if (result == BanManager.RemovalResult.REMOVED) {
                    module.audit(current, "unban", inputName
                            + (connections > 0 ? " (+" + connections + " ipban)" : ""));
                }
            });
        };
        Player online = target.getPlayer();
        if (online != null) {
            FoliaTasks.entity(module.plugin(), online, ignored -> remove.run(),
                    () -> FoliaTasks.global(module.plugin(), remove));
        } else {
            FoliaTasks.global(module.plugin(), remove);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        // El cliente pide sugerencias a cada tecla: es el momento de ir armando el comando.
        PunishmentSyntax.hint(sender, name, alias, args);
        if (name.equals("unban") && args.length == 1) {
            // Solo lectura de la cache: snapshot() escribe (retira caducados) y esto corre en el
            // hilo principal a cada tecla.
            return StaffCommandSupport.prefix(args[0], module.bans().cachedBannedNames());
        }
        return PunishmentSyntax.suggestions(sender, name, args,
                args.length == 1 ? StaffCommandSupport.onlineNames(sender) : List.of());
    }
}
