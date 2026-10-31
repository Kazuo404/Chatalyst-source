package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.SoundUtil;
import dev.chatalyst.util.Vanish;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a giveaway from the announcement to the prize landing in someone's inventory.
 *
 * <p>Four phases, and each one only ever leads to the next: the WARM-UP tells the server a draw
 * is coming and counts down without locking anything, START opens the wheel for everyone at the
 * same instant and switches immunity on, the SPIN turns the reel on a decelerating schedule,
 * and the REVEAL names a winner, pays them and lets immunity fade.
 *
 * <p><b>The winner is chosen at the last possible moment</b>, uniformly among the entrants who
 * are still online, and only then is the reel arranged so their head lands in the window.
 * Deciding up front would have been simpler to animate and would have introduced the one bug
 * this cannot have: a wheel that stops on somebody who left ten seconds ago, in front of the
 * whole server. The animation is presentation; the draw is not.
 */
public final class GiveawayManager implements Listener {

    /** Where the draw is right now. Anything player-facing reads this, never a task handle. */
    public enum Phase { IDLE, WARMUP, RUNNING }

    private static final int MAX_WARMUP_SECONDS = 3_600;
    private static final int MAX_SPIN_SECONDS = 60;

    private final ChatalystPlugin plugin;
    private final GiveawayImmunity immunity;
    private final GiveawayDelivery delivery;
    private GiveawayAmountPrompt amountPrompt;
    private final GiveawayPermissionsFile permissionsFile;
    private final GiveawayRanks ranks;

    private volatile boolean enabled = true;
    private volatile int warmupSeconds = 20;
    private volatile int readySeconds = 3;
    private volatile int spinSeconds = 9;
    private volatile int winnerSeconds = 6;
    private volatile boolean reopenIfClosed = true;
    private volatile boolean immunityEnabled = true;
    private volatile int immunityExtraSeconds = 5;
    private volatile String exemptPermission = "chatalyst.giveaway.exempt";
    private volatile boolean excludeStaff = true;
    private volatile List<String> staffPermissions = List.of();
    private volatile Set<String> staffGroups = Set.of();
    private volatile boolean excludeVanished = true;
    private volatile boolean includeAfk;
    private volatile Set<String> excludedWorlds = Set.of();
    private volatile List<Integer> countdownMarks = List.of(30, 20, 10, 5, 4, 3, 2, 1);
    private volatile boolean firework = true;
    /** Giveaway.Participants.Win_Cooldown: quien gano hace menos de esto no entra. 0 = sin espera. */
    private volatile long winCooldownMillis;
    /** Historial de ganadores (Store compartido): "last:<uuid>" y "h:<millis>:<uuid>". */
    private dev.chatalyst.db.Store history;

    // ---- live state ------------------------------------------------------------------
    /** Cuantos ganadores tiene el sorteo en marcha (1 salvo /sorteo start ... ganadores:N). */
    private volatile int winnersCount = 1;
    private volatile Phase phase = Phase.IDLE;
    private volatile GiveawayPrize prize;
    private volatile GiveawayRoulette wheel;
    // One bar PER VIEWER rather than one shared bar: its text names the prize and the seconds
    // left, and a surface the whole server stares at for twenty seconds is the last place to
    // hand half the players someone else's language.
    private final java.util.Map<UUID, BossBar> bars = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<UUID> entrants = List.of();
    private volatile List<UUID> audience = List.of();
    private volatile UUID lastWinner;
    private volatile String lastWinnerPrize;
    private ScheduledTask countdownTask;
    private ScheduledTask readyTask;
    /** Seconds still on the warm-up clock, so a player joining mid-warm-up gets a bar. */
    private volatile int warmupLeft;

    public GiveawayManager(ChatalystPlugin plugin, GiveawayImmunity immunity,
                           GiveawayDelivery delivery, GiveawayRanks ranks) {
        this.plugin = plugin;
        this.immunity = immunity;
        this.delivery = delivery;
        this.permissionsFile = new GiveawayPermissionsFile(plugin);
        this.ranks = ranks;
    }

    /**
     * Everything that can be given away: the prizes written in {@code Giveaway_Prizes}, plus
     * the ranks this server turns out to have. Built fresh each time rather than cached — a
     * rank created in LuckPerms should show up in the menu without a reload, and the menu is
     * opened by hand a few times a day.
     */
    public List<GiveawayPrize> catalogue() {
        return ranks.catalogue();
    }

    /** One prize by id, from the same merged catalogue the menu shows. */
    public java.util.Optional<GiveawayPrize> prize(String id) {
        return ranks.prize(id);
    }

    public GiveawayRanks ranks() {
        return ranks;
    }

    // ---- configuration ---------------------------------------------------------------

    public void load() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("Giveaway.Enabled", true);
        warmupSeconds = clamp(config.getInt("Giveaway.Warmup_Seconds", 20), 0, MAX_WARMUP_SECONDS);
        readySeconds = clamp(config.getInt("Giveaway.Ready_Seconds", 3), 0, 30);
        spinSeconds = clamp(config.getInt("Giveaway.Spin_Seconds", 9), 2, MAX_SPIN_SECONDS);
        winnerSeconds = clamp(config.getInt("Giveaway.Winner_Seconds", 6), 1, 60);
        reopenIfClosed = config.getBoolean("Giveaway.Reopen_If_Closed", true);
        immunityEnabled = config.getBoolean("Giveaway.Immunity.Enabled", true);
        immunityExtraSeconds = clamp(config.getInt("Giveaway.Immunity.Extra_Seconds", 5), 0, 120);
        immunity.cancelVoid(config.getBoolean("Giveaway.Immunity.Cancel_Void", true));
        exemptPermission = config.getString("Giveaway.Participants.Exempt_Permission",
                "chatalyst.giveaway.exempt");
        excludeVanished = config.getBoolean("Giveaway.Participants.Exclude_Vanished", true);
        excludeStaff = config.getBoolean("Giveaway.Participants.Exclude_Staff", true);
        staffPermissions = List.copyOf(
                config.getStringList("Giveaway.Participants.Staff_Permissions"));
        Set<String> groups = new LinkedHashSet<>();
        for (String group : config.getStringList("Giveaway.Participants.Staff_Groups")) {
            if (group != null && !group.isBlank()) groups.add(group.toLowerCase(Locale.ROOT));
        }
        staffGroups = Set.copyOf(groups);
        includeAfk = config.getBoolean("Giveaway.Participants.Include_Afk", false);
        Set<String> worlds = new LinkedHashSet<>();
        for (String world : config.getStringList("Giveaway.Participants.Excluded_Worlds")) {
            if (world != null && !world.isBlank()) worlds.add(world.toLowerCase(Locale.ROOT));
        }
        excludedWorlds = Set.copyOf(worlds);
        List<Integer> marks = new ArrayList<>();
        for (int mark : config.getIntegerList("Giveaway.Countdown_Marks")) {
            if (mark > 0 && mark <= MAX_WARMUP_SECONDS) marks.add(mark);
        }
        marks.sort(Collections.reverseOrder());
        if (!marks.isEmpty()) countdownMarks = List.copyOf(marks);
        firework = config.getBoolean("Giveaway.Firework", true);
        long cooldown = dev.chatalyst.util.TimeUtil.parseDuration(
                config.getString("Giveaway.Participants.Win_Cooldown", "0"));
        winCooldownMillis = Math.max(0, cooldown);
        if (history == null) history = plugin.getStorageManager().store("giveaway_history");
    }

    // ---- historial y espera entre victorias -----------------------------------------------

    /** Una victoria guardada, para /sorteo historial. */
    public record Win(long time, UUID player, String name, String prizeId) {
    }

    private void recordWin(UUID winner, GiveawayPrize won) {
        dev.chatalyst.db.Store store = history;
        if (store == null) return;
        long now = System.currentTimeMillis();
        String name = GiveawayNames.plain(plugin.getServer(), winner);
        long keepLast = Math.max(winCooldownMillis, LAST_WIN_KEEP_MILLIS);
        // La espera entre victorias se lee del cache del Store: se apunta YA, en este hilo
        // (put solo toca memoria), para que el siguiente sorteo la vea aunque empiece antes de
        // que la tarea de abajo llegue a ejecutarse.
        store.put("last:" + winner, String.valueOf(now));
        store.put("h:" + now + ":" + winner, won.id() + "|" + name);
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                for (String old : staleKeys(store.all(), now, keepLast)) store.remove(old);
                store.flushNow();
            });
        } catch (RuntimeException disabling) {
            // Apagando: el Store vuelca lo pendiente al cerrarse. El anuncio y la entrega del
            // premio, que van detras de esta llamada, no pueden depender de esto.
        }
    }

    /** El historial guarda las ultimas HISTORY_KEEP victorias; sin tope crecia para siempre. */
    static final int HISTORY_KEEP = 200;
    /** Un "last:" mas viejo que esto (y que la espera configurada) ya no decide nada. */
    static final long LAST_WIN_KEEP_MILLIS = 90L * 24 * 60 * 60 * 1000;

    /**
     * Lo que sobra del historial: las victorias mas antiguas por encima de HISTORY_KEEP y las
     * marcas {@code last:} caducadas. Estas ultimas no se limpiaban nunca: una por cada jugador
     * que hubiera ganado alguna vez, para siempre, en un Store que se carga entero en memoria.
     */
    static List<String> staleKeys(java.util.Map<String, String> all, long now, long keepLast) {
        List<String> stale = new ArrayList<>();
        List<String> wins = new ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("h:")) {
                wins.add(key);
            } else if (key.startsWith("last:")) {
                try {
                    if (now - Long.parseLong(entry.getValue()) > keepLast) stale.add(key);
                } catch (NumberFormatException broken) {
                    stale.add(key);
                }
            }
        }
        if (wins.size() > HISTORY_KEEP) {
            wins.sort(java.util.Comparator.comparingLong(GiveawayManager::historyTime));
            stale.addAll(wins.subList(0, wins.size() - HISTORY_KEEP));
        }
        return stale;
    }

    private static long historyTime(String key) {
        String[] parts = key.split(":", 3);
        try {
            return parts.length == 3 ? Long.parseLong(parts[1]) : 0L;
        } catch (NumberFormatException broken) {
            return 0L;
        }
    }

    /** Ultimas victorias, de la mas reciente a la mas antigua. */
    public List<Win> recentWins(int limit) {
        dev.chatalyst.db.Store store = history;
        if (store == null) return List.of();
        List<Win> wins = new ArrayList<>();
        for (java.util.Map.Entry<String, String> entry : store.all().entrySet()) {
            String[] key = entry.getKey().split(":", 3);
            if (key.length != 3 || !key[0].equals("h")) continue;
            String[] value = entry.getValue().split("\\|", 2);
            try {
                wins.add(new Win(Long.parseLong(key[1]), UUID.fromString(key[2]),
                        value.length > 1 ? value[1] : key[2], value[0]));
            } catch (IllegalArgumentException broken) {
                // entrada vieja o corrupta: se salta
            }
        }
        wins.sort((a, b) -> Long.compare(b.time(), a.time()));
        return wins.size() > limit ? List.copyOf(wins.subList(0, limit)) : List.copyOf(wins);
    }

    /** True si este jugador gano hace menos de Win_Cooldown. */
    private boolean onCooldown(UUID player) {
        if (winCooldownMillis <= 0 || history == null) return false;
        String last = history.get("last:" + player);
        if (last == null) return false;
        try {
            return System.currentTimeMillis() - Long.parseLong(last) < winCooldownMillis;
        } catch (NumberFormatException broken) {
            return false;
        }
    }

    public long winCooldownMillis() {
        return winCooldownMillis;
    }

    public boolean enabled() {
        return enabled;
    }

    public Phase phase() {
        return phase;
    }

    public int defaultWarmupSeconds() {
        return warmupSeconds;
    }

    public GiveawayPrize runningPrize() {
        return prize;
    }

    public UUID lastWinner() {
        return lastWinner;
    }

    public String lastWinnerPrize() {
        return lastWinnerPrize;
    }

    public GiveawayDelivery delivery() {
        return delivery;
    }

    /**
     * Quien pide por chat la cantidad de un premio de dinero. Lo construye el modulo y no el
     * constructor porque necesita este mismo manager para devolver al jugador a su pantalla.
     */
    void amountPrompt(GiveawayAmountPrompt prompt) {
        this.amountPrompt = prompt;
    }

    public GiveawayAmountPrompt amountPrompt() {
        return amountPrompt;
    }

    /** Quien escribe la lista de permisos de un premio de vuelta a giveaway.yml. */
    public GiveawayPermissionsFile permissionsFile() {
        return permissionsFile;
    }

    // ---- starting --------------------------------------------------------------------

    /**
     * @return a lang key describing why it could not start, or {@code null} when it did
     */
    public synchronized String start(CommandSender by, GiveawayPrize chosen, int seconds) {
        return start(by, chosen, seconds, -1);
    }

    /**
     * @param winners cuantos ganadores; 0 o menos = los del premio personalizado, o 1
     * @return a lang key describing why it could not start, or {@code null} when it did
     */
    public synchronized String start(CommandSender by, GiveawayPrize chosen, int seconds,
                                     int winners) {
        if (chosen != null && phase == Phase.IDLE) {
            int fromPrize = chosen.customData().map(c -> c.winners).orElse(1);
            winnersCount = clamp(winners > 0 ? winners : fromPrize, 1,
                    GiveawayCustomPrizes.MAX_WINNERS);
        }
        if (!enabled) return "giveaway.disabled";
        if (phase != Phase.IDLE) return "giveaway.already-running";
        if (chosen == null) return "giveaway.unknown-prize";
        // Whether anyone can WIN, not merely whether anyone is looking. Checking the
        // audience let a draw with nothing but vanished or exempt staff online run its
        // whole warm-up, announce itself to the server, and only then admit it had
        // nobody to draw from.
        if (eligible().stream().noneMatch(this::canWin)) return "giveaway.nobody";
        // Un premio defectuoso se avisa mas abajo; uno que no entrega NADA se corta aqui.
        // Girar la rueda para no dar nada no se puede deshacer con una advertencia.
        if (delivery.deliversNothing(chosen)) return "giveaway.prize-empty";

        this.prize = chosen;
        this.phase = Phase.WARMUP;
        // Segunda red, por si alguien entro antes de que el modulo estuviera escuchando: el
        // aviso previo dura segundos, tiempo de sobra para que las texturas esten listas.
        for (Player watcher : plugin.getServer().getOnlinePlayers()) {
            dev.chatalyst.gui.PlayerHeads.warm(watcher);
        }
        int warmup = clamp(seconds < 0 ? warmupSeconds : seconds, 0, MAX_WARMUP_SECONDS);

        plugin.getLogger().info("Giveaway started by "
                + (by == null ? "console" : by.getName()) + ": prize '" + chosen.id()
                + "', warm-up " + warmup + "s");
        String problem = delivery.problem(chosen);
        if (problem != null) {
            // Not a refusal: an owner may be mid-setup, and stopping the draw would be a worse
            // surprise than a warning. But the console has to say it BEFORE the reveal.
            plugin.getLogger().warning("Giveaway prize '" + chosen.id()
                    + "' may not be deliverable: " + problem);
        }

        announceWarmup(warmup);
        if (warmup <= 0) {
            openWheel();
        } else {
            startCountdown(warmup);
        }
        return null;
    }

    /** Stops whatever is running without naming a winner. */
    public synchronized void cancel() {
        if (phase == Phase.IDLE) return;
        broadcast(viewer -> Lang.get(viewer, "giveaway.cancelled"));
        finish();
    }

    private void announceWarmup(int seconds) {
        GiveawayPrize running = prize;
        broadcast(viewer -> Lang.get(viewer, "giveaway.warmup",
                "prize", running.displayName(plugin.getConfig(), viewer),
                "seconds", String.valueOf(seconds)));
        Sound start = configuredSound("Start", "BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.0f);
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            viewer.showTitle(Title.title(
                    Lang.get(viewer, "giveaway.warmup-title"),
                    Lang.get(viewer, "giveaway.warmup-subtitle", "seconds",
                            String.valueOf(seconds)),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2),
                            Duration.ofMillis(500))));
            if (start != null) viewer.playSound(start);
        });
        if (seconds > 0) showBar(seconds);
    }

    private void showBar(int seconds) {
        FoliaTasks.onlinePlayers(plugin, viewer -> showBarTo(viewer, seconds));
    }

    /**
     * Gives one player the countdown bar, but only while there is still a countdown.
     *
     * <p>Synchronized, and re-checking the phase INSIDE the lock, because both callers
     * arrive late: the opening broadcast fans out through the schedulers, and a joining
     * player lands two seconds after their login. Either can be overtaken by the wheel
     * opening or by a cancel, and a bar created after {@link #hideBar()} has already run
     * is a bar nothing owns — it would sit frozen on that player's screen until they
     * relogged.
     */
    private synchronized void showBarTo(Player viewer, int seconds) {
        if (phase != Phase.WARMUP) return;
        GiveawayPrize running = prize;
        if (running == null) return;
        BossBar created = BossBar.bossBar(barText(viewer, running, seconds),
                1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        bars.put(viewer.getUniqueId(), created);
        viewer.showBossBar(created);
    }

    private Component barText(Player viewer, GiveawayPrize running, int seconds) {
        return Lang.get(viewer, "giveaway.bar",
                "prize", running.displayName(plugin.getConfig(), viewer),
                "seconds", String.valueOf(Math.max(0, seconds)));
    }

    private void updateBars(int secondsLeft, float progress) {
        GiveawayPrize running = prize;
        if (running == null || bars.isEmpty()) return;
        for (var entry : bars.entrySet()) {
            Player viewer = plugin.getServer().getPlayer(entry.getKey());
            if (viewer == null) continue;
            // Adventure boss bars are safe to mutate from any thread (copy-on-write viewers).
            entry.getValue().progress(Math.max(0f, Math.min(1f, progress)));
            entry.getValue().name(barText(viewer, running, secondsLeft));
        }
    }

    private synchronized void hideBar() {
        if (bars.isEmpty()) return;
        var snapshot = java.util.Map.copyOf(bars);
        bars.clear();
        snapshot.forEach((id, bossBar) -> {
            Player viewer = plugin.getServer().getPlayer(id);
            if (viewer != null) FoliaTasks.entity(plugin, viewer, p -> p.hideBossBar(bossBar));
        });
    }

    /**
     * One tick per second until the wheel opens. Async like the announcer's ticker: everything
     * it touches is either an Adventure boss bar (thread-safe) or dispatched to the player's
     * own scheduler.
     */
    private void startCountdown(int seconds) {
        stopCountdown();
        AtomicInteger remaining = new AtomicInteger(seconds);
        int total = seconds;
        try {
            countdownTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
                if (phase != Phase.WARMUP) {
                    task.cancel();
                    return;
                }
                int left = remaining.decrementAndGet();
                warmupLeft = Math.max(0, left);
                updateBars(left, (float) left / total);
                if (left <= 0) {
                    task.cancel();
                    countdownTask = null;
                    // Opening the wheel reads worlds and builds items; the ticker is
                    // async, so it hands that over to the global thread rather than
                    // touching regions it does not own.
                    FoliaTasks.global(plugin, this::openWheel);
                    return;
                }
                if (countdownMarks.contains(left)) announceMark(left);
            }, 1L, 1L, TimeUnit.SECONDS);
        } catch (RuntimeException rejected) {
            // Plugin shutting down between the command and the scheduler accepting the task.
            finish();
        }
    }

    /** Stops both second-tickers. Either may be null; both are safe to stop twice. */
    private void stopCountdown() {
        ScheduledTask warmup = countdownTask;
        countdownTask = null;
        if (warmup != null) warmup.cancel();
        ScheduledTask ready = readyTask;
        readyTask = null;
        if (ready != null) ready.cancel();
    }

    private void announceMark(int secondsLeft) {
        Sound beep = configuredSound("Countdown", "BLOCK_NOTE_BLOCK_PLING", 1.0f,
                secondsLeft <= 3 ? 1.6f : 1.2f);
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            viewer.showTitle(Title.title(
                    Lang.get(viewer, "giveaway.countdown-title", "seconds",
                            String.valueOf(secondsLeft)),
                    Lang.get(viewer, "giveaway.countdown-subtitle"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900),
                            Duration.ofMillis(200))));
            if (beep != null) viewer.playSound(beep);
        });
    }

    // ---- the wheel -------------------------------------------------------------------

    private synchronized void openWheel() {
        if (phase != Phase.WARMUP) return;
        GiveawayPrize running = prize;
        if (running == null) {
            finish();
            return;
        }
        hideBar();
        List<Player> watching = eligible();
        List<UUID> drawn = new ArrayList<>();
        List<UUID> watchers = new ArrayList<>();
        for (Player player : watching) {
            watchers.add(player.getUniqueId());
            if (canWin(player)) drawn.add(player.getUniqueId());
        }
        if (drawn.isEmpty()) {
            broadcast(viewer -> Lang.get(viewer, "giveaway.nobody"));
            finish();
            return;
        }
        // Shuffled once: an alphabetical reel would let anyone read the order and see who is
        // about to be under the window.
        Collections.shuffle(drawn, ThreadLocalRandom.current());
        entrants = List.copyOf(drawn);
        audience = List.copyOf(watchers);
        phase = Phase.RUNNING;

        if (immunityEnabled) immunity.protect(watchers);

        GiveawayRoulette roulette = new GiveawayRoulette(plugin, running);
        // The reel is nothing but player heads, and this server's backends run in
        // offline mode behind a proxy, where the offline profile carries no textures.
        // Warmed here, during the pause before the wheel moves, so the first frame is
        // already showing real skins instead of nine identical Steves.
        for (Player player : watching) dev.chatalyst.gui.PlayerHeads.warm(player);
        roulette.entrants(drawn.size());
        roulette.reel(window(drawn, 0));
        roulette.countdown(readySeconds);
        wheel = roulette;
        for (Player player : watching) roulette.openFor(player);

        if (readySeconds <= 0) {
            spin();
        } else {
            readyCountdown(roulette);
        }
    }

    /** The pause after the menu opens: long enough to read the prize before the reel moves. */
    private void readyCountdown(GiveawayRoulette roulette) {
        AtomicInteger left = new AtomicInteger(readySeconds);
        Sound beep = configuredSound("Countdown", "BLOCK_NOTE_BLOCK_PLING", 0.7f, 1.4f);
        try {
            readyTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
                if (phase != Phase.RUNNING || wheel != roulette) {
                    task.cancel();
                    return;
                }
                int remaining = left.decrementAndGet();
                roulette.countdown(Math.max(0, remaining));
                // Same reason as above: repainting builds item stacks and reads player
                // profiles, which belong on the global thread. Once a second, so the
                // extra tick of latency is invisible.
                FoliaTasks.global(plugin, roulette::repaintAll);
                if (beep != null) toAudience(beep);
                if (remaining <= 0) {
                    task.cancel();
                    readyTask = null;
                    FoliaTasks.global(plugin, this::spin);
                }
            }, 1L, 1L, TimeUnit.SECONDS);
        } catch (RuntimeException rejected) {
            finish();
        }
    }

    private void spin() {
        GiveawayRoulette roulette = wheel;
        if (roulette == null || phase != Phase.RUNNING) return;
        roulette.phase(GiveawayRoulette.Phase.SPINNING);
        List<GiveawaySpin.Step> steps = GiveawaySpin.schedule(spinSeconds * 20);
        step(roulette, steps, 0, ThreadLocalRandom.current().nextInt(Math.max(1, entrants.size())));
    }

    /**
     * One movement of the reel, then schedules the next. Self-rescheduling rather than a fixed
     * rate because the whole point is that the gap between steps grows; a fixed-rate task can
     * only fake deceleration by skipping frames, which reads as stutter.
     */
    private void step(GiveawayRoulette roulette, List<GiveawaySpin.Step> steps, int index,
                      int offset) {
        if (phase != Phase.RUNNING || wheel != roulette) return;
        GiveawaySpin.Step current = steps.get(index);
        boolean last = index == steps.size() - 1;
        FoliaTasks.globalLater(plugin, () -> {
            if (phase != Phase.RUNNING || wheel != roulette) return;
            if (last) {
                reveal(roulette);
                return;
            }
            roulette.reel(window(entrants, offset));
            roulette.repaintAll();
            playTick(current.progress());
            step(roulette, steps, index + 1, offset + 1);
        }, current.delayTicks());
    }

    private void playTick(double progress) {
        var config = plugin.getConfig();
        if (!config.getBoolean("Giveaway.Sounds.Enabled", true)) return;
        // The last quarter gets its own, more present sound: by then each tick is a whole
        // beat on its own instead of part of a run.
        boolean finalStretch = progress >= 0.75d;
        float pitch = GiveawaySpin.pitch(progress,
                (float) config.getDouble("Giveaway.Sounds.Pitch_From", 0.7d),
                (float) config.getDouble("Giveaway.Sounds.Pitch_To", 2.0d));
        Sound tick = SoundUtil.sound(
                config.getString(finalStretch
                        ? "Giveaway.Sounds.Tick_Final" : "Giveaway.Sounds.Tick"),
                finalStretch ? "BLOCK_NOTE_BLOCK_PLING" : "BLOCK_NOTE_BLOCK_HAT",
                finalStretch ? 1.0f : 0.6f, pitch);
        toAudience(tick);
    }

    // ---- the reveal ------------------------------------------------------------------

    private synchronized void reveal(GiveawayRoulette roulette) {
        if (phase != Phase.RUNNING || wheel != roulette) return;
        List<UUID> present = new ArrayList<>();
        for (UUID id : entrants) {
            if (plugin.getServer().getPlayer(id) != null) present.add(id);
        }
        if (present.isEmpty()) {
            // Every single entrant left during the spin. Picking one of them anyway would
            // announce a winner to an empty room and park their prize in a hold that only
            // survives until the next restart; ending the draw lets staff simply run it
            // again when there is somebody there to win it.
            broadcast(viewer -> Lang.get(viewer, "giveaway.nobody-left"));
            finish();
            return;
        }
        // Varios ganadores: distintos entre si, al azar entre los que siguen conectados. La
        // ruleta se para en el primero; los demas se anuncian justo despues.
        Collections.shuffle(present, ThreadLocalRandom.current());
        List<UUID> winners = List.copyOf(present.subList(0, Math.min(winnersCount, present.size())));
        UUID winner = winners.get(0);
        GiveawayPrize running = roulette.prize();

        roulette.reel(landingOn(entrants, winner));
        roulette.winner(winner);
        roulette.phase(GiveawayRoulette.Phase.WINNER);
        roulette.repaintAll();

        lastWinner = winner;
        lastWinnerPrize = running.id();
        announceWinner(winner, running);
        delivery.deliver(winner, running);
        recordWin(winner, running);
        for (UUID extra : winners.subList(1, winners.size())) {
            String extraName = GiveawayNames.shown(plugin.getServer(), extra);
            broadcast(viewer -> Lang.get(viewer, "giveaway-custom.extra-winner",
                    "player", extraName,
                    "prize", running.displayName(plugin.getConfig(), viewer)));
            delivery.deliver(extra, running);
            recordWin(extra, running);
        }
        if (plugin.getConfig().getBoolean("Giveaway.Notify_Discord", true)
                && plugin.getStaffNotifier() != null) {
            // Todos los ganadores, no solo el primero: con "ganadores:3" el aviso al staff
            // nombraba a uno y los otros dos no constaban en ningun sitio fuera del chat.
            StringBuilder names = new StringBuilder();
            for (UUID each : winners) {
                if (names.length() > 0) names.append(", ");
                names.append(GiveawayNames.plain(plugin.getServer(), each))
                        .append(" (").append(each).append(')');
            }
            plugin.getStaffNotifier().alert("Giveaway: " + running.id() + " won by " + names);
        }

        // The menu stays up so people can see the head that stopped in the window, and
        // immunity outlives it so the close does not drop everyone back into a fight.
        FoliaTasks.globalLater(plugin, () -> closeWheel(roulette), winnerSeconds * 20L);
        FoliaTasks.globalLater(plugin, () -> finishIfStill(roulette),
                (winnerSeconds + immunityExtraSeconds) * 20L);
    }

    /**
     * El cierre aplazado de ESTE sorteo. Si entre tanto se cancelo y se lanzo otro, la rueda
     * ya es otra y no se toca: el cierre viejo cortaba el sorteo nuevo a medio giro.
     */
    private synchronized void finishIfStill(GiveawayRoulette roulette) {
        if (wheel == roulette) finish();
    }

    private void announceWinner(UUID winner, GiveawayPrize running) {
        String name = GiveawayNames.shown(plugin.getServer(), winner);
        broadcast(viewer -> Lang.get(viewer, "giveaway.winner", "player", name,
                "prize", running.displayName(plugin.getConfig(), viewer)));
        Sound fanfare = configuredSound("Winner", "ENTITY_PLAYER_LEVELUP", 1.0f, 1.2f);
        Sound personal = configuredSound("Winner_Personal", "UI_TOAST_CHALLENGE_COMPLETE",
                1.0f, 1.0f);
        // Short lines, no decoration inside the title itself: a title is the one surface that
        // has to land identically on a Bedrock client, where long text is scaled down until it
        // is unreadable and some formatting simply is not there.
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            viewer.showTitle(Title.title(
                    Lang.get(viewer, "giveaway.winner-title"),
                    Lang.get(viewer, "giveaway.winner-subtitle", "player", name),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4),
                            Duration.ofSeconds(1))));
            if (fanfare != null) viewer.playSound(fanfare);
            if (personal != null && viewer.getUniqueId().equals(winner)) {
                viewer.playSound(personal);
            }
        });
        if (firework) launchFirework(winner);
    }

    private void launchFirework(UUID winner) {
        Player player = plugin.getServer().getPlayer(winner);
        if (player == null) return;
        FoliaTasks.entity(plugin, player, target -> {
            try {
                target.getWorld().spawn(target.getLocation(), Firework.class, spawned -> {
                    FireworkMeta meta = spawned.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.STAR)
                            .withColor(Color.fromRGB(0xB5, 0x6C, 0xFF),
                                    Color.fromRGB(0xE4, 0xA8, 0xFF))
                            .withFade(Color.fromRGB(0xFF, 0xD1, 0x66))
                            .trail(true).flicker(true).build());
                    meta.setPower(1);
                    spawned.setFireworkMeta(meta);
                    // No damage: this is a decoration on top of a prize, not a trap.
                    spawned.setShotAtAngle(false);
                });
            } catch (RuntimeException blocked) {
                // A protection plugin refusing the spawn must not take the prize with it.
                plugin.getLogger().fine("Giveaway firework refused: " + blocked.getMessage());
            }
        });
    }

    private void closeWheel(GiveawayRoulette roulette) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            FoliaTasks.entity(plugin, player, viewer -> {
                if (viewer.getOpenInventory().getTopInventory().getHolder()
                        instanceof dev.chatalyst.gui.MenuHolder holder
                        && holder.getMenu() == roulette) {
                    viewer.closeInventory();
                }
            });
        }
    }

    /** Back to idle, whatever happened. Safe to call twice. */
    private synchronized void finish() {
        GiveawayRoulette roulette = wheel;
        stopCountdown();
        hideBar();
        phase = Phase.IDLE;
        wheel = null;
        prize = null;
        entrants = List.of();
        audience = List.of();
        immunity.release();
        if (roulette != null) closeWheel(roulette);
    }

    /** Called from the module's shutdown: never leave a boss bar or immunity behind. */
    public void shutdown() {
        stopCountdown();
        GiveawayRoulette roulette = wheel;
        phase = Phase.IDLE;
        wheel = null;
        immunity.release();
        // Cerrar la rueda aqui es redundante desde que ChatalystPlugin.onDisable cierra
        // TODA GUI abierta (el mismo problema afectaba al mercado y al correo, con items de
        // verdad dentro). Se conserva porque shutdown() tambien se llama en un /cht reload
        // del modulo, donde el plugin no se esta apagando y nadie mas cerraria la rueda.
        if (roulette != null && !FoliaTasks.isFolia()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder()
                        instanceof dev.chatalyst.gui.MenuHolder holder
                        && holder.getMenu() == roulette) {
                    player.closeInventory();
                }
            }
        }
        var snapshot = java.util.Map.copyOf(bars);
        bars.clear();
        // Under Folia the regional ticks stop before onDisable, so touching players across
        // regions here would be illegal; disconnecting drops their viewers anyway.
        if (!FoliaTasks.isFolia()) {
            snapshot.forEach((id, bossBar) -> {
                Player viewer = plugin.getServer().getPlayer(id);
                if (viewer != null) viewer.hideBossBar(bossBar);
            });
        }
    }

    // ---- who takes part --------------------------------------------------------------

    /** Everyone who should SEE the draw: the whole server minus the excluded worlds. */
    public List<Player> eligible() {
        List<Player> out = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (excludedWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(player);
        }
        return out;
    }

    /** Of those, the ones who can actually be drawn. */
    private boolean canWin(Player player) {
        if (!exemptPermission.isBlank() && player.hasPermission(exemptPermission)) return false;
        if (excludeStaff && isStaff(player)) return false;
        if (excludeVanished && Vanish.isVanished(player)) return false;
        if (onCooldown(player.getUniqueId())) return false;
        if (!includeAfk && plugin.getAfkManager() != null
                && plugin.getAfkManager().isAfk(player.getUniqueId())) {
            return false;
        }
        return true;
    }

    /**
     * Staff never win their own giveaways.
     *
     * <p>Three nets, because no single one is reliable on every server. Operator status is the
     * one thing that is always true of an owner testing locally. Permissions are the honest
     * signal and work whatever permission plugin is installed. Group names are the last net,
     * for a server whose moderators hold no Chatalyst permission at all — matched against the
     * primary group only, so the list should name the ranks people are actually SET to.
     *
     * <p>All three are cheap and synchronous: this is asked once per player at the instant the
     * wheel opens, and an async lookup there would have to block it.
     */
    private boolean isStaff(Player player) {
        if (player.isOp()) return true;
        for (String permission : staffPermissions) {
            if (permission != null && !permission.isBlank()
                    && player.hasPermission(permission)) {
                return true;
            }
        }
        if (staffGroups.isEmpty()) return false;
        try {
            String group = dev.chatalyst.hook.LuckPermsHook.group(player.getUniqueId());
            return group != null && staffGroups.contains(group.toLowerCase(Locale.ROOT));
        } catch (RuntimeException unavailable) {
            // No LuckPerms, or it answered badly. The other two nets already ran.
            return false;
        }
    }

    // ---- reel arithmetic -------------------------------------------------------------

    /** The nine heads visible at a given scroll offset, wrapping around a short entrant list. */
    static List<UUID> window(List<UUID> order, int offset) {
        if (order.isEmpty()) return List.of();
        List<UUID> out = new ArrayList<>(GiveawayRoulette.REEL_SIZE);
        for (int i = 0; i < GiveawayRoulette.REEL_SIZE; i++) {
            out.add(order.get(Math.floorMod(offset + i, order.size())));
        }
        return out;
    }

    /**
     * The final frame: the same scrolling window, positioned so the winner sits under the
     * marker. Its neighbours stay the ones that were always next to them in the reel, so the
     * wheel looks like it coasted to a stop rather than teleporting.
     */
    static List<UUID> landingOn(List<UUID> order, UUID winner) {
        if (order.isEmpty()) return List.of();
        int at = order.indexOf(winner);
        if (at < 0) at = 0;
        return window(order, at - GiveawayRoulette.REEL_SIZE / 2);
    }

    // ---- keeping everyone in the room ------------------------------------------------

    /**
     * Puts the wheel back in front of anyone who closed it mid-spin. Delayed by a tick because
     * a client cannot be handed a new inventory from inside the close it is still processing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!reopenIfClosed || phase != Phase.RUNNING) return;
        GiveawayRoulette roulette = wheel;
        if (roulette == null) return;
        if (roulette.phase() == GiveawayRoulette.Phase.WINNER) return;
        if (!(event.getInventory().getHolder() instanceof dev.chatalyst.gui.MenuHolder holder)
                || holder.getMenu() != roulette) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!audience.contains(player.getUniqueId())) return;
        player.getScheduler().runDelayed(plugin, task -> {
            if (phase == Phase.RUNNING && wheel == roulette && player.isOnline()) {
                roulette.openFor(player);
            }
        }, null, 2L);
    }

    /**
     * Somebody arriving in the middle of a draw is shown it too.
     *
     * <p>They are NOT entered — the entrant list was sealed when the wheel opened, and a reel
     * that grew a slot mid-spin would be a wheel whose odds changed while it turned. But they
     * get the boss bar during the warm-up, and during the spin they get the wheel and the
     * immunity that goes with it: the menu takes their controls away exactly as it does
     * everyone else's, and being new is no reason to be the only one who can be killed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // La textura se cachea AL ENTRAR, no al abrir la rueda. Calentarla justo antes no
        // llegaba a tiempo: el EntityScheduler corre al tick siguiente y el primer fotograma
        // se construye en el mismo tick, asi que la rueda abria con nueve Steves. Pedirla al
        // entrar la deja lista minutos antes de que haga falta, y no cuesta ninguna consulta
        // de red: es el perfil VIVO del jugador, que el server ya tiene.
        dev.chatalyst.gui.PlayerHeads.warm(event.getPlayer());
        Phase current = phase;
        if (current == Phase.IDLE) return;
        Player player = event.getPlayer();
        if (excludedWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        GiveawayPrize running = prize;
        if (running == null) return;
        // A few ticks of air: the join messages, the resource pack prompt and other plugins'
        // welcome menus all land first, and whichever of them opens an inventory last wins.
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || phase == Phase.IDLE) return;
            if (phase == Phase.WARMUP) {
                showBarTo(player, warmupLeft);
                return;
            }
            GiveawayRoulette roulette = wheel;
            if (roulette == null) return;
            watch(player);
            roulette.openFor(player);
        }, null, 40L);
    }

    /** Adds a late arrival to the audience — protected and counted, but not entered. */
    private synchronized void watch(Player player) {
        if (phase != Phase.RUNNING) return;
        UUID id = player.getUniqueId();
        if (audience.contains(id)) {
            // Salio y volvio durante el giro: onQuit le quito la inmunidad pero sigue en el
            // publico, asi que hay que volver a protegerle o mira la rueda sin defensa.
            // Solo los que estan conectados: los que salieron no deben volver al conjunto.
            if (immunityEnabled) {
                immunity.protect(audience.stream()
                        .filter(member -> plugin.getServer().getPlayer(member) != null)
                        .toList());
            }
            return;
        }
        List<UUID> extended = new ArrayList<>(audience);
        extended.add(id);
        audience = List.copyOf(extended);
        if (immunityEnabled) immunity.protect(extended);
    }

    /**
     * Puts the wheel back in front of one player who asked for it — what {@code /sorteo} does
     * for somebody who pressed escape and changed their mind.
     *
     * @return false when there is no wheel to show
     */
    public boolean showTo(Player player) {
        GiveawayRoulette roulette = wheel;
        if (roulette == null || phase != Phase.RUNNING) return false;
        if (excludedWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        watch(player);
        roulette.openFor(player);
        return true;
    }

    /**
     * Immunity follows the player out of the door. A UUID left in the protected set would
     * still be there when they log back in tomorrow, quietly making them invulnerable — and
     * the boss bar they were shown has to go with them too.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID gone = event.getPlayer().getUniqueId();
        bars.remove(gone);
        if (!immunityEnabled || phase != Phase.RUNNING) return;
        List<UUID> remaining = new ArrayList<>(audience);
        remaining.remove(gone);
        immunity.protect(remaining);
    }

    // ---- helpers ---------------------------------------------------------------------

    private void toAudience(Sound sound) {
        if (sound == null) return;
        List<UUID> listeners = audience;
        for (UUID id : listeners) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) FoliaTasks.entity(plugin, player, p -> p.playSound(sound));
        }
    }

    private Sound configuredSound(String key, String fallback, float volume, float pitch) {
        if (!plugin.getConfig().getBoolean("Giveaway.Sounds.Enabled", true)) return null;
        return SoundUtil.sound(plugin.getConfig().getString("Giveaway.Sounds." + key),
                fallback, volume, pitch);
    }

    private void broadcast(java.util.function.Function<Player, Component> line) {
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(line.apply(viewer)));
        FoliaTasks.global(plugin, () ->
                plugin.getServer().getConsoleSender().sendMessage(line.apply(null)));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
