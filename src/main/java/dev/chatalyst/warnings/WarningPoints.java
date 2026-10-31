package dev.chatalyst.warnings;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puntos de advertencia: cada infraccion suma puntos; al cruzar un umbral se ejecutan
 * comandos de consola. Persisten via {@link Store} (YAML o base de datos) como "points|last"
 * y decaen: si pasa el tiempo configurado sin infracciones, los puntos se reinician solos.
 */
public final class WarningPoints {

    // Separador puntos|timestamp en el valor persistido. OJO: split() recibe regex, por eso
    // el load usa "\\|". (Antes era "" por un caracter invisible perdido: corrompia la carga.)
    private static final String SEP = "|";

    private final JavaPlugin plugin;
    private final Store store;
    private record State(int points, long lastInfraction) {
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Object statesLock = new Object();
    // Mapa inmutable reemplazado entero en load() + escalares volatile: los puntos se anaden
    // desde el chat async (rules/swear) mientras /chatalyst reload recarga los umbrales.
    private volatile Map<Integer, List<String>> thresholds = Map.of();
    private volatile boolean enabled;
    private volatile long decayMillis;

    public WarningPoints(JavaPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("warnings");
        listenForInvalidations();
    }

    /** Constructor del nucleo de expiracion para pruebas deterministas sin servidor Bukkit. */
    WarningPoints(Store store, long decayMillis) {
        this.plugin = null;
        this.store = store;
        this.enabled = true;
        this.decayMillis = decayMillis;
        listenForInvalidations();
        loadPersistedStates();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Warning_Points.Enabled", true);
        decayMillis = TimeUtil.parseDuration(plugin.getConfig().getString("Warning_Points.Decay_After", "1d"));

        // TreeMap para que la iteracion recorra los umbrales en orden ascendente.
        TreeMap<Integer, List<String>> next = new TreeMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("Warning_Points.Thresholds");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (next.size() >= 128) break;
                try {
                    int threshold = Integer.parseInt(key);
                    if (threshold <= 0) continue;
                    List<String> actions = sec.getStringList(key).stream()
                            .filter(java.util.Objects::nonNull)
                            .map(command -> command.length() <= 1_024
                                    ? command : command.substring(0, 1_024))
                            .limit(128)
                            .toList();
                    next.put(threshold, actions);
                } catch (NumberFormatException ignore) {
                    plugin.getLogger().warning("Invalid warning points threshold: " + key);
                }
            }
        }
        thresholds = java.util.Collections.unmodifiableMap(next); // publicacion atomica

        loadPersistedStates();
    }

    private void loadPersistedStates() {
        // El lock cubre snapshot + publicacion. add()/invalidaciones pueden confirmar el
        // Store en paralelo, pero su actualizacion local espera al final y por tanto nunca
        // termina escribiendo en un mapa que acaba de ser reemplazado/limpiado.
        synchronized (statesLock) {
            Map<UUID, State> next = new ConcurrentHashMap<>();
            for (Map.Entry<String, String> e : store.all().entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    State state = decode(e.getValue());
                    if (state != null) {
                        // Normaliza el formato legacy "points" una vez para que futuras
                        // expiraciones puedan comparar el valor exacto sin ambiguedad.
                        if (!e.getValue().contains(SEP)
                                && !store.compareAndPutDurably(e.getKey(), e.getValue(), encode(state))) {
                            state = decode(store.get(e.getKey()));
                        }
                        if (state != null) next.put(id, state);
                    }
                } catch (RuntimeException ignore) {
                    // corrupto
                }
            }
            states.clear();
            states.putAll(next);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // Si paso el tiempo de decaimiento sin infracciones, reinicia los puntos.
    private int applyDecay(UUID id) {
        synchronized (statesLock) {
            State observed = states.get(id);
            if (observed == null) return 0;
            if (decayMillis > 0 && decayMillis != TimeUtil.PERMANENT) {
                if (System.currentTimeMillis() - observed.lastInfraction() >= decayMillis
                        && states.remove(id, observed)) {
                    String key = id.toString();
                    if (!store.compareAndRemoveDurably(key, encode(observed))) {
                        State replacement = decode(store.get(key));
                        if (replacement != null) {
                            states.put(id, replacement);
                            return replacement.points();
                        }
                    }
                    return 0;
                }
            }
            State current = states.get(id);
            return current == null ? 0 : current.points();
        }
    }

    static boolean crossesThreshold(java.util.Collection<Integer> limits, int before, int after) {
        for (int limit : limits) {
            if (before < limit && after >= limit) return true;
        }
        return false;
    }

    /** Suma puntos y dispara las acciones de los umbrales recien cruzados. */
    public void add(Player player, int amount) {
        if (!enabled || amount <= 0) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        // La transicion before->after debe ser atomica tambien ENTRE servidores. El CAS del
        // Store serializa cada suma contra la fila autoritativa y reintenta sobre el valor
        // ganador; un compute() local no evita perder puntos escritos en otro backend.
        int[] transition = new int[2]; // [before, after]
        State[] committed = new State[1];
        // La suma sigue siendo un CAS; lo que no se hace es forzar el guardado en cada infraccion,
        // porque con YAML reescribe el archivo entero en el hilo del chat. Sale en el guardado
        // diferido, salvo que cruce un umbral, que si se confirma antes de actuar.
        boolean updated = StoreCas.update(store, id.toString(), raw -> {
            State current = decode(raw);
            int before = current == null ? 0 : current.points();
            if (decayMillis > 0 && decayMillis != TimeUtil.PERMANENT) {
                if (current != null && now - current.lastInfraction() >= decayMillis) before = 0;
            }
            transition[0] = before;
            int after = saturatedAdd(before, amount);
            transition[1] = after;
            committed[0] = new State(after, now);
            return encode(committed[0]);
        });
        if (!updated) {
            reloadState(id.toString());
            return;
        }
        synchronized (statesLock) {
            states.put(id, committed[0]);
        }

        int before = transition[0];
        int after = transition[1];
        if (crossesThreshold(thresholds.keySet(), before, after)) store.flushNow();
        for (Map.Entry<Integer, List<String>> e : thresholds.entrySet()) {
            int threshold = e.getKey();
            if (before < threshold && after >= threshold) {
                runActions(player, e.getValue(), after);
            }
        }

        // Acumulador del staff suite: cruzar Warn_Points_For_Ban escala a un ban automatico.
        // Lookup perezoso para no acoplar el orden de arranque (y nulo en tests unitarios).
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getStaffModeration() != null
                && chatalyst.getStaffModeration().escalation() != null) {
            chatalyst.getStaffModeration().escalation().onWarnPoints(player, after);
        }
    }

    static int saturatedAdd(int before, int amount) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, (long) before + Math.max(0, amount)));
    }

    public int get(Player player) {
        return applyDecay(player.getUniqueId());
    }

    public int get(UUID id) {
        return applyDecay(id);
    }

    // ---- Consultas para /checkwarn, /warnlist y /unwarn --------------------------------------

    /** Puntos y ultima infraccion de un jugador, tal como estan ahora (con decaimiento). */
    public record Standing(int points, long lastInfraction) {
    }

    /** Tiempo sin infracciones tras el que los puntos vuelven a cero (0 = nunca decaen). */
    public long decayMillis() {
        return decayMillis;
    }

    /** Momento de la ultima infraccion que sumo puntos, o 0 si no tiene puntos. */
    public long lastInfraction(UUID id) {
        if (applyDecay(id) <= 0) return 0L;
        State state = states.get(id);
        return state == null ? 0L : state.lastInfraction();
    }

    /**
     * Quien tiene puntos ahora mismo (lista del staff). Lee el Store autoritativo, asi que
     * incluye los avisos puestos en cualquier servidor de la red. No modifica nada: lo
     * caducado simplemente no sale.
     */
    public Map<UUID, Standing> standings() {
        Map<UUID, Standing> out = new java.util.LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            UUID id;
            try {
                id = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException notAPlayer) {
                continue; // p. ej. las filas "revoked-" de los avisos retirados
            }
            State state = decode(e.getValue());
            if (state == null || state.points() <= 0) continue;
            if (decayMillis > 0 && decayMillis != TimeUtil.PERMANENT
                    && now - state.lastInfraction() >= decayMillis) continue;
            out.put(id, new Standing(state.points(), state.lastInfraction()));
        }
        return out;
    }

    /**
     * Resta puntos (al retirar un aviso con /unwarn). No toca la fecha de la ultima infraccion:
     * quitar un aviso no debe alargar ni acortar el decaimiento del resto.
     *
     * @return los puntos que le quedan, o -1 si el almacenamiento no acepto el cambio.
     */
    public int subtract(UUID id, int amount) {
        if (amount <= 0) return applyDecay(id);
        int[] left = {0};
        boolean updated = StoreCas.updateDurably(store, id.toString(), raw -> {
            State current = decode(raw);
            if (current == null) {
                left[0] = 0;
                return null; // nada que restar
            }
            int after = Math.max(0, current.points() - amount);
            left[0] = after;
            return encode(new State(after, current.lastInfraction()));
        });
        if (!updated) {
            reloadState(id.toString());
            return decode(store.get(id.toString())) == null ? 0 : -1;
        }
        reloadState(id.toString());
        return left[0];
    }

    private static final String REVOKED_PREFIX = "revoked-";

    /** Momentos (epoch ms) de los avisos de este jugador retirados con /unwarn. */
    public java.util.Set<Long> revokedWarns(UUID id) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        String raw = store.get(REVOKED_PREFIX + id);
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // entrada corrupta: se salta
            }
        }
        return out;
    }

    /**
     * Marca como retirado el aviso puesto en {@code warnTime}. El aviso sigue en el historial
     * (que es auditoria y no se reescribe); solo deja de contar como activo.
     */
    public boolean revokeWarn(UUID id, long warnTime) {
        return StoreCas.updateDurably(store, REVOKED_PREFIX + id, raw -> {
            java.util.LinkedHashSet<String> times = new java.util.LinkedHashSet<>();
            if (raw != null && !raw.isBlank()) {
                for (String part : raw.split(",")) if (!part.isBlank()) times.add(part.trim());
            }
            if (!times.add(Long.toString(warnTime))) return null; // ya estaba retirado
            // Tope: el historial guarda 50 filas, no hay mas avisos que recordar.
            while (times.size() > 64) times.remove(times.iterator().next());
            return String.join(",", times);
        });
    }

    public boolean clear(UUID id) {
        String key = id.toString();
        String observed = store.get(key);
        if (observed == null || store.compareAndRemoveDurably(key, observed)) {
            synchronized (statesLock) {
                states.remove(id);
            }
            return true;
        }
        reloadState(key);
        return false;
    }

    private void runActions(Player player, List<String> commands, int total) {
        String playerName = player.getName();
        dev.chatalyst.util.FoliaTasks.global(plugin, () -> {
            for (String raw : commands) {
                String cmd = raw.replace("{player}", playerName)
                        .replace("{points}", String.valueOf(total));
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            }
        });
    }

    private static String encode(State state) {
        return state.points() + SEP + state.lastInfraction();
    }

    private static State decode(String raw) {
        if (raw == null) return null;
        try {
            String[] parts = raw.split("\\|", 2);
            int points = Math.max(0, Integer.parseInt(parts[0]));
            long now = System.currentTimeMillis();
            long timestamp = parts.length > 1 ? Long.parseLong(parts[1]) : now;
            // Un valor corrupto/futuro no puede congelar el decay para siempre.
            if (timestamp <= 0 || timestamp > now + 86_400_000L) timestamp = now;
            return new State(points, timestamp);
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    private void listenForInvalidations() {
        store.addInvalidationListener(ids -> ids.forEach(this::reloadState));
    }

    private void reloadState(String rawId) {
        final UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (RuntimeException invalid) {
            return;
        }
        State replacement = decode(store.get(rawId));
        synchronized (statesLock) {
            if (replacement == null) states.remove(id);
            else states.put(id, replacement);
        }
    }

    public void shutdown() {
        store.flushNow();
    }
}
