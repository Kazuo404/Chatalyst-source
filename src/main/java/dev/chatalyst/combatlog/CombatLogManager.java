package dev.chatalyst.combatlog;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registro de PvP: quien golpea a quien, con que, y sobre todo <b>quien dio el primer golpe</b>
 * de cada pelea.
 *
 * <p>Es el gemelo de {@link dev.chatalyst.containerlog.ContainerLogManager} para el combate:
 * mismo buffer en memoria para el comando y misma persistencia como tipo {@code COMBAT} en los
 * archivos diarios del {@link dev.chatalyst.log.LogManager}.
 *
 * <p>Por defecto NO se registra cada golpe. Una pelea normal son decenas de impactos y llenar
 * el log con ellos entierra justo lo que hay que encontrar; con {@code Log_Hits} apagado solo
 * quedan la APERTURA y la MUERTE, que es lo que responde a "quien empezo".
 */
public final class CombatLogManager {

    /** Los que llevan este permiso no dejan rastro al pegar (duelos de staff, pruebas). */
    public static final String EXEMPT_PERMISSION = "chatalyst.combatlog.exempt";
    /** Botones [Ir] y [TP] de las lineas del registro. */
    public static final String TELEPORT_PERMISSION = "chatalyst.combatlog.teleport";

    private static final int RECENT_MAX = 1000;

    private final JavaPlugin plugin;
    private final dev.chatalyst.log.LogManager log;
    private final Deque<CombatRecord> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();
    private final Encounters encounters = new Encounters();
    private final dev.chatalyst.util.RepeatThrottle hitThrottle =
            new dev.chatalyst.util.RepeatThrottle();

    // Volatile: los golpes llegan desde hilos de region (Folia) mientras un reload reescribe.
    private volatile boolean enabled;
    private volatile boolean logHits;
    private volatile long encounterWindowMillis = 30_000L;
    private volatile long repeatWindowMillis = 10_000L;

    public CombatLogManager(JavaPlugin plugin, dev.chatalyst.log.LogManager log) {
        this.plugin = plugin;
        this.log = log;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Spy.Combat.Enabled", true);
        logHits = enabled && plugin.getConfig().getBoolean("Spy.Combat.Log_Hits", false);
        // Silencio tras el cual la siguiente pelea entre esos dos cuenta como NUEVA. Entre
        // 5 s y una hora: por debajo, un respiro para comer partiria la misma pelea en dos y
        // el agresor pasaria a figurar como agredido.
        encounterWindowMillis = clampSeconds("Spy.Combat.Encounter_Seconds", 30, 5, 3600) * 1000L;
        // Agrupacion de golpes repetidos, solo relevante con Log_Hits activado.
        repeatWindowMillis = clampSeconds("Spy.Combat.Repeat_Seconds", 10, 0, 3600) * 1000L;
    }

    private long clampSeconds(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(path, fallback)));
    }

    public boolean enabled() {
        return enabled;
    }

    /** True si tambien se registran los golpes intermedios, no solo apertura y muerte. */
    public boolean logsHits() {
        return logHits;
    }

    public long encounterWindowMillis() {
        return encounterWindowMillis;
    }

    public long repeatWindowMillis() {
        return repeatWindowMillis;
    }

    public Encounters encounters() {
        return encounters;
    }

    /**
     * Decide si una linea HIT se muestra o se agrupa. Devuelve -1 para silenciarla, o cuantas
     * quedaron ocultas desde la ultima mostrada.
     */
    public int throttleHit(String attacker, String victim) {
        if (repeatWindowMillis <= 0) return 0;
        return hitThrottle.register(attacker + ">" + victim, repeatWindowMillis);
    }

    /** Encola el registro en memoria y lo persiste en el archivo diario. */
    public void add(CombatRecord record) {
        synchronized (recentLock) {
            recent.addLast(record);
            while (recent.size() > RECENT_MAX) recent.pollFirst();
        }
        if (log != null && log.logsCombat()) {
            log.log("COMBAT", record.attacker(), record.fileContent());
        }
    }

    /**
     * Ultimas entradas que involucran a alguno de {@code players} como atacante O victima (o
     * todas con null/vacio), cronologicas (mas nuevas al final).
     *
     * <p>Que el filtro mire los dos lados es lo que hace util {@code /combatlog A B}: sale la
     * pelea entera vista desde fuera, no solo lo que hizo uno de los dos.
     */
    public List<CombatRecord> recent(Set<String> players, int limit) {
        List<CombatRecord> out = new ArrayList<>();
        synchronized (recentLock) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                CombatRecord r = it.next();
                if (matches(r.attacker(), players) || matches(r.victim(), players)) out.add(r);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static boolean matches(String who, Set<String> players) {
        if (players == null || players.isEmpty()) return true;
        for (String p : players) {
            if (who.equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    // ---- Presentacion ----------------------------------------------------------------

    /** Verbo de la accion en el idioma del viewer. */
    public static String actionLabel(CommandSender viewer, String action) {
        String key = "combatlog.action-" + action.toLowerCase(Locale.ROOT);
        String label = Lang.raw(viewer, key);
        return key.equals(label) ? action.toLowerCase(Locale.ROOT) : label;
    }

    /** Nombre legible del arma: lang si existe, si no el material en minusculas. */
    public static String weaponLabel(CommandSender viewer, String weapon) {
        String key = "combatlog.weapon." + weapon.toLowerCase(Locale.ROOT);
        String label = Lang.raw(viewer, key);
        return key.equals(label) ? weapon.toLowerCase(Locale.ROOT).replace('_', ' ') : label;
    }

    /**
     * Linea completa y localizada, con los botones <b>[Ir]</b> y <b>[TP]</b> si el viewer tiene
     * {@link #TELEPORT_PERMISSION}.
     *
     * <p>El sufijo {@code started} es la razon de ser del modulo: en cada linea que no sea la
     * apertura se recuerda quien la abrio y hace cuanto, para que el staff no tenga que ir a
     * buscar la linea FIRST varias pantallas mas arriba.
     */
    public static net.kyori.adventure.text.Component render(CommandSender viewer,
                                                            CombatRecord record,
                                                            String entryKey) {
        boolean buttons = viewer instanceof org.bukkit.entity.Player p
                && (p.isOp() || p.hasPermission(TELEPORT_PERMISSION));
        String safeAttacker = dev.chatalyst.util.MM.escape(record.attacker());
        String safeVictim = dev.chatalyst.util.MM.escape(record.victim());
        String safeWorld = dev.chatalyst.util.MM.escape(record.world());
        String go = buttons ? Lang.raw(viewer, "combatlog.go-button",
                "world", safeWorld, "x", record.x(), "y", record.y(), "z", record.z()) : "";
        String tp = buttons ? Lang.raw(viewer, "combatlog.tp-button",
                "player", safeAttacker) : "";
        String repeat = record.repeats() > 0
                ? Lang.raw(viewer, "combatlog.repeats", "count", record.repeats()) : "";
        String started = "";
        if (!"FIRST".equals(record.action()) && record.starter() != null
                && !record.starter().isEmpty()) {
            started = Lang.raw(viewer, "combatlog.started-by",
                    "player", dev.chatalyst.util.MM.escape(record.starter()),
                    "ago", agoLabel(viewer, record.sinceMillis()));
        }
        return Lang.get(viewer, entryKey,
                "time", shortTime(record.time()),
                "attacker", safeAttacker,
                "victim", safeVictim,
                "action", actionLabel(viewer, record.action()),
                "weapon", weaponLabel(viewer, record.weapon()),
                "damage", record.damageLabel(),
                "world", safeWorld,
                "x", record.x(), "y", record.y(), "z", record.z(),
                "started", started, "go", go, "tp", tp, "repeat", repeat);
    }

    /** "hace 12s" / "hace 3m". Sin segundos por debajo del minuto no se entiende una pelea. */
    private static String agoLabel(CommandSender viewer, long millis) {
        long seconds = Math.max(0, millis / 1000L);
        if (seconds < 60) return Lang.raw(viewer, "combatlog.ago-seconds", "count", seconds);
        return Lang.raw(viewer, "combatlog.ago-minutes", "count", seconds / 60);
    }

    /** Hora local del registro con formato HH:mm:ss. */
    public static String shortTime(long epochMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                time.getHour(), time.getMinute(), time.getSecond());
    }
}
