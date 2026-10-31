package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Los avisos en vivo: al staff con {@code chatalyst.itemaudit.alerts} (cada uno en su idioma,
 * con hover y clic al recorrido), a la consola y, si se quiere, al canal de staff de Discord.
 *
 * <p>Lo de objetos pasa por un {@link BurstAggregator}: lo que la misma persona saca o da al
 * mismo destinatario en la ventana sale como UN aviso resumido. Los gamemodes van directos:
 * son pocos y cada uno importa.
 */
final class AuditAlerts {

    /** Como mucho una linea por jugador cada tanto si un plugin insiste en cambiarle el modo. */
    private static final long DENIED_GAP_MILLIS = 10_000L;
    private static final int HOVER_LINES = 8;
    private static final int SUMMARY_ITEMS = 4;

    private record BurstKey(AuditType type, String actor, String target) {
    }

    private final ChatalystPlugin plugin;
    private final ItemAuditManager manager;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastDenied = new ConcurrentHashMap<>();
    private volatile BurstAggregator<BurstKey, AuditEntry> bursts = new BurstAggregator<>(5_000L, 64);
    private volatile long windowMillis = 5_000L;

    AuditAlerts(ChatalystPlugin plugin, ItemAuditManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    boolean toggleMuted(UUID staff) {
        if (muted.remove(staff)) return false;
        muted.add(staff);
        return true;
    }

    void queue(AuditEntry entry) {
        if (entry.type().isGamemode()) {
            gamemode(entry);
            return;
        }
        long window = manager.settings().burstWindowMillis();
        if (window != windowMillis) {
            // Cambio de ventana tras un reload: lo pendiente se manda con la vieja.
            BurstAggregator<BurstKey, AuditEntry> old = bursts;
            bursts = new BurstAggregator<>(window, 64);
            windowMillis = window;
            old.drainAll().forEach(this::send);
        }
        String actor = entry.actorId() == null ? entry.actorName() : entry.actorId().toString();
        bursts.add(new BurstKey(entry.type(), actor, AuditFormat.lower(entry.targetName())), entry,
                System.currentTimeMillis());
    }

    void tick(long now) {
        bursts.drain(now).forEach(this::send);
        lastDenied.values().removeIf(time -> now - time > DENIED_GAP_MILLIS);
    }

    /**
     * Al apagar: lo pendiente se descarta. Las filas ya estan en el registro; mandar avisos
     * con los schedulers y el bot de Discord cerrandose solo produciria errores.
     */
    void discard() {
        bursts.drainAll();
        lastDenied.clear();
    }

    // ---- objetos -----------------------------------------------------------------------------

    private void send(BurstAggregator.Burst<BurstKey, AuditEntry> burst) {
        List<AuditEntry> entries = burst.entries();
        if (entries.isEmpty()) return;
        AuditEntry first = entries.getFirst();
        if (first.type() == AuditType.COMMAND) {
            // Cada comando es una decision distinta: se ensenan uno a uno.
            for (AuditEntry entry : entries) single(entry);
            return;
        }
        boolean oneId = entries.stream().allMatch(e -> e.id().equals(first.id()));
        if (entries.size() == 1) {
            single(first);
            return;
        }
        AuditFormat.Summary summary = AuditFormat.summarize(entries, SUMMARY_ITEMS);
        long last = entries.getLast().time();
        long seconds = Math.max(1L, (last - first.time() + 999L) / 1000L);
        String key = switch (first.type()) {
            case CREATIVE -> "itemaudit.alert-creative-burst";
            case GIVE -> first.hasTarget() ? "itemaudit.alert-give-burst"
                    : "itemaudit.alert-give-self-burst";
            default -> "itemaudit.alert-transfer-burst";
        };
        String click = oneId ? "/itemaudit id " + first.id()
                : first.type() == AuditType.TRANSFER && first.hasTarget()
                        ? "/itemaudit player " + first.targetName()
                        : "/itemaudit " + first.actorName();
        broadcast(key, viewer -> new Object[]{
                        "actor", AuditText.actor(viewer, first), "target", AuditText.target(first),
                        "stacks", summary.stacks() + burst.dropped(), "items", summary.items(),
                        "seconds", seconds, "summary", AuditText.summary(viewer, summary),
                        "id", first.id()},
                viewer -> hover(viewer, entries, burst.dropped()), click);
    }

    private void single(AuditEntry entry) {
        String key = switch (entry.type()) {
            case CREATIVE -> "itemaudit.alert-creative";
            case GIVE -> entry.hasTarget() ? "itemaudit.alert-give" : "itemaudit.alert-give-self";
            case COMMAND -> "itemaudit.alert-command";
            default -> "itemaudit.alert-transfer";
        };
        broadcast(key, viewer -> new Object[]{
                        "actor", AuditText.actor(viewer, entry), "target", AuditText.target(entry),
                        "amount", entry.amount(), "item", AuditText.item(viewer, entry.item()),
                        "command", AuditText.command(entry),
                        "via", AuditText.via(viewer, entry.source()), "id", entry.id()},
                viewer -> hover(viewer, List.of(entry), 0), "/itemaudit id " + entry.id());
    }

    private String hover(CommandSender viewer, List<AuditEntry> entries, int dropped) {
        List<String> lines = new ArrayList<>();
        int shown = Math.min(HOVER_LINES, entries.size());
        if (entries.size() == 1) {
            lines.addAll(AuditText.hover(viewer, entries.getFirst(), zone));
        } else {
            for (AuditEntry entry : entries.subList(0, shown)) {
                lines.add(Lang.raw(viewer, "itemaudit.hover-item", "amount", entry.amount(),
                        "item", AuditText.item(viewer, entry.item()),
                        "key", MM.escape(entry.item())));
                if (!entry.detail().isEmpty()) {
                    lines.add(Lang.raw(viewer, "itemaudit.hover-detail",
                            "detail", MM.escape(entry.detail())));
                }
            }
            int more = entries.size() - shown + dropped;
            if (more > 0) lines.add(Lang.raw(viewer, "itemaudit.hover-more", "count", more));
            AuditEntry last = entries.getLast();
            if (!last.world().isEmpty()) {
                lines.add(Lang.raw(viewer, "itemaudit.hover-where", "world", MM.escape(last.world()),
                        "x", last.x(), "y", last.y(), "z", last.z()));
            }
        }
        lines.add(Lang.raw(viewer, "itemaudit.hover-click"));
        return String.join("<newline>", lines);
    }

    // ---- gamemodes ---------------------------------------------------------------------------

    private void gamemode(AuditEntry entry) {
        String[] modes = AuditText.modes(entry);
        boolean enforced = entry.type() == AuditType.GAMEMODE_DENIED
                && ("JOIN".equals(entry.source()) || "RECHECK".equals(entry.source()));
        if (entry.type() == AuditType.GAMEMODE_DENIED && !enforced && entry.actorId() != null) {
            long now = System.currentTimeMillis();
            Long previous = lastDenied.put(entry.actorId(), now);
            if (previous != null && now - previous < DENIED_GAP_MILLIS) return;
        }
        String key = entry.type() == AuditType.GAMEMODE ? "itemaudit.alert-gamemode"
                : enforced ? "itemaudit.alert-gamemode-enforced" : "itemaudit.alert-gamemode-denied";
        broadcast(key, viewer -> new Object[]{
                        "player", AuditText.actor(viewer, entry),
                        "from", AuditText.mode(viewer, modes[0]),
                        "to", AuditText.mode(viewer, modes[1]),
                        "cause", AuditText.cause(viewer, entry.source()),
                        "world", MM.escape(entry.world()),
                        "x", entry.x(), "y", entry.y(), "z", entry.z()},
                viewer -> String.join("<newline>", AuditText.hover(viewer, entry, zone)),
                "/itemaudit " + entry.actorName());
    }

    // ---- reparto -------------------------------------------------------------------------------

    private void broadcast(String key, Function<CommandSender, Object[]> vars,
                           Function<CommandSender, String> hover, String click) {
        FoliaTasks.onlinePlayers(plugin,
                viewer -> viewer.hasPermission(ItemAuditManager.ALERT_PERMISSION)
                        && !muted.contains(viewer.getUniqueId()),
                viewer -> viewer.sendMessage(Lang.get(viewer, key, vars.apply(viewer))
                        .hoverEvent(HoverEvent.showText(MM.full(hover.apply(viewer))))
                        .clickEvent(ClickEvent.runCommand(click))));
        CommandSender console = plugin.getServer().getConsoleSender();
        Object[] consoleVars = vars.apply(console);
        FoliaTasks.global(plugin, () -> console.sendMessage(Lang.get(console, key, consoleVars)));
        if (manager.settings().alertDiscord() && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Item audit: "
                    + MM.strip(Lang.raw(console, key, consoleVars)));
        }
    }
}
