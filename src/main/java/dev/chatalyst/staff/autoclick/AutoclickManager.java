package dev.chatalyst.staff.autoclick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Detector de autoclick: guarda los clics de cada jugador, los evalua una vez por segundo en
 * segundo plano y avisa al staff con permiso cuando salta una senal.
 *
 * <p>Solo AVISA y deja registro (log diario, historial de /check, Discord). El castigo
 * automatico existe pero viene vacio: un falso positivo no le debe costar nada a nadie y el
 * staff decide mirando {@code /autoclick <jugador>} en vivo.
 */
public final class AutoclickManager {

    static final String ALERT_PERMISSION = "chatalyst.autoclick.alerts";
    static final String BYPASS_PERMISSION = "chatalyst.autoclick.bypass";
    /** Tope de sospechosos recordados para /autoclick: la lista es para leerla, no un archivo. */
    private static final int MAX_SUSPECTS = 50;

    private final ChatalystPlugin plugin;
    private final Map<UUID, ClickTracker> trackers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> alertTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Suspect> suspects = new ConcurrentHashMap<>();
    /** Staff mirando a alguien con /autoclick: staff -&gt; vista. */
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    /** Staff que ha silenciado los avisos con /autoclick alerts (solo esta sesion). */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    private volatile AutoclickSettings settings = AutoclickSettings.disabled();
    private volatile boolean packetSource;
    private AutoclickPacketSource packets;
    private ScheduledTask task;

    /** Un aviso ya confirmado: lo que se enseña, se registra y se guarda en la lista. */
    record Alert(UUID id, String name, ClickAnalysis.Verdict verdict, int ping, double tps,
                 long time) {
    }

    /** Sospechoso reciente para /autoclick sin argumentos. */
    record Suspect(UUID id, String name, Alert last, int alerts) {
    }

    /** Vista en vivo: a quien se mira, hasta cuando, y lo peor visto mientras tanto. */
    record Watch(UUID target, String name, long until, int peakCps, double lowestDeviation) {
    }

    public AutoclickManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        settings = AutoclickSettings.load(
                plugin.getConfig().getConfigurationSection(AutoclickSettings.ROOT));
    }

    public synchronized void start() {
        stop();
        AutoclickSettings current = settings;
        if (!current.enabled()) return;
        boolean wantPackets = current.source() != AutoclickSettings.Source.EVENTS;
        if (wantPackets && plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                // Solo aqui, con PacketEvents presente, se carga la clase que lo toca.
                AutoclickPacketSource source = new AutoclickPacketSource(this);
                if (source.start()) packets = source;
            } catch (Throwable t) {
                plugin.getLogger().warning("Autoclick: could not hook into PacketEvents ("
                        + t.getMessage() + "); using server events instead.");
            }
        }
        packetSource = packets != null;
        if (current.source() == AutoclickSettings.Source.PACKETS && !packetSource) {
            plugin.getLogger().warning("Autoclick: Source is 'packets' but PacketEvents is not"
                    + " available; using server events (about 1 tick of resolution).");
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) track(online.getUniqueId());
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                ignored -> tickSafely(), 1, 1, TimeUnit.SECONDS);
        plugin.getLogger().info("Autoclick detector on (clicks from "
                + (packetSource ? "PacketEvents packets" : "server events") + ").");
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (packets != null) {
            packets.stop();
            packets = null;
        }
        packetSource = false;
        trackers.clear();
        watches.clear();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    AutoclickSettings settings() {
        return settings;
    }

    /** Las marcas son de llegada del paquete (~1 ms) y no de un evento del servidor. */
    boolean packetSource() {
        return packetSource;
    }

    // ---- trackers ----------------------------------------------------------------------

    /** Lo usa el hilo de red: nunca crea, solo busca. */
    ClickTracker tracker(UUID id) {
        return trackers.get(id);
    }

    void track(UUID id) {
        AutoclickSettings current = settings;
        if (!current.enabled()) return;
        trackers.computeIfAbsent(id, key -> new ClickTracker(key, ClickTracker.isBedrock(key),
                current.capacity(), current.maxGapMillis() * 1_000_000L));
    }

    void forget(UUID id) {
        trackers.remove(id);
        watches.remove(id);
        muted.remove(id);
    }

    boolean toggleMuted(UUID staff) {
        if (muted.remove(staff)) return false;
        muted.add(staff);
        return true;
    }

    // ---- evaluacion --------------------------------------------------------------------

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            // Un fallo raro no puede matar la tarea periodica: el detector quedaria mudo.
            plugin.getLogger().warning("Autoclick evaluation failed: " + t);
        }
    }

    /** Una vez por segundo, fuera de los hilos de juego y de red. */
    private void tick() {
        AutoclickSettings current = settings;
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        for (ClickTracker tracker : trackers.values()) {
            if (tracker.bedrock() && !current.analyzeBedrock()) continue;
            ClickAnalysis.Verdict verdict = tracker.evaluate(
                    current.thresholdsFor(tracker.bedrock()), now,
                    packetSource && !tracker.bedrock());
            if (verdict == null || coolingDown(tracker.id(), wall, current)) continue;
            Player player = plugin.getServer().getPlayer(tracker.id());
            if (player == null) continue;
            // Permisos, modo de juego y ping se miran en la region del jugador (Folia).
            FoliaTasks.entity(plugin, player, online -> confirm(online, tracker, verdict, current));
        }
        tickWatches(current, now, wall);
        prune(current, wall);
    }

    private boolean coolingDown(UUID id, long wall, AutoclickSettings current) {
        Long previous = lastAlert.get(id);
        return previous != null && wall - previous < current.alertCooldownMillis();
    }

    private void confirm(Player player, ClickTracker tracker, ClickAnalysis.Verdict verdict,
                         AutoclickSettings current) {
        // Un OP no esta exento por serlo (OP = "si" a todo permiso con LuckPerms).
        if (!player.isOp() && player.hasPermission(BYPASS_PERMISSION)) return;
        if (current.ignoreCreative() && (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        // Con lag los paquetes llegan a rafagas o espaciados: lo medido ya no es el dedo.
        int ping = player.getPing();
        if (ping > current.maxPingFor(tracker.bedrock())) return;
        double tps = tps();
        if (!Double.isNaN(tps) && tps < current.minTps()) return;
        long wall = System.currentTimeMillis();
        boolean[] won = {false};
        lastAlert.compute(player.getUniqueId(), (id, previous) -> {
            if (previous != null && wall - previous < current.alertCooldownMillis()) return previous;
            won[0] = true;
            return wall;
        });
        if (!won[0]) return;
        alert(new Alert(player.getUniqueId(), player.getName(), verdict, ping, tps, wall), current);
    }

    /** TPS del ultimo minuto; NaN donde el servidor no lo da (Folia: TPS por region). */
    private double tps() {
        try {
            return Math.min(20.0, plugin.getServer().getTPS()[0]);
        } catch (Throwable unsupported) {
            return Double.NaN;
        }
    }

    // ---- avisos ------------------------------------------------------------------------

    private void alert(Alert alert, AutoclickSettings current) {
        suspects.merge(alert.id(), new Suspect(alert.id(), alert.name(), alert, 1),
                (old, fresh) -> new Suspect(alert.id(), alert.name(), alert, old.alerts() + 1));
        String goCommand = plugin.getStaffModeration() != null
                && plugin.getStaffModeration().enabled()
                ? "/tpto " + alert.name() : "/minecraft:tp @s " + alert.name();
        FoliaTasks.onlinePlayers(plugin,
                viewer -> StaffAccess.has(viewer, ALERT_PERMISSION)
                        && !muted.contains(viewer.getUniqueId())
                        && !viewer.getUniqueId().equals(alert.id()),
                viewer -> viewer.sendMessage(alertLine(viewer, alert)
                        .append(Component.space())
                        .append(Lang.get(viewer, "autoclick.alert-watch")
                                .clickEvent(ClickEvent.runCommand("/autoclick " + alert.name()))
                                .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                                        "autoclick.alert-watch-hover",
                                        "player", MM.escape(alert.name())))))
                        .append(Component.space())
                        .append(Lang.get(viewer, "autoclick.alert-go")
                                .clickEvent(ClickEvent.runCommand(goCommand))
                                .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                                        "autoclick.alert-go-hover"))))));
        var console = plugin.getServer().getConsoleSender();
        FoliaTasks.global(plugin, () -> console.sendMessage(alertLine(console, alert)));
        String detail = detail(alert);
        if (plugin.getLogManager() != null) {
            plugin.getLogManager().log("AUTOCLICK", alert.name(), detail);
        }
        if (current.notifyDiscord() && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Autoclick: " + alert.name() + " - " + detail);
        }
        recordHistory(alert, detail, current);
        punishIfDue(alert, current);
    }

    private Component alertLine(CommandSender viewer, Alert alert) {
        return Lang.get(viewer, "autoclick.alert", vars(viewer, alert.name(), alert.verdict(),
                "ping", alert.ping(), "tps", tpsText(alert.tps())));
    }

    /** Variables comunes de un veredicto, escritas en el idioma de quien lo lee. */
    static Object[] vars(CommandSender viewer, String name, ClickAnalysis.Verdict verdict,
                         Object... extra) {
        Object[] base = {
                "player", MM.escape(name),
                "signal", signalName(viewer, verdict.signal()),
                "cps", oneDecimal(verdict.cps()),
                "deviation", percent(verdict.stats().deviation()),
                "std", oneDecimal(verdict.stats().stdMillis()),
                "duration", TimeUtil.formatElapsed(verdict.streakMillis())};
        Object[] all = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    static String signalName(CommandSender viewer, ClickAnalysis.Signal signal) {
        return Lang.raw(viewer, switch (signal) {
            case REGULARITY -> "autoclick.signal-regularity";
            case SPEED -> "autoclick.signal-speed";
            case LONG_REGULARITY -> "autoclick.signal-long";
        });
    }

    /** Texto plano para el log, el historial y Discord: siempre en ingles, sin colores. */
    static String detail(Alert alert) {
        ClickAnalysis.Verdict v = alert.verdict();
        return v.signal().name().toLowerCase(Locale.ROOT) + ": " + oneDecimal(v.cps()) + " CPS, "
                + "deviation " + percent(v.stats().deviation()) + " (±"
                + oneDecimal(v.stats().stdMillis()) + " ms, " + v.stats().distinct()
                + " distinct), " + TimeUtil.formatElapsed(v.streakMillis()) + ", ping "
                + alert.ping() + " ms, " + tpsText(alert.tps()) + " TPS";
    }

    static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    static String tpsText(double tps) {
        return Double.isNaN(tps) ? "?" : oneDecimal(tps);
    }

    /**
     * Una fila en el historial de /check como mucho cada History_Cooldown_Minutes: el historial
     * tiene tope por jugador y una hora de avisos no puede empujar fuera sus bans de verdad.
     */
    private void recordHistory(Alert alert, String detail, AutoclickSettings current) {
        if (!current.recordHistory()) return;
        var staff = plugin.getStaffModeration();
        if (staff == null || staff.history() == null) return;
        Long previous = lastHistory.get(alert.id());
        if (previous != null && alert.time() - previous < current.historyCooldownMillis()) return;
        lastHistory.put(alert.id(), alert.time());
        // CAS durable contra la base de datos: fuera de los hilos de juego.
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                ignored -> staff.history().record(alert.id(), "autoclick", "Autoclick", detail));
    }

    /** Castigo opcional: Punishment.Commands desde consola al acumular avisos en la ventana. */
    private void punishIfDue(Alert alert, AutoclickSettings current) {
        if (current.punishCommands().isEmpty()) return;
        Deque<Long> times = alertTimes.computeIfAbsent(alert.id(), id -> new ArrayDeque<>());
        boolean due;
        synchronized (times) {
            times.addLast(alert.time());
            while (!times.isEmpty() && alert.time() - times.peekFirst() > current.punishWindowMillis()) {
                times.pollFirst();
            }
            due = times.size() >= current.punishAfter();
            if (due) times.clear(); // una vez por tanda, no en cada aviso siguiente
        }
        if (!due) return;
        FoliaTasks.global(plugin, () -> {
            var console = plugin.getServer().getConsoleSender();
            for (String command : current.punishCommands()) {
                String line = command.replace("{player}", alert.name())
                        .replace("{uuid}", alert.id().toString());
                try {
                    plugin.getServer().dispatchCommand(console, line);
                } catch (RuntimeException failed) {
                    plugin.getLogger().warning("Autoclick punishment command failed: " + line
                            + " (" + failed.getMessage() + ")");
                }
            }
            plugin.getLogger().warning("[Autoclick] " + alert.name() + " reached "
                    + current.punishAfter() + " alerts; ran " + current.punishCommands().size()
                    + " punishment command(s).");
        });
        FoliaTasks.onlinePlayers(plugin,
                viewer -> StaffAccess.has(viewer, ALERT_PERMISSION)
                        && !muted.contains(viewer.getUniqueId()),
                viewer -> viewer.sendMessage(Lang.get(viewer, "autoclick.punished",
                        "player", MM.escape(alert.name()), "alerts", current.punishAfter())));
    }

    private void prune(AutoclickSettings current, long wall) {
        suspects.values().removeIf(s -> wall - s.last().time() > current.recentMillis());
        if (suspects.size() > MAX_SUSPECTS) {
            suspects.values().stream()
                    .sorted(Comparator.comparingLong(s -> s.last().time()))
                    .limit(suspects.size() - MAX_SUSPECTS)
                    .map(Suspect::id).toList().forEach(suspects::remove);
        }
        long keep = Math.max(current.alertCooldownMillis(), current.historyCooldownMillis());
        lastAlert.values().removeIf(time -> wall - time > keep);
        lastHistory.values().removeIf(time -> wall - time > keep);
        alertTimes.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long newest = e.getValue().peekLast();
                return newest == null || wall - newest > current.punishWindowMillis();
            }
        });
    }

    // ---- /autoclick --------------------------------------------------------------------

    /** Sospechosos recientes, el mas nuevo primero. */
    List<Suspect> suspects() {
        List<Suspect> out = new ArrayList<>(suspects.values());
        out.sort(Comparator.comparingLong((Suspect s) -> s.last().time()).reversed());
        return out;
    }

    Suspect suspect(UUID id) {
        return suspects.get(id);
    }

    /** Foto actual de un jugador, o null si no se le sigue. */
    ClickAnalysis.Live live(UUID target) {
        ClickTracker tracker = trackers.get(target);
        if (tracker == null) return null;
        return tracker.live(settings.thresholdsFor(tracker.bedrock()), System.nanoTime());
    }

    void watch(Player staff, Player target) {
        long until = System.currentTimeMillis() + settings.liveSeconds() * 1_000L;
        watches.put(staff.getUniqueId(),
                new Watch(target.getUniqueId(), target.getName(), until, 0, Double.NaN));
    }

    private void tickWatches(AutoclickSettings current, long now, long wall) {
        for (var entry : watches.entrySet()) {
            UUID staffId = entry.getKey();
            Watch watch = entry.getValue();
            Player staff = plugin.getServer().getPlayer(staffId);
            if (staff == null) {
                watches.remove(staffId, watch);
                continue;
            }
            ClickTracker tracker = trackers.get(watch.target());
            if (tracker == null || wall >= watch.until()) {
                watches.remove(staffId, watch);
                boolean gone = tracker == null;
                FoliaTasks.entity(plugin, staff, viewer -> viewer.sendMessage(gone
                        ? Lang.get(viewer, "autoclick.watch-gone", "player", MM.escape(watch.name()))
                        : Lang.get(viewer, "autoclick.watch-end", "player", MM.escape(watch.name()),
                                "peak", watch.peakCps(),
                                "deviation", Double.isNaN(watch.lowestDeviation())
                                        ? "-" : percent(watch.lowestDeviation()))));
                continue;
            }
            ClickAnalysis.Live live = tracker.live(current.thresholdsFor(tracker.bedrock()), now);
            double deviation = live.stats().intervals() >= 10 ? live.stats().deviation() : Double.NaN;
            Watch updated = new Watch(watch.target(), watch.name(), watch.until(),
                    Math.max(watch.peakCps(), live.lastSecond()),
                    Double.isNaN(deviation) ? watch.lowestDeviation()
                            : Double.isNaN(watch.lowestDeviation()) ? deviation
                            : Math.min(watch.lowestDeviation(), deviation));
            watches.replace(staffId, watch, updated);
            FoliaTasks.entity(plugin, staff, viewer ->
                    viewer.sendActionBar(liveLine(viewer, watch.name(), live)));
        }
    }

    static Component liveLine(CommandSender viewer, String name, ClickAnalysis.Live live) {
        boolean measured = live.stats().intervals() >= 2;
        return Lang.get(viewer, "autoclick.watch-bar",
                "player", MM.escape(name),
                "cps", live.lastSecond(),
                "deviation", measured ? percent(live.stats().deviation()) : "-",
                "std", measured ? oneDecimal(live.stats().stdMillis()) : "-",
                "duration", TimeUtil.formatElapsed(live.streakMillis()),
                "clicks", live.streakClicks());
    }
}
