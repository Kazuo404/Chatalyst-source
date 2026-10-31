package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import dev.chatalyst.warnings.WarningPoints;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /unwarn <jugador> [id|last]: retira un aviso y los puntos que sumo.
 *
 * <p>El aviso no se borra del historial (es auditoria: queda constancia de que se puso y de
 * que se retiro); se marca como retirado y deja de contar. El numero es el de /checkwarn, y
 * sin numero se retira el mas reciente.
 */
public final class UnwarnCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    public UnwarnCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!LookupSupport.allowed(module, sender, "unwarn")) return true;
        if (args.length < 1) {
            LookupSupport.usage(sender, label, "<jugador> [id|last]");
            return true;
        }
        WarningPoints points = module.plugin().getWarningPoints();
        if (points == null) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        String input = args[0];
        String selector = args.length > 1 ? args[1] : "last";
        String actor = sender.getName();
        int perWarn = Math.max(0, module.plugin().getConfig()
                .getInt("Warning_Points.Manual_Warn_Points", 1));
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
            if (target == null) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "common.unknown-player", "player", MM.escape(input))));
                return;
            }
            String shown = MM.escape(target.name());
            List<WarnLedger.Warn> active = CheckStatusCommand.activeWarns(module, target.id());
            if (active.isEmpty()) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(
                        Lang.get(viewer, "staff-lookup.unwarn-none", "player", shown)));
                return;
            }
            WarnLedger.Warn chosen = WarnLedger.select(active, selector);
            if (chosen == null) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-lookup.unwarn-bad-id", "id", MM.escape(selector),
                        "player", shown)));
                return;
            }
            if (!points.revokeWarn(target.id(), chosen.time())) {
                // Otro /unwarn (de otro staff, o de otro servidor) se adelanto con el mismo
                // aviso: ya esta retirado, no es un fallo de almacenamiento.
                boolean alreadyRevoked = points.revokedWarns(target.id()).contains(chosen.time());
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(alreadyRevoked
                        ? Lang.get(viewer, "staff-lookup.unwarn-bad-id",
                                "id", "#" + chosen.id(), "player", shown)
                        : Lang.get(viewer, "plugin.storage-unavailable")));
                return;
            }
            int left = points.subtract(target.id(), perWarn);
            if (left < 0) {
                // El aviso ya no cuenta, pero los puntos no se pudieron restar: decir "le
                // quedan 0" seria mentir al staff, que ademas no volveria a intentarlo.
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(
                        Lang.get(viewer, "plugin.storage-unavailable")));
            }
            module.history().record(target.id(), "unwarn", actor,
                    "#" + chosen.id() + " " + chosen.reason());
            LookupSupport.reply(module, sender, viewer -> {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.unwarn-done",
                        "id", chosen.id(), "player", shown, "points", perWarn,
                        "left", Math.max(0, left)));
                module.audit(viewer, "unwarn", target.name() + " #" + chosen.id());
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, "chatalyst.command.unwarn")) return List.of();
        if (args.length == 1) return LookupSupport.knownNames(sender, args[0]);
        if (args.length == 2) return List.of("last", "1", "2", "3");
        return List.of();
    }
}
