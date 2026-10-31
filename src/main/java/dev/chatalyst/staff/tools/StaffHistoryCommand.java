package dev.chatalyst.staff.tools;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /staffhistory &lt;staff&gt; [pagina] — todo lo que ha castigado un miembro del staff en la
 * red, mas reciente primero. Cada linea se pulsa para abrir {@code /check} de ese jugador.
 *
 * <p>Sirve para revisar el trabajo de alguien nuevo o, si una cuenta de staff se ve
 * comprometida, ver de un vistazo que hizo antes de usar {@code /staffrollback}. El staff se
 * busca por el NOMBRE con que firmo cada castigo (tambien "CONSOLE", "IpGuard"...), asi que
 * vale aunque ya no este conectado o no exista en este servidor. Permiso
 * {@value #PERMISSION}.
 */
public final class StaffHistoryCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.staffhistory";
    static final int PAGE_SIZE = 8;

    private final StaffModerationModule module;

    public StaffHistoryCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!ToolsSupport.available(module, sender)
                || !ToolsSupport.require(sender, PERMISSION)) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-tools.staffhistory-usage",
                    "label", label));
            return true;
        }
        String staff = args[0];
        int requested = HistoryQueries.pageArg(args.length > 1 ? args[1] : null);
        String commandBase = "/" + label + " " + staff;
        // Recorre el historial de toda la red: fuera del hilo del servidor.
        ToolsSupport.async(module, () -> {
            HistoryQueries.Page<HistoryQueries.StaffEntry> page = HistoryQueries.page(
                    HistoryQueries.byStaff(module.history().all(), staff), requested,
                    PAGE_SIZE);
            // Solo los nombres de ESTA pagina: un staff veterano tiene miles de filas.
            List<String> names = page.items().stream()
                    .map(e -> ToolsSupport.nameOf(e.target(),
                            e.target().toString().substring(0, 8)))
                    .toList();
            ToolsSupport.reply(module, sender,
                    viewer -> show(viewer, staff, page, names, commandBase));
        });
        return true;
    }

    private void show(CommandSender viewer, String staff,
                      HistoryQueries.Page<HistoryQueries.StaffEntry> page, List<String> names,
                      String commandBase) {
        if (page.total() == 0) {
            viewer.sendMessage(Lang.get(viewer, "staff-tools.staffhistory-empty",
                    "staff", MM.escape(staff)));
            return;
        }
        viewer.sendMessage(Lang.get(viewer, "staff-tools.staffhistory-header",
                "staff", MM.escape(staff), "count", page.total()));
        long now = System.currentTimeMillis();
        for (int i = 0; i < page.items().size(); i++) {
            HistoryQueries.StaffEntry row = page.items().get(i);
            String player = names.get(i);
            Component line = module.formatter().feedback(viewer,
                    "staff-tools.staffhistory-entry", row.entry().detail(),
                    "ago", TimeUtil.formatElapsed(now - row.entry().time()),
                    "id", PunishmentIds.label(row.entry().id()),
                    "type", MM.escape(ToolsSupport.typeLabel(viewer, row.entry().type())),
                    "player", MM.escape(player),
                    "silent", row.entry().silent()
                            ? Lang.raw(viewer, "staff-tools.silent-mark") : "");
            viewer.sendMessage(line
                    .clickEvent(ClickEvent.runCommand("/check " + player))
                    .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                            "staff-tools.staffhistory-hover", "player", MM.escape(player),
                            "date", module.formatter().expiresText(viewer,
                                    row.entry().time())))));
        }
        if (page.pages() > 1) {
            viewer.sendMessage(ToolsSupport.pager(viewer, commandBase, page.page(),
                    page.pages()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, PERMISSION)) return List.of();
        if (args.length == 1) {
            List<String> names = new java.util.ArrayList<>(ToolsSupport.onlineNames(sender));
            names.add("CONSOLE");
            return ToolsSupport.prefix(args[0], names);
        }
        return List.of();
    }
}
