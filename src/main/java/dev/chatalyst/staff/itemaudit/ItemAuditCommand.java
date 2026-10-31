package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Vanish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /itemaudit (/staffitems): consultar el registro.
 * <ul>
 *   <li>{@code <staff> [pagina]} — lo que saco, dio y sus cambios de gamemode</li>
 *   <li>{@code player <jugador> [pagina]} — lo que le llego</li>
 *   <li>{@code id <id>} — el recorrido completo de una marca</li>
 *   <li>{@code hand} — la marca del objeto en la mano</li>
 *   <li>{@code recent [pagina]} — lo ultimo de todos</li>
 *   <li>{@code alerts} — silenciar o reactivar los avisos (solo esta sesion)</li>
 * </ul>
 */
final class ItemAuditCommand implements CommandExecutor, TabCompleter {

    /** Un recorrido largo (un stack que paso por veinte manos) se corta aqui. */
    private static final int TRAIL_MAX = 40;
    private static final List<String> SUBCOMMANDS =
            List.of("recent", "player", "id", "hand", "alerts");

    private final ItemAuditManager manager;
    private final ZoneId zone = ZoneId.systemDefault();

    ItemAuditCommand(ItemAuditManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffAccess.has(sender, ItemAuditManager.COMMAND_PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!manager.enabled()) {
            sender.sendMessage(Lang.get(sender, "itemaudit.disabled"));
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "hand", "mano" -> hand(sender);
            case "alerts", "avisos" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return true;
                }
                boolean muted = manager.alerts().toggleMuted(player.getUniqueId());
                sender.sendMessage(Lang.get(sender,
                        muted ? "itemaudit.alerts-off" : "itemaudit.alerts-on"));
            }
            case "recent", "recientes" -> list(sender, label,
                    new AuditQuery(AuditQuery.Kind.RECENT, ""), page(args, 1), "recent");
            case "player", "jugador" -> {
                if (args.length < 2) {
                    usage(sender, label);
                    return true;
                }
                list(sender, label, new AuditQuery(AuditQuery.Kind.PLAYER, args[1]),
                        page(args, 2), "player " + args[1]);
            }
            case "id" -> {
                if (args.length < 2 || !AuditIds.isValid(args[1])) {
                    sender.sendMessage(Lang.get(sender, "itemaudit.bad-id",
                            "id", MM.escape(args.length < 2 ? "" : args[1])));
                    return true;
                }
                trail(sender, AuditIds.normalize(args[1]));
            }
            default -> list(sender, label, new AuditQuery(AuditQuery.Kind.STAFF, args[0]),
                    page(args, 1), args[0]);
        }
        return true;
    }

    private static int page(String[] args, int index) {
        return args.length > index ? AuditFormat.pageArg(args[index]) : 1;
    }

    private static void usage(CommandSender sender, String label) {
        for (String line : Lang.rawList(sender, "itemaudit.usage", "label", label)) {
            sender.sendMessage(MM.full(line));
        }
    }

    // ---- consultas -------------------------------------------------------------------------

    private void list(CommandSender sender, String label, AuditQuery query, int page,
                      String args) {
        sender.sendMessage(Lang.get(sender, "itemaudit.searching"));
        int size = manager.settings().pageSize();
        manager.query(query, AuditFormat.needed(page, size)).whenComplete((rows, failure) ->
                reply(sender, () -> {
                    if (failure != null) {
                        sender.sendMessage(Lang.get(sender, "itemaudit.read-failed"));
                        return;
                    }
                    AuditFormat.Page<AuditEntry> shown = AuditFormat.page(rows, page, size);
                    String header = switch (query.kind()) {
                        case STAFF -> "itemaudit.header-staff";
                        case PLAYER -> "itemaudit.header-player";
                        default -> "itemaudit.header-recent";
                    };
                    sender.sendMessage(Lang.get(sender, header,
                            "name", MM.escape(query.value()), "page", shown.page()));
                    if (shown.rows().isEmpty()) {
                        sender.sendMessage(Lang.get(sender, "itemaudit.empty"));
                        return;
                    }
                    long now = System.currentTimeMillis();
                    for (AuditEntry entry : shown.rows()) sender.sendMessage(line(sender, entry, now));
                    footer(sender, label, args, shown);
                }));
    }

    private void trail(CommandSender sender, String id) {
        sender.sendMessage(Lang.get(sender, "itemaudit.searching"));
        manager.query(new AuditQuery(AuditQuery.Kind.ID, id), TRAIL_MAX + 1)
                .whenComplete((rows, failure) -> reply(sender, () -> {
                    if (failure != null) {
                        sender.sendMessage(Lang.get(sender, "itemaudit.read-failed"));
                        return;
                    }
                    sender.sendMessage(Lang.get(sender, "itemaudit.header-id", "id", id));
                    if (rows.isEmpty()) {
                        sender.sendMessage(Lang.get(sender, "itemaudit.empty"));
                        return;
                    }
                    // El recorrido se lee en orden: de donde salio y luego cada mano.
                    List<AuditEntry> chronological = new ArrayList<>(
                            rows.subList(0, Math.min(TRAIL_MAX, rows.size())));
                    java.util.Collections.reverse(chronological);
                    long now = System.currentTimeMillis();
                    for (AuditEntry entry : chronological) sender.sendMessage(line(sender, entry, now));
                    if (rows.size() > TRAIL_MAX) {
                        sender.sendMessage(Lang.get(sender, "itemaudit.trail-cut", "count", TRAIL_MAX));
                    }
                }));
    }

    /** La respuesta en el hilo de quien pregunto (su region en Folia, o el global). */
    private void reply(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(manager.plugin(), player, ignored -> action.run());
        } else {
            FoliaTasks.global(manager.plugin(), action);
        }
    }

    private Component line(CommandSender viewer, AuditEntry entry, long now) {
        String ago = Lang.raw(viewer, "itemaudit.ago", "time", AuditFormat.ago(now - entry.time()));
        String key = switch (entry.type()) {
            case CREATIVE -> "itemaudit.line-creative";
            case GIVE -> entry.hasTarget() ? "itemaudit.line-give" : "itemaudit.line-give-self";
            case COMMAND -> "itemaudit.line-command";
            case TRANSFER -> "itemaudit.line-transfer";
            case GAMEMODE -> "itemaudit.line-gamemode";
            case GAMEMODE_DENIED -> "itemaudit.line-gamemode-denied";
        };
        String[] modes = AuditText.modes(entry);
        List<String> hover = new ArrayList<>(AuditText.hover(viewer, entry, zone));
        boolean hasTrail = !entry.type().isGamemode();
        if (hasTrail) hover.add(Lang.raw(viewer, "itemaudit.hover-click"));
        Component text = Lang.get(viewer, key,
                "ago", ago, "actor", AuditText.actor(viewer, entry),
                "target", AuditText.target(entry), "amount", entry.amount(),
                "item", AuditText.item(viewer, entry.item()), "command", AuditText.command(entry),
                "via", AuditText.via(viewer, entry.source()),
                "from", AuditText.mode(viewer, modes[0]), "to", AuditText.mode(viewer, modes[1]),
                "cause", AuditText.cause(viewer, entry.source()), "id", entry.id())
                .hoverEvent(HoverEvent.showText(MM.full(String.join("<newline>", hover))));
        return hasTrail ? text.clickEvent(ClickEvent.runCommand("/itemaudit id " + entry.id())) : text;
    }

    private static void footer(CommandSender sender, String label, String args,
                               AuditFormat.Page<AuditEntry> shown) {
        if (shown.page() <= 1 && !shown.hasNext()) return;
        Component footer = Component.empty();
        if (shown.page() > 1) {
            footer = footer.append(Lang.get(sender, "itemaudit.page-prev")
                    .clickEvent(ClickEvent.runCommand("/" + label + " " + args + " "
                            + (shown.page() - 1))));
        }
        footer = footer.append(Lang.get(sender, "itemaudit.page-current", "page", shown.page()));
        if (shown.hasNext()) {
            footer = footer.append(Lang.get(sender, "itemaudit.page-next")
                    .clickEvent(ClickEvent.runCommand("/" + label + " " + args + " "
                            + (shown.page() + 1))));
        }
        sender.sendMessage(footer);
    }

    // ---- la marca en la mano ------------------------------------------------------------------

    private void hand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return;
        }
        // El comando ya corre en la region del jugador: su inventario es legal aqui.
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            sender.sendMessage(Lang.get(sender, "itemaudit.hand-empty"));
            return;
        }
        ItemMarks.Mark mark = manager.marks().read(item);
        if (mark == null) {
            sender.sendMessage(Lang.get(sender, "itemaudit.hand-none",
                    "item", AuditText.item(sender, item.getType().getKey().toString())));
            return;
        }
        long now = System.currentTimeMillis();
        sender.sendMessage(Lang.get(sender, "itemaudit.hand-mark",
                        "id", mark.id(), "staff", MM.escape(mark.staffName()),
                        "item", AuditText.item(sender, item.getType().getKey().toString()),
                        "ago", Lang.raw(sender, "itemaudit.ago",
                                "time", AuditFormat.ago(now - mark.time())),
                        "date", AuditFormat.date(mark.time(), zone))
                .clickEvent(ClickEvent.runCommand("/itemaudit id " + mark.id()))
                .hoverEvent(HoverEvent.showText(Lang.get(sender, "itemaudit.hover-click"))));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!StaffAccess.has(sender, ItemAuditManager.COMMAND_PERMISSION)) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.concat(SUBCOMMANDS.stream(), names(sender))
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("player")
                || args[0].equalsIgnoreCase("jugador"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return names(sender).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private Stream<String> names(CommandSender sender) {
        return manager.plugin().getServer().getOnlinePlayers().stream()
                .filter(p -> !Vanish.isVanished(p) || StaffAccess.canSeeVanished(sender))
                .map(Player::getName);
    }
}
