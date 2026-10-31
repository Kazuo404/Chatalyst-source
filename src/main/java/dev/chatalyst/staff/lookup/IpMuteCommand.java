package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.staff.AltTracker;
import dev.chatalyst.staff.ModerationFormatter;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * /ipmute <jugador|ip> [tiempo] [motivo] y /unipmute <jugador|ip>.
 *
 * <p>Silencia la CONEXION: todas las cuentas que entraron desde ella quedan silenciadas ya, y
 * las que entren despues por ella tambien ({@link IpMuteListener}). Como en /banip, una IP
 * escrita a mano se hashea al momento y nunca se guarda ni se repite en un mensaje.
 *
 * <p>Las cuentas protegidas contra el mute (OP exento, {@code chatalyst.bypass.mute}) no caen
 * por compartir conexion; el objetivo nombrado, si esta protegido, para el comando entero.
 */
public final class IpMuteCommand implements CommandExecutor, TabCompleter {

    private static final Pattern RAW_IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern RAW_IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,45}$");
    private static final String BYPASS = "chatalyst.bypass.mute";

    private final StaffModerationModule module;

    public IpMuteCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // /unipmute comparte el permiso de /ipmute, igual que /unbanip con /banip.
        if (!LookupSupport.allowed(module, sender, "ipmute")) return true;
        boolean lift = command.getName().equalsIgnoreCase("unipmute");
        if (args.length < 1) {
            LookupSupport.usage(sender, label, lift ? "<jugador|ip>"
                    : "<jugador|ip> [tiempo] [motivo]");
            return true;
        }
        MuteManager mutes = module.plugin().getMuteManager();
        if (mutes == null) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        if (lift) {
            unmute(sender, mutes, args[0]);
        } else {
            mute(sender, mutes, args);
        }
        return true;
    }

    // ---- /ipmute ---------------------------------------------------------------------------

    private void mute(CommandSender sender, MuteManager mutes, String[] args) {
        long duration = TimeUtil.PERMANENT;
        int reasonStart = 1;
        if (args.length >= 2) {
            long parsed = TimeUtil.parseDuration(args[1]);
            if (parsed != 0) {
                duration = parsed;
                reasonStart = 2;
            } else if (TimeUtil.looksLikeDuration(args[1])) {
                sender.sendMessage(Lang.get(sender, "staff-moderation.invalid-duration"));
                return;
            }
        }
        String raw = args.length > reasonStart
                ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length))
                : module.formatter().defaultToken(ModerationFormatter.Screen.MUTE);
        String reason = module.formatter().reason(sender, raw);
        String actor = sender.getName();
        long selectedDuration = duration;
        String input = args[0];

        LookupSupport.async(module, () -> {
            Resolved resolved = resolveConnection(sender, input);
            if (resolved == null) return;
            CompletableFuture<Boolean> allowed = resolved.account() == null
                    ? CompletableFuture.completedFuture(true)
                    : StaffAccess.canAffectAsync(sender,
                            Bukkit.getOfflinePlayer(resolved.account()), BYPASS);
            allowed.whenComplete((ok, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(ok)) {
                    LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(
                            Lang.get(viewer, "staff-moderation.protected-target")));
                    return;
                }
                LookupSupport.async(module, () -> apply(sender, mutes, resolved,
                        selectedDuration, reason, actor));
            });
        });
    }

    private void apply(CommandSender sender, MuteManager mutes, Resolved resolved,
                       long duration, String reason, String actor) {
        if (!mutes.ipMute(resolved.hash(), duration, reason, actor)) {
            LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(
                    Lang.get(viewer, "plugin.storage-unavailable")));
            return;
        }
        MuteManager.IpMute ipMute = mutes.ipMuteOf(resolved.hash());
        long until = ipMute == null ? TimeUtil.deadline(duration) : ipMute.until();
        List<UUID> accounts = new ArrayList<>();
        for (AltTracker.Account account : module.alts().accountsOn(resolved.hash())) {
            accounts.add(account.id());
        }
        if (resolved.account() != null && !accounts.contains(resolved.account())) {
            accounts.add(resolved.account());
        }
        List<CompletableFuture<Boolean>> applied = new ArrayList<>();
        for (UUID account : accounts) {
            applied.add(muteAccount(mutes, account, until, reason, actor,
                    account.equals(resolved.account())));
        }
        CompletableFuture.allOf(applied.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    int muted = (int) applied.stream()
                            .filter(future -> Boolean.TRUE.equals(future.getNow(false))).count();
                    LookupSupport.reply(module, sender, viewer -> {
                        String durationText = duration == TimeUtil.PERMANENT
                                ? Lang.raw(viewer, "staff-moderation.duration-permanent")
                                : TimeUtil.formatElapsed(duration);
                        viewer.sendMessage(module.formatter().feedback(viewer,
                                "staff-lookup.ipmute-done", reason,
                                "player", MM.escape(resolved.shown()),
                                "id", SharedConnections.shortId(resolved.hash()),
                                "accounts", muted, "duration", durationText));
                        module.audit(viewer, "ipmute", resolved.shown() + " ("
                                + muted + " accounts) " + module.formatter().plainReason(reason));
                    });
                });
    }

    /**
     * Silencia una cuenta de la conexion si no esta protegida y si su mute actual no dura ya
     * mas (un /ipmute de una hora no puede acortar el mute permanente de una de ellas).
     */
    private CompletableFuture<Boolean> muteAccount(MuteManager mutes, UUID account, long until,
                                                   String reason, String actor,
                                                   boolean alreadyChecked) {
        Mute current = mutes.getMute(account);
        if (current != null && !lastsLess(current.until, until)) {
            return CompletableFuture.completedFuture(false);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(account);
        CompletableFuture<Boolean> allowed = alreadyChecked
                ? CompletableFuture.completedFuture(true)
                : protectedFromConnection(offline);
        return allowed.thenCompose(ok -> {
            if (!Boolean.TRUE.equals(ok)) return CompletableFuture.completedFuture(false);
            // La escritura (durable, en MySQL un viaje a la base por cuenta) va FUERA del hilo
            // del servidor: una conexion con diez cuentas eran diez esperas en el hilo
            // principal. Solo el aviso al jugador vuelve a su region.
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            LookupSupport.async(module, () -> {
                try {
                    // Hasta el MISMO instante que el silencio de la conexion (ver IpMuteRules).
                    if (!mutes.muteUntil(offline, until, reason, actor)) {
                        done.complete(false);
                        return;
                    }
                    module.history().record(account, "mute", actor,
                            module.formatter().plainReason(reason) + " (ipmute)");
                    Player online = offline.getPlayer();
                    if (online != null) {
                        FoliaTasks.entity(module.plugin(), online, mutes::notifyIfMuted);
                    }
                    done.complete(true);
                } catch (RuntimeException failure) {
                    done.complete(false);
                }
            });
            // Apagandose: async() no llega a correr y nadie completaria el futuro.
            done.completeOnTimeout(false, 30, java.util.concurrent.TimeUnit.SECONDS);
            return done;
        }).exceptionally(failure -> false);
    }

    /**
     * Las demas cuentas de la conexion: se respeta su bypass como si las silenciara un staff
     * no-OP. canAffectAsync con la consola siempre dice que si, asi que el bypass se mira
     * aparte (en su region si esta conectada, en LuckPerms si no).
     */
    private CompletableFuture<Boolean> protectedFromConnection(OfflinePlayer account) {
        Player online = account.getPlayer();
        if (online != null) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            boolean scheduled = FoliaTasks.entity(module.plugin(), online, current -> result.complete(
                    !(current.isOp() && StaffAccess.exemptsOps())
                            && !current.hasPermission(BYPASS)), () -> result.complete(false));
            if (!scheduled) result.complete(false);
            return result;
        }
        if (account.isOp() && StaffAccess.exemptsOps()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!dev.chatalyst.hook.LuckPermsHook.isAvailable()) {
            return CompletableFuture.completedFuture(true);
        }
        return dev.chatalyst.hook.LuckPermsHook.hasPermissionAsync(account.getUniqueId(), BYPASS)
                .thenApply(has -> !has).exceptionally(failure -> false);
    }

    /** Si un mute que acaba en {@code current} dura menos que uno hasta {@code until}. */
    static boolean lastsLess(long current, long until) {
        return IpMuteRules.lastsLess(current, until);
    }

    // ---- /unipmute -------------------------------------------------------------------------

    private void unmute(CommandSender sender, MuteManager mutes, String input) {
        String actor = sender.getName();
        LookupSupport.async(module, () -> {
            Resolved resolved = resolveConnection(sender, input);
            if (resolved == null) return;
            MuteManager.IpMute removed = mutes.removeIpMute(resolved.hash());
            if (removed == null) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-lookup.unipmute-none", "player", MM.escape(resolved.shown()))));
                return;
            }
            // Solo se levantan los mutes que puso ESTE ipmute (mismo final y motivo): un mute
            // aparte que alguna de esas cuentas tuviera por su cuenta se queda como estaba.
            // La cuenta nombrada tambien: /ipmute la silencio aunque el registro de conexiones
            // no la tuviera (Track_Alts apagado), y antes se quedaba muda tras el /unipmute.
            java.util.LinkedHashSet<UUID> accounts = new java.util.LinkedHashSet<>();
            for (AltTracker.Account account : module.alts().accountsOn(resolved.hash())) {
                accounts.add(account.id());
            }
            if (resolved.account() != null) accounts.add(resolved.account());
            // Ya estamos fuera del hilo del servidor: las escrituras durables se quedan aqui
            // (la voz se despacha sola al hilo global).
            int lifted = 0;
            for (UUID account : accounts) {
                Mute current = mutes.getMute(account);
                if (current == null || !IpMuteRules.belongsTo(current.until, current.reason,
                        removed.until(), removed.reason())) continue;
                if (mutes.unmuteResult(account) == MuteManager.RemovalResult.REMOVED) {
                    lifted++;
                    module.history().record(account, "unmute", actor, "ipmute");
                }
            }
            int count = lifted;
            LookupSupport.reply(module, sender, viewer -> {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.unipmute-done",
                        "player", MM.escape(resolved.shown()),
                        "id", SharedConnections.shortId(resolved.hash()), "accounts", count));
                module.audit(viewer, "unipmute",
                        resolved.shown() + " (" + count + " accounts)");
            });
        });
    }

    // ---- Comun ------------------------------------------------------------------------------

    /** La conexion pedida: su hash, como se enseña, y la cuenta nombrada (null si fue una IP). */
    private record Resolved(String hash, String shown, UUID account) {
    }

    /** Fuera del hilo del servidor. Null si no hay conexion (y ya se le dijo al staff). */
    private Resolved resolveConnection(CommandSender sender, String input) {
        if (rawIp(input)) {
            String hash = AltTracker.hashIp(input);
            if (hash == null) return null;
            return new Resolved(hash, "IP·" + SharedConnections.shortId(hash), null);
        }
        LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
        if (target == null) {
            LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                    "common.unknown-player", "player", MM.escape(input))));
            return null;
        }
        String hash = module.alts().lastHash(target.id());
        if (hash == null) {
            LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                    "staff-lookup.no-connection", "player", MM.escape(target.name()))));
            return null;
        }
        return new Resolved(hash, target.name(), target.id());
    }

    private static boolean rawIp(String input) {
        return RAW_IPV4.matcher(input).matches()
                || (input.contains(":") && RAW_IPV6.matcher(input).matches());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, "chatalyst.command.ipmute")) return List.of();
        if (args.length == 1) return LookupSupport.knownNames(sender, args[0]);
        if (args.length == 2 && command.getName().equalsIgnoreCase("ipmute")) {
            return List.of("10m", "1h", "1d", "7d", "perm");
        }
        return List.of();
    }
}
