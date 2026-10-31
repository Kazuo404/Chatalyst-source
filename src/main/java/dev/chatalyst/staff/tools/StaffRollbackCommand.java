package dev.chatalyst.staff.tools;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.BanManager;
import dev.chatalyst.staff.BanRecord;
import dev.chatalyst.staff.HistoryLog;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /staffrollback &lt;staff&gt; &lt;tiempo&gt; [confirmar] — deshace lo que un miembro del staff
 * hizo en ese tiempo, para cuando su cuenta se ve comprometida y alguien empieza a banear a
 * medio servidor.
 *
 * <p>Sin {@code confirmar} solo enseña el resumen (cuantos bans, banips y kicks, a cuantos
 * jugadores) y un boton para confirmar; nada cambia. Con {@code confirmar} — y solo si ese
 * mismo staff acaba de ver ese mismo resumen, ver {@link ConfirmationGate} — se levantan los
 * bans y banips que SIGUEN vigentes y que puso esa persona (un ban que despues sustituyo otro
 * staff no se toca), y esas filas salen del historial de cada jugador. En
 * cada historial queda una fila "rollback" que dice quien lo deshizo y cuanto, y la accion
 * entera va a la auditoria. Permiso {@value #PERMISSION}.
 */
public final class StaffRollbackCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.staffrollback";

    private final StaffModerationModule module;
    private final ConfirmationGate gate = new ConfirmationGate();

    public StaffRollbackCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!ToolsSupport.available(module, sender)
                || !ToolsSupport.require(sender, PERMISSION)) return true;
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "staff-tools.rollback-usage", "label", label));
            return true;
        }
        String staff = args[0];
        long window = window(args[1]);
        if (window == 0L) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.invalid-duration"));
            return true;
        }
        boolean confirm = args.length > 2 && ToolsSupport.isConfirm(args[2]);
        String action = "rollback:" + staff.toLowerCase(Locale.ROOT) + ":" + window;
        String confirmCommand = "/" + label + " " + staff + " " + args[1] + " confirmar";
        long since = since(System.currentTimeMillis(), window);
        ToolsSupport.async(module, () -> {
            List<HistoryQueries.StaffEntry> plan =
                    HistoryQueries.rollbackPlan(module.history().all(), staff, since);
            if (!confirm) {
                preview(sender, staff, args[1], plan, action, confirmCommand);
                return;
            }
            if (!gate.confirm(sender.getName(), action, System.currentTimeMillis())) {
                ToolsSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-tools.confirm-expired", "command",
                        MM.escape(confirmCommand.replace(" confirmar", "")))));
                return;
            }
            // Se queda en este hilo async: un rollback de una cuenta comprometida son cientos
            // de escrituras durables (historial, banips, numeros), y en el hilo global eran
            // segundos de servidor congelado. Solo el desbaneo salta al global (liftIfTheirs).
            execute(sender, staff, args[1], plan);
        });
        return true;
    }

    /** "7d" -> milisegundos; "todo"/"all" -> sin limite; 0 si no se entiende. */
    static long window(String raw) {
        if (raw == null) return 0L;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("all") || lower.equals("todo") || lower.equals("siempre")) {
            return Long.MAX_VALUE;
        }
        long parsed = TimeUtil.parseDuration(raw);
        return parsed == TimeUtil.PERMANENT ? Long.MAX_VALUE : Math.max(0L, parsed);
    }

    /** Desde cuando cuenta la ventana; sin desbordar con "todo". */
    static long since(long now, long window) {
        return window == Long.MAX_VALUE || window >= now ? 0L : now - window;
    }

    private void preview(CommandSender sender, String staff, String window,
                         List<HistoryQueries.StaffEntry> plan, String action,
                         String confirmCommand) {
        int bans = 0;
        int kicks = 0;
        java.util.Set<UUID> players = new java.util.HashSet<>();
        for (HistoryQueries.StaffEntry row : plan) {
            players.add(row.target());
            if (row.entry().type().equals("kick")) kicks++;
            else bans++;
        }
        int bansCount = bans;
        int kicksCount = kicks;
        int playerCount = players.size();
        if (!plan.isEmpty()) gate.request(sender.getName(), action, System.currentTimeMillis());
        ToolsSupport.reply(module, sender, viewer -> {
            if (plan.isEmpty()) {
                viewer.sendMessage(Lang.get(viewer, "staff-tools.rollback-nothing",
                        "staff", MM.escape(staff), "time", MM.escape(window)));
                return;
            }
            viewer.sendMessage(Lang.get(viewer, "staff-tools.rollback-preview",
                    "staff", MM.escape(staff), "time", MM.escape(window),
                    "bans", bansCount, "kicks", kicksCount, "players", playerCount));
            Component button = Lang.get(viewer, "staff-tools.rollback-confirm-button")
                    .clickEvent(ClickEvent.runCommand(confirmCommand))
                    .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                            "staff-tools.confirm-hover")));
            viewer.sendMessage(button);
        });
    }

    private void execute(CommandSender sender, String staff, String window,
                         List<HistoryQueries.StaffEntry> plan) {
        Map<UUID, List<HistoryLog.Entry>> byTarget = new LinkedHashMap<>();
        for (HistoryQueries.StaffEntry row : plan) {
            byTarget.computeIfAbsent(row.target(), ignored -> new ArrayList<>()).add(row.entry());
        }
        int removed = 0;
        int lifted = 0;
        int failures = 0;
        String actor = sender.getName();
        for (Map.Entry<UUID, List<HistoryLog.Entry>> target : byTarget.entrySet()) {
            UUID id = target.getKey();
            boolean hadBan = target.getValue().stream()
                    .anyMatch(entry -> !entry.type().equals("kick"));
            if (hadBan && liftIfTheirs(id, staff)) lifted++;
            int gone = module.history().removeIf(id, HistoryQueries.sameAs(target.getValue()));
            if (gone < 0) {
                failures++;
                continue;
            }
            removed += gone;
            module.history().record(id, "rollback", actor,
                    staff + " (" + gone + ")", PunishmentIds.NONE, true);
        }
        int removedCount = removed;
        int liftedCount = lifted;
        int failureCount = failures;
        module.audit(sender, "staffrollback", staff + " " + window + ": " + removedCount
                + " entries, " + liftedCount + " bans lifted"
                + (failureCount > 0 ? ", " + failureCount + " storage failures" : ""));
        ToolsSupport.reply(module, sender, viewer -> {
            viewer.sendMessage(Lang.get(viewer, "staff-tools.rollback-done",
                    "staff", MM.escape(staff), "amount", removedCount,
                    "unbanned", liftedCount, "players", byTarget.size()));
            if (failureCount > 0) {
                viewer.sendMessage(Lang.get(viewer, "plugin.storage-unavailable"));
            }
        });
    }

    /**
     * Levanta el ban vigente de {@code id} solo si lo puso {@code staff}: si despues otro staff
     * lo baneo por su cuenta, ese ban es legitimo y el rollback no lo toca. Tambien su ban de
     * conexion, como hace /unban.
     */
    private boolean liftIfTheirs(UUID id, String staff) {
        BanRecord ban = module.bans().get(id);
        if (ban == null || !staff.equalsIgnoreCase(ban.staff())) return false;
        // El ban de cuenta toca la lista nativa de Paper: en el hilo global, como /unban, y
        // este hilo async espera a que termine (con tope, por si el plugin se apaga).
        java.util.concurrent.CompletableFuture<BanManager.RemovalResult> result =
                new java.util.concurrent.CompletableFuture<>();
        FoliaTasks.global(module.plugin(), () -> {
            try {
                result.complete(module.bans().unbanResult(Bukkit.getOfflinePlayer(id)));
            } catch (RuntimeException failure) {
                result.complete(BanManager.RemovalResult.STORAGE_FAILED);
            }
        });
        BanManager.RemovalResult removed;
        try {
            removed = result.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException failure) {
            return false;
        }
        if (module.ipBans() != null) module.ipBans().unbanAccount(id);
        return removed == BanManager.RemovalResult.REMOVED;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, PERMISSION)) return List.of();
        if (args.length == 1) return ToolsSupport.prefix(args[0], ToolsSupport.onlineNames(sender));
        if (args.length == 2) {
            return ToolsSupport.prefix(args[1], List.of("1h", "6h", "1d", "7d", "todo"));
        }
        if (args.length == 3) return ToolsSupport.prefix(args[2], List.of("confirmar"));
        return List.of();
    }
}
