package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.staff.BanRecord;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import dev.chatalyst.warnings.WarningPoints;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /banlist, /mutelist y /warnlist [pagina]: los castigos ACTIVOS de toda la red, diez por
 * pagina, con clic en cada nombre para abrir su /check y flechas para pasar
 * de pagina. Se lee el Store compartido, asi que salen tambien los puestos en otro servidor.
 */
public final class ActiveListCommand implements CommandExecutor, TabCompleter {

    /** Una linea de la lista ya resuelta (sin Bukkit): quien, por quien, cuanto y por que. */
    private record Line(String name, String staff, long until, String reason, int points,
                        long when) {
    }

    private final StaffModerationModule module;

    public ActiveListCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!LookupSupport.allowed(module, sender, name)) return true;
        int requested = Paging.parsePage(args.length > 0 ? args[0] : null);
        LookupSupport.async(module, () -> {
            List<Line> lines = switch (name) {
                case "banlist" -> bans();
                case "mutelist" -> mutes();
                case "warnlist" -> warns();
                default -> List.of();
            };
            Paging paging = Paging.of(lines.size(), Paging.PER_PAGE, requested);
            List<Line> page = lines.subList(paging.from(), paging.to());
            LookupSupport.reply(module, sender, viewer -> show(viewer, name, lines.size(),
                    paging, page));
        });
        return true;
    }

    private List<Line> bans() {
        List<Line> out = new ArrayList<>();
        for (Map.Entry<UUID, BanRecord> entry : module.bans().snapshot().entrySet()) {
            BanRecord ban = entry.getValue();
            String name = ban.playerName() == null || ban.playerName().isBlank()
                    ? LookupSupport.nameOf(module, entry.getKey()) : ban.playerName();
            out.add(new Line(name, ban.staff(), ban.until(), ban.reason(), 0, 0L));
        }
        out.sort(Comparator.comparing(Line::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private List<Line> mutes() {
        var manager = module.plugin().getMuteManager();
        if (manager == null) return List.of();
        List<Line> out = new ArrayList<>();
        for (Map.Entry<UUID, Mute> entry : manager.snapshot().entrySet()) {
            Mute mute = entry.getValue();
            out.add(new Line(LookupSupport.nameOf(module, entry.getKey()), mute.by, mute.until,
                    mute.reason, 0, 0L));
        }
        out.sort(Comparator.comparing(Line::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private List<Line> warns() {
        WarningPoints points = module.plugin().getWarningPoints();
        if (points == null) return List.of();
        List<Line> out = new ArrayList<>();
        for (Map.Entry<UUID, WarningPoints.Standing> entry : points.standings().entrySet()) {
            WarningPoints.Standing standing = entry.getValue();
            out.add(new Line(LookupSupport.nameOf(module, entry.getKey()), "", 0L, "",
                    standing.points(), standing.lastInfraction()));
        }
        // Los que mas puntos tienen arriba: son los que estan a punto de escalar.
        out.sort(Comparator.comparingInt(Line::points).reversed()
                .thenComparing(Line::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void show(CommandSender viewer, String list, int total, Paging paging,
                      List<Line> page) {
        String prefix = "staff-lookup." + list;
        if (total == 0) {
            viewer.sendMessage(Lang.get(viewer, prefix + "-empty"));
            return;
        }
        viewer.sendMessage(Lang.get(viewer, prefix + "-header",
                "page", paging.page(), "pages", paging.pages(), "count", total));
        for (Line line : page) {
            String name = MM.escape(line.name());
            Component row = list.equals("warnlist")
                    ? Lang.get(viewer, prefix + "-entry", "player", name,
                            "points", line.points(), "ago", LookupSupport.ago(viewer, line.when()))
                    : Lang.get(viewer, prefix + "-entry", "player", name,
                            "staff", MM.escape(line.staff()),
                            "remaining", LookupSupport.remaining(viewer, line.until()),
                            "reason", LookupSupport.reason(module, viewer, line.reason()));
            viewer.sendMessage(row
                    .hoverEvent(HoverEvent.showText(Lang.get(viewer, "staff-lookup.hover-click",
                            "name", name)))
                    .clickEvent(ClickEvent.runCommand("/chatalyst:check " + line.name())));
        }
        if (paging.pages() > 1) viewer.sendMessage(navigation(viewer, list, paging));
    }

    /** "« Anterior · 2/5 · Siguiente »" con las flechas clicables solo si hay a donde ir. */
    private static Component navigation(CommandSender viewer, String list, Paging paging) {
        Component previous = paging.hasPrevious()
                ? Lang.get(viewer, "staff-lookup.page-previous").clickEvent(ClickEvent.runCommand(
                        "/chatalyst:" + list + " " + (paging.page() - 1)))
                : Lang.get(viewer, "staff-lookup.page-previous-off");
        Component next = paging.hasNext()
                ? Lang.get(viewer, "staff-lookup.page-next").clickEvent(ClickEvent.runCommand(
                        "/chatalyst:" + list + " " + (paging.page() + 1)))
                : Lang.get(viewer, "staff-lookup.page-next-off");
        return previous.append(Lang.get(viewer, "staff-lookup.page-middle",
                "page", paging.page(), "pages", paging.pages())).append(next);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? List.of("1", "2", "3") : List.of();
    }
}
