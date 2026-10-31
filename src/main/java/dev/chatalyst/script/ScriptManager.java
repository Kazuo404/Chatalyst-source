package dev.chatalyst.script;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motor de scripting para variables con JavaScript (Nashorn). Un valor de variable que empieza
 * por "js:" se evalua como script, con el jugador disponible como {@code player}. Los scripts
 * se compilan una vez y se cachean. Un {@link ClassFilter} bloquea clases peligrosas (Runtime,
 * ProcessBuilder, File, System...) como defensa en profundidad: los scripts los escribe el
 * admin en la config (no jugadores), pero limitamos superficie igualmente — por eso tampoco
 * se expone {@code server} como binding, solo {@code player}/{@code playerName}.
 */
public final class ScriptManager {

    private static final int MAX_SCRIPT_LENGTH = 1024;
    private static final int MAX_COMPILED_SCRIPTS = 512;

    private final JavaPlugin plugin;
    /** Scripts a la vez. Un motor pesa bastante y las condiciones son cortas: no hacen falta mas. */
    private static final int POOL_SIZE =
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final long BORROW_TIMEOUT_MS = 250L;

    private volatile ScriptEnginePool<Worker> pool;
    private ClassLoader loader;
    private volatile boolean available;
    private volatile boolean enabled = true;
    // Scripts que no compilan: se recuerdan para no recompilarlos en cada mensaje.
    private final java.util.Set<String> invalid = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ScriptManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        enabled = plugin.getConfig().getBoolean("Scripting.Enabled", true);
        if (!enabled) {
            available = false;
            plugin.getLogger().info("Variable scripting disabled in config.");
            return;
        }
        // Los motores se crean en hilos de chat: el cargador de clases se fija aqui.
        loader = Thread.currentThread().getContextClassLoader();
        pool = new ScriptEnginePool<>(POOL_SIZE, this::newWorker);
        try {
            long generation = pool.generation();
            Worker probe = pool.borrow(BORROW_TIMEOUT_MS);
            available = probe != null && probe.engine() instanceof Compilable;
            if (probe != null) pool.release(probe, generation);
            if (available) {
                // El banner ya muestra "Scripting on"; este detalle va a debug.
                plugin.getLogger().fine("Variable scripting (Nashorn) available.");
            }
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("Nashorn unavailable; 'js:' variables will be empty: "
                    + t.getMessage());
        }
    }

    /** Un motor y lo compilado con el: un script compilado pertenece a su motor. */
    private record Worker(ScriptEngine engine, Map<String, CompiledScript> compiled) {
    }

    private Worker newWorker() {
        ScriptEngine engine = new NashornScriptEngineFactory()
                .getScriptEngine(new String[0], loader, new SafeFilter());
        return new Worker(engine, new java.util.HashMap<>());
    }

    public boolean isAvailable() {
        return available && enabled;
    }

    /** Evalua un script con el jugador como {@code player}; devuelve "" ante cualquier fallo. */
    public String eval(String script, Player player) {
        if (!canEvaluate(script)) return "";
        try {
            Object result = run(script, player, Map.of());
            return result != null ? result.toString() : "";
        } catch (Throwable t) {
            warnFailed(script, t);
            return "";
        }
    }

    /** Boolean rule condition with bounded, explicit runtime bindings. */
    public boolean evalRuleCondition(String script, Player player, String message,
            dev.chatalyst.rules.RuleContext context) {
        if (!canEvaluate(script)) return false;
        try {
            return truthy(run(script, player, Map.of(
                    "message", message != null ? message : "",
                    "context", context != null ? context.name().toLowerCase(Locale.ROOT) : "chat")));
        } catch (Throwable failure) {
            warnFailed(script, failure);
            return false;
        }
    }

    /** Boolean condition used by sender/receiver-aware format parts. */
    public boolean evalFormatCondition(String script, Player subject, Player sender,
            Map<String, String> context) {
        if (!canEvaluate(script)) return false;
        try {
            Map<String, Object> bindings = new java.util.HashMap<>();
            bindings.put("sender", sender);
            bindings.put("senderName", sender != null ? sender.getName() : "");
            bindings.put("channelName", context != null
                    ? context.getOrDefault("channel_name", context.getOrDefault("channel", "")) : "");
            return truthy(run(script, subject, bindings));
        } catch (Throwable failure) {
            warnFailed(script, failure);
            return false;
        }
    }

    private static boolean truthy(Object result) {
        if (result instanceof Boolean value) return value;
        if (result instanceof Number value) return value.doubleValue() != 0;
        String text = result != null ? result.toString().trim() : "";
        return text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1");
    }

    /** Null si el script no compila; los errores de ejecucion se lanzan. */
    private Object run(String script, Player player, Map<String, ?> extra) throws Exception {
        ScriptEnginePool<Worker> engines = pool;
        long generation = engines.generation();
        Worker worker = engines.borrow(BORROW_TIMEOUT_MS);
        if (worker == null) throw new IllegalStateException("every script engine is busy");
        try {
            CompiledScript compiled = compiled(worker, script);
            if (compiled == null) return null;
            Bindings bindings = worker.engine().createBindings();
            bindings.put("player", player);
            bindings.put("playerName", player != null ? player.getName() : "");
            extra.forEach(bindings::put);
            return compiled.eval(bindings);
        } finally {
            engines.release(worker, generation);
        }
    }

    private CompiledScript compiled(Worker worker, String script) {
        CompiledScript known = worker.compiled().get(script);
        if (known != null) return known;
        if (worker.compiled().size() >= MAX_COMPILED_SCRIPTS) return null;
        try {
            CompiledScript fresh = ((Compilable) worker.engine()).compile(script);
            worker.compiled().put(script, fresh);
            return fresh;
        } catch (Throwable t) {
            // Sin esto, un script invalido se recompilaria y avisaria en CADA mensaje.
            if (invalid.size() < MAX_COMPILED_SCRIPTS && invalid.add(script)) {
                plugin.getLogger().warning("Ignoring invalid script: " + preview(script)
                        + " (" + t.getMessage() + ")");
            }
            return null;
        }
    }

    /** Un script roto se avisa una vez por script, no una vez por mensaje de chat. */
    private void warnFailed(String script, Throwable failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        if (warned.size() < MAX_COMPILED_SCRIPTS && warned.add(script)) {
            plugin.getLogger().warning("Script failed and was skipped: " + preview(script)
                    + " (" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")");
        }
    }

    private static String preview(String script) {
        String line = script.replaceAll("\\s+", " ").trim();
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }

    private boolean canEvaluate(String script) {
        return available && enabled && pool != null && script != null && !script.isBlank()
                && script.length() <= MAX_SCRIPT_LENGTH && !invalid.contains(script);
    }

    public void reset() {
        invalid.clear();
        warned.clear();
        if (pool != null) pool.clear();
        boolean was = enabled;
        enabled = plugin.getConfig().getBoolean("Scripting.Enabled", true);
        // Activarlo en caliente requiere inicializar el motor si nunca arranco.
        if (enabled && !was && pool == null) {
            init();
        }
    }

    // Bloquea el acceso a clases sensibles desde los scripts (defensa en profundidad;
    // los scripts los escribe el admin). Locale.ROOT: toLowerCase() con locale turco
    // cambia las 'I' y podria desalinear la comparacion del blocklist.
    private static final class SafeFilter implements ClassFilter {
        @Override
        public boolean exposeToScripts(String className) {
            String c = className.toLowerCase(Locale.ROOT);
            return !(c.startsWith("java.lang.runtime")
                    || c.startsWith("java.lang.process")
                    || c.startsWith("java.lang.system")
                    || c.startsWith("java.lang.thread")
                    || c.startsWith("java.lang.classloader")
                    || c.startsWith("java.lang.module")
                    || c.startsWith("java.lang.invoke")
                    || c.startsWith("java.io")
                    || c.startsWith("java.nio")
                    || c.startsWith("java.net")
                    || c.startsWith("javax.script")
                    || c.startsWith("jdk.")
                    || c.startsWith("sun.")
                    || c.contains("reflect"));
        }
    }
}
