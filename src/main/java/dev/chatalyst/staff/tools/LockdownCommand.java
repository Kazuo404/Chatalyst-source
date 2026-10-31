package dev.chatalyst.staff.tools;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /lockdown &lt;motivo&gt; | /lockdown end — cierra la entrada a la red a todo el que no sea
 * staff ({@value LockdownManager#BYPASS}). Sin argumentos dice si hay uno activo, quien lo
 * puso, desde cuando y por que. El motivo admite colores como el de un ban. Permiso
 * {@value #PERMISSION}.
 */
public final class LockdownCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.lockdown";

    private final StaffModerationModule module;
    private final LockdownManager lockdown;

    public LockdownCommand(StaffModerationModule module, LockdownManager lockdown) {
        this.module = module;
        this.lockdown = lockdown;
    }

    static boolean isEndWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "end", "off", "stop", "fin", "terminar", "quitar", "desactivar" -> true;
            default -> false;
        };
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!ToolsSupport.available(module, sender)
                || !ToolsSupport.require(sender, PERMISSION)) return true;
        if (args.length == 0) {
            ToolsSupport.async(module, () -> {
                LockdownState state = lockdown.current();
                ToolsSupport.reply(module, sender, viewer -> status(viewer, state, label));
            });
            return true;
        }
        if (args.length == 1 && isEndWord(args[0])) {
            ToolsSupport.async(module, () -> {
                boolean active = lockdown.current() != null;
                boolean ended = active && lockdown.end();
                ToolsSupport.reply(module, sender, viewer -> {
                    if (!active) {
                        viewer.sendMessage(Lang.get(viewer, "staff-tools.lockdown-not-active"));
                    } else if (!ended) {
                        viewer.sendMessage(Lang.get(viewer, "plugin.storage-unavailable"));
                    }
                });
                if (ended) {
                    module.audit(sender, "lockdown end", "");
                    notifyStaff("staff-tools.lockdown-stopped", sender.getName(), "");
                }
            });
            return true;
        }
        String reason = module.formatter().reason(sender, ToolsSupport.join(args, 0));
        String staff = sender.getName();
        ToolsSupport.async(module, () -> {
            boolean saved = lockdown.start(reason, staff);
            if (!saved) {
                ToolsSupport.reply(module, sender, viewer ->
                        viewer.sendMessage(Lang.get(viewer, "plugin.storage-unavailable")));
                return;
            }
            module.audit(sender, "lockdown", module.formatter().plainReason(reason));
            notifyStaff("staff-tools.lockdown-started", staff, reason);
        });
        return true;
    }

    private void status(CommandSender viewer, LockdownState state, String label) {
        if (state == null) {
            viewer.sendMessage(Lang.get(viewer, "staff-tools.lockdown-status-off",
                    "label", label));
            return;
        }
        viewer.sendMessage(module.formatter().feedback(viewer, "staff-tools.lockdown-status-on",
                state.reason(), "staff", MM.escape(state.staff()),
                "since", TimeUtil.formatElapsed(System.currentTimeMillis() - state.since()),
                "label", label));
    }

    /** El staff conectado (y la consola) se entera de que la red se cerro o se abrio. */
    private void notifyStaff(String key, String staff, String reason) {
        FoliaTasks.onlinePlayers(module.plugin(),
                viewer -> StaffAccess.has(viewer, PERMISSION)
                        || StaffAccess.has(viewer, LockdownManager.BYPASS),
                viewer -> viewer.sendMessage(module.formatter().feedback(viewer, key, reason,
                        "staff", MM.escape(staff))));
        FoliaTasks.global(module.plugin(), () -> {
            var console = module.plugin().getServer().getConsoleSender();
            console.sendMessage(module.formatter().feedback(console, key, reason,
                    "staff", MM.escape(staff)));
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, PERMISSION) || args.length != 1) return List.of();
        return ToolsSupport.prefix(args[0], List.of("end", "fin"));
    }
}
