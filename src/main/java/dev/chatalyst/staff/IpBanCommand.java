package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.staff.tools.PunishmentBroadcast;
import dev.chatalyst.staff.tools.PunishmentIds;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * /banip y /unbanip: banean la CONEXION ademas de la cuenta. Acepta un nombre (usa el hash
 * de su ultima conexion conocida) o una IP literal (se hashea al instante y no se guarda).
 * Al banear se expulsa con la pantalla fancy a todo conectado que comparta esa conexion.
 */
final class IpBanCommand implements CommandExecutor, TabCompleter {

    private static final Pattern RAW_IPV4 =
            Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern RAW_IPV6 =
            Pattern.compile("^[0-9a-fA-F:]{2,45}$");

    private final StaffModerationModule module;

    IpBanCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)
                || !StaffCommandSupport.require(sender, "chatalyst.command.banip")) return true;
        boolean unban = command.getName().equalsIgnoreCase("unbanip");
        // -s: sin anuncio publico (solo el staff lo ve).
        PunishmentBroadcast.Parsed parsed = PunishmentBroadcast.parse(args);
        args = parsed.args();
        boolean silent = parsed.silent();
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, unban
                    ? "staff-moderation.unbanip-usage" : "staff-moderation.banip-usage",
                    "label", label));
            PunishmentSyntax.help(sender);
            return true;
        }
        return unban ? unban(sender, args[0]) : ban(sender, label, args, silent);
    }

    private boolean unban(CommandSender sender, String input) {
        String shown = shownName(input);
        String hash = resolveHash(input);
        if (hash == null) {
            sender.sendMessage(Lang.get(sender, rawIp(input)
                    ? "staff-moderation.unbanip-none" : "staff-moderation.banip-no-ip",
                    "player", MM.escape(shown)));
            return true;
        }
        boolean existed = module.ipBans().has(hash);
        if (existed && !module.ipBans().unban(hash)) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        sender.sendMessage(Lang.get(sender, existed
                ? "staff-moderation.unbanip-done" : "staff-moderation.unbanip-none",
                "player", MM.escape(shown)));
        if (existed) module.audit(sender, "unbanip", shown);
        return true;
    }

    private boolean ban(CommandSender sender, String label, String[] args, boolean silent) {
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
        String raw = args.length > reasonStart ? StaffCommandSupport.join(args, reasonStart)
                : module.formatter().defaultToken(ModerationFormatter.Screen.BAN);
        String reason = module.formatter().reason(sender, raw);
        String actorName = sender.getName();
        long selectedDuration = duration;

        if (rawIp(args[0])) {
            // IP literal: se hashea aqui mismo y jamas se persiste en claro.
            apply(sender, AltTracker.hashIp(args[0]), shownName(args[0]), null,
                    selectedDuration, reason, actorName, silent);
            return true;
        }
        OfflinePlayer target = NickManager.resolveOffline(args[0]);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            return StaffCommandSupport.unknown(sender, args[0]);
        }
        String targetName = target.getName() == null ? args[0] : target.getName();
        StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.ban")
                .whenComplete((allowed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(allowed)) {
                        onSender(sender, current -> current.sendMessage(
                                Lang.get(current, "staff-moderation.protected-target")));
                        return;
                    }
                    String hash = module.alts().lastHash(target.getUniqueId());
                    if (hash == null) {
                        onSender(sender, current -> current.sendMessage(Lang.get(current,
                                "staff-moderation.banip-no-ip",
                                "player", MM.escape(targetName))));
                        return;
                    }
                    apply(sender, hash, targetName, target, selectedDuration, reason,
                            actorName, silent);
                });
        return true;
    }

    /** Si un ban nuevo de {@code duration} debe sustituir al {@code existing} (o no hay). */
    static boolean replacesAccountBan(BanRecord existing, long duration) {
        if (existing == null) return true;
        if (existing.permanent()) return false;
        return duration == TimeUtil.PERMANENT || TimeUtil.deadline(duration) > existing.until();
    }

    /** Banea la conexion, banea la cuenta (si hay), y expulsa a todo el que la comparta. */
    private void apply(CommandSender sender, String hash, String shownName,
                       OfflinePlayer account, long duration, String reason,
                       String actorName, boolean silent) {
        if (!module.ipBans().ban(hash, account == null ? null : account.getUniqueId(),
                shownName, duration, reason, actorName)) {
            onSender(sender, current -> current.sendMessage(
                    Lang.get(current, "plugin.storage-unavailable")));
            return;
        }
        FoliaTasks.global(module.plugin(), () -> {
            // Tambien si el ban que ya tenia es MAS CORTO: un tempban de 1 h no puede hacer que
            // un /banip permanente se quede en una hora para la cuenta.
            long id = PunishmentIds.NONE;
            if (account != null && replacesAccountBan(module.bans().get(account.getUniqueId()),
                    duration)) {
                try {
                    id = module.bans().ban(account, duration, reason, actorName);
                } catch (RuntimeException failure) {
                    module.plugin().getLogger().warning("IP ban stored, but the account ban"
                            + " for " + shownName + " failed: " + failure.getMessage());
                }
            }
            if (account != null) {
                // El numero del ban de cuenta: el mismo que vera en su pantalla.
                id = module.history().record(account.getUniqueId(), "banip", actorName,
                        module.formatter().plainReason(reason), id, silent);
                // Antes del kick: su quit no debe activar el ban del FreezeGuard.
                module.endFreezeForBan(account.getUniqueId());
            }
            kickSharedConnections(hash, actorName, reason, id);
            PunishmentBroadcast.announce(module, duration == TimeUtil.PERMANENT
                            ? PunishmentBroadcast.Kind.BANIP : PunishmentBroadcast.Kind.TEMPBANIP,
                    sender, account == null ? null : account.getUniqueId(), shownName, reason,
                    duration == TimeUtil.PERMANENT ? "" : TimeUtil.formatElapsed(duration), id,
                    silent);
            long assigned = id;
            onSender(sender, current -> PunishmentCommand.tellId(current, assigned));
        });
        onSender(sender, current -> {
            module.audit(current, "banip", shownName + " "
                    + module.formatter().plainReason(reason));
            String durationText = duration == TimeUtil.PERMANENT
                    ? Lang.raw(current, "staff-moderation.duration-permanent")
                    : TimeUtil.formatElapsed(duration);
            current.sendMessage(module.formatter().feedback(current,
                    "staff-moderation.banip-done", reason,
                    "player", MM.escape(shownName), "duration", durationText));
        });
    }

    /** Todo conectado que comparta la conexion baneada sale con la pantalla de ban. */
    private void kickSharedConnections(String hash, String actorName, String reason, long id) {
        for (Player online : module.plugin().getServer().getOnlinePlayers()) {
            java.net.InetSocketAddress address = online.getAddress();
            if (address == null || address.getAddress() == null) continue;
            if (!hash.equals(AltTracker.hashIp(address.getAddress().getHostAddress()))) {
                continue;
            }
            if (online.isOp() && StaffAccess.exemptsOps()) continue;
            IpBanManager.IpBan ban = module.ipBans().get(hash);
            // Misma regla que en el login: un protegido contra el ban no sale por compartir
            // conexion con el baneado (salvo que sea la propia cuenta sancionada).
            if (ban != null && !ban.isTheBannedAccount(online)
                    && online.hasPermission("chatalyst.bypass.ban")) continue;
            long until = ban == null ? TimeUtil.PERMANENT : ban.until();
            long since = System.currentTimeMillis();
            FoliaTasks.entity(module.plugin(), online, current -> current.kick(
                    module.formatter().screen(current, ModerationFormatter.Screen.BAN,
                            current.getName(), actorName, until, reason, id, since)));
        }
    }

    private static boolean rawIp(String input) {
        return RAW_IPV4.matcher(input).matches()
                || (input.contains(":") && RAW_IPV6.matcher(input).matches());
    }

    /** Una IP literal nunca se re-imprime completa en mensajes ni en el store. */
    private static String shownName(String input) {
        if (!rawIp(input)) return input;
        String hash = AltTracker.hashIp(input);
        return "IP·" + (hash == null ? "?" : hash.substring(0, 8));
    }

    private String resolveHash(String input) {
        if (rawIp(input)) return AltTracker.hashIp(input);
        OfflinePlayer target = NickManager.resolveOffline(input);
        if (target == null) return null;
        return module.alts().lastHash(target.getUniqueId());
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
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        PunishmentSyntax.hint(sender, name, alias, args);
        return PunishmentSyntax.suggestions(sender, name, args,
                args.length == 1 ? StaffCommandSupport.onlineNames(sender) : List.of());
    }
}
