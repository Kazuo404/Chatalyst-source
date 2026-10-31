package dev.chatalyst.log;

import dev.chatalyst.util.CommandNames;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Registro de chat, comandos y mensajes privados a archivos diarios (logs/chat-YYYY-MM-DD.log)
 * para auditoria. Las lineas se acumulan en memoria y se vuelcan al disco en bloque de forma
 * asincrona, para no tocar el disco en el hilo principal en cada mensaje. Ademas mantiene un
 * buffer en memoria de las lineas recientes para consultarlas dentro del juego (/chatlog).
 */
public final class LogManager {

    private static final List<String> REQUIRED_SENSITIVE_COMMANDS = List.of(
            "/login", "/register", "/l", "/reg", "/log",
            "/changepassword", "/cp", "/2fa", "/authme", "/premium",
            "/password", "/passwd", "/setpassword", "/resetpassword",
            "/recover", "/login2fa", "/totp", "/otp",
            // El contenido de PM se rige por Log.Private_Messages; no debe filtrarse por
            // Log.Commands cuando el administrador desactivo expresamente ese registro.
            "/msg", "/tell", "/w", "/whisper", "/r", "/reply"
    );

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final java.util.regex.Pattern UNSAFE_LOG_CHARACTERS =
            java.util.regex.Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    /** Una linea del historial reciente (para el comando in-game). */
    public record Entry(String time, String type, String who, String content) {
    }

    private final JavaPlugin plugin;
    private final ConcurrentLinkedDeque<String> buffer = new ConcurrentLinkedDeque<>();
    private final Object fileLock = new Object();

    // Tope del buffer pendiente de volcar: si el disco falla durante horas, se sacrifica lo
    // MAS VIEJO en vez de crecer sin limite y tumbar el server por memoria. El contador va
    // aparte porque ConcurrentLinkedDeque.size() es O(n).
    private static final int MAX_BUFFER = 10_000;
    private final java.util.concurrent.atomic.AtomicInteger bufferSize =
            new java.util.concurrent.atomic.AtomicInteger();

    // Historial reciente en memoria (para /chatlog). Acotado; el mas viejo se descarta.
    private static final int RECENT_MAX = 500;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    // Volatile: el log recibe eventos del chat async (y comandos en hilos de region en
    // Folia) mientras /chatalyst reload reescribe esta configuracion.
    private volatile boolean enabled;
    private volatile boolean logChat;
    private volatile boolean logCommands;
    private volatile boolean logPrivate;
    private volatile boolean logMail;
    private volatile boolean logSigns;
    private volatile boolean logBooks;
    private volatile boolean logAnvil;
    private volatile boolean logContainers;
    private volatile boolean logCombat;
    private volatile boolean logAnonymous;
    private volatile boolean logSwear;
    private volatile boolean logDiscord;
    private volatile int keepDays;
    private volatile List<String> sensitiveCommands = List.of();
    private ScheduledTask task;
    private ScheduledTask cleanupTask;

    public LogManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Log.Enabled", true);
        logChat = plugin.getConfig().getBoolean("Log.Chat", true);
        logCommands = plugin.getConfig().getBoolean("Log.Commands", true);
        logPrivate = plugin.getConfig().getBoolean("Log.Private_Messages", true);
        logMail = plugin.getConfig().getBoolean("Log.Mail", true);
        logSigns = plugin.getConfig().getBoolean("Log.Signs", true);
        logBooks = plugin.getConfig().getBoolean("Log.Books", true);
        logAnvil = plugin.getConfig().getBoolean("Log.Anvil", true);
        logContainers = plugin.getConfig().getBoolean("Log.Containers", true);
        logCombat = plugin.getConfig().getBoolean("Log.Combat", true);
        logAnonymous = plugin.getConfig().getBoolean("Log.Anonymous", true);
        logSwear = plugin.getConfig().getBoolean("Log.Swear", true);
        logDiscord = plugin.getConfig().getBoolean("Log.Discord", true);
        keepDays = normalizeKeepDays(plugin.getConfig().getInt("Log.Keep_Days", 30));
        // Comandos que NUNCA se registran (contienen contrasenas u otros datos sensibles).
        sensitiveCommands = plugin.getConfig().getStringList("Log.Ignore_Commands").stream()
                .map(CommandNames::label)
                .filter(c -> !c.isEmpty())
                .toList();
    }

    public void start() {
        stop();
        if (!enabled) return;
        cleanOldLogs();
        // Volcado cada 5 segundos.
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                t -> flush(), 5, 5, TimeUnit.SECONDS);
        // La limpieza de archivos viejos tambien corre periodicamente: un server que pasa
        // semanas sin reiniciar no debe acumular logs mas alla de Keep_Days.
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                t -> cleanOldLogs(), 12, 12, TimeUnit.HOURS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        flush();
    }

    public boolean logsChat() {
        return enabled && logChat;
    }

    public boolean logsCommands() {
        return enabled && logCommands;
    }

    /** True si este comando debe registrarse (no es uno sensible como /login). */
    public boolean shouldLogCommand(String message) {
        if (!logsCommands()) return false;
        return shouldLogCommand(message, sensitiveCommands);
    }

    /** La configuracion puede agregar exclusiones, pero nunca quitar las de credenciales. */
    static boolean shouldLogCommand(String message, List<String> configuredSensitive) {
        return !CommandNames.matches(message, REQUIRED_SENSITIVE_COMMANDS)
                && !CommandNames.matches(message,
                configuredSensitive != null ? configuredSensitive : List.of());
    }

    /**
     * True para el conjunto que el plugin protege siempre, aunque el administrador sustituya
     * sus listas configurables. Compartir esta deteccion evita que el log y el command-spy se
     * desincronicen al incorporar un comando de autenticacion nuevo.
     */
    public static boolean isAlwaysSensitiveCommand(String message) {
        return CommandNames.matches(message, REQUIRED_SENSITIVE_COMMANDS);
    }

    public boolean logsPrivate() {
        return enabled && logPrivate;
    }

    public boolean logsMail() {
        return enabled && logMail;
    }

    public boolean logsSigns() {
        return enabled && logSigns;
    }

    public boolean logsBooks() {
        return enabled && logBooks;
    }

    public boolean logsAnvil() {
        return enabled && logAnvil;
    }

    /** Registro de contenedores y mecanismos (cofres, shulkers, palancas...) con coordenadas. */
    public boolean logsContainers() {
        return enabled && logContainers;
    }

    /**
     * Registro de PvP: quien abre cada pelea, quien mata a quien y con que. Es lo que permite
     * arbitrar la regla del primer golpe; ver {@link dev.chatalyst.combatlog.CombatLogManager}.
     */
    public boolean logsCombat() {
        return enabled && logCombat;
    }

    /**
     * Correspondencia nombre real -> alias al entrar en modo anonimo. Es el UNICO sitio donde
     * queda constancia de quien esta detras del disfraz (la consola en vivo y el chat solo ven
     * el alias), para poder investigar despues sin delatarlo en el momento.
     */
    public boolean logsAnonymous() {
        return enabled && logAnonymous;
    }

    /** Registro de lo que el filtro de groserias censuro o bloqueo (con el texto ORIGINAL). */
    public boolean logsSwear() {
        return enabled && logSwear;
    }

    /** Registro de los mensajes reenviados desde Discord al chat del juego. */
    public boolean logsDiscord() {
        return enabled && logDiscord;
    }

    /** True si el comando contiene credenciales u otros datos que NUNCA deben registrarse. */
    public boolean isSensitiveCommand(String message) {
        return !shouldLogCommand(message, sensitiveCommands);
    }

    /**
     * Redacta todos los argumentos de un comando protegido y conserva solo su etiqueta.
     * Se obtiene la etiqueta con {@link CommandNames#label(String)} en vez de buscar un espacio
     * literal: un cliente manipulado puede separar el secreto con tabuladores u otro whitespace.
     */
    public String redactSensitiveCommand(String message) {
        return redactSensitiveCommand(message, sensitiveCommands);
    }

    /** Fallback seguro para consumidores que arrancan antes de que exista el LogManager. */
    public static String redactAlwaysSensitiveCommand(String message) {
        return redactSensitiveCommand(message, List.of());
    }

    static String redactSensitiveCommand(String message, List<String> configuredSensitive) {
        if (shouldLogCommand(message, configuredSensitive)) return message;
        String label = CommandNames.label(message);
        return (label.isEmpty() ? "<command>" : label) + " <redacted>";
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Encola una linea de log. type ej. "CHAT", "CMD", "PM". */
    public void log(String type, String who, String content) {
        if (!enabled) return;
        String time = LocalDateTime.now().format(TS);
        String safeType = safeField(type, 16);
        String safeWho = safeField(who, 160);
        String safeContent = safeField(content, 2048);
        buffer.add("[" + time + "] [" + safeType + "] " + safeWho + ": " + safeContent);
        if (bufferSize.incrementAndGet() > MAX_BUFFER && buffer.pollFirst() != null) {
            bufferSize.decrementAndGet(); // disco caido: cae lo mas viejo, no el server
        }
        synchronized (recentLock) {
            recent.addLast(new Entry(time, safeType, safeWho, safeContent));
            while (recent.size() > RECENT_MAX) recent.pollFirst();
        }
    }

    static String safeField(String value, int maxLength) {
        if (value == null) return "";
        String cleaned = UNSAFE_LOG_CHARACTERS.matcher(value).replaceAll(" ")
                .replace('\t', ' ').trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    /**
     * Ultimas lineas recientes que involucran a ALGUNO de {@code targets} (o todas si es
     * null/vacio), mas nuevas al final. Con varios nombres reconstruye una conversacion:
     * solo aparecen las lineas de esos jugadores, el resto se omite. Buffer en memoria.
     */
    public List<Entry> recent(java.util.Set<String> targets, int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (recentLock) {
            var it = recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                Entry e = it.next();
                if (involves(e.who(), targets)) out.add(e);
            }
        }
        java.util.Collections.reverse(out); // cronologico
        return out;
    }

    private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile(
            "^\\[(.*?)\\] \\[(.*?)\\] (.*?): (.*)$");

    /**
     * Lee el archivo de un dia concreto y devuelve las ultimas {@code limit} lineas que
     * involucran a {@code who} (o todas si es null). Para investigar un reporte de dias atras.
     */
    public List<Entry> readDay(LocalDate date, java.util.Set<String> targets, int limit) {
        return readDay(date, targets, null, limit);
    }

    /**
     * Como {@link #readDay(LocalDate, java.util.Set, int)} pero filtrando TAMBIEN por tipo
     * ({@code CHAT}, {@code CONTAINER}...) DURANTE el recorrido. Filtrar despues era un
     * fallo silencioso: el limite se gastaba con las lineas de otros tipos (el chat domina
     * el archivo) y una consulta de un tipo minoritario devolvia vacia aunque hubiera datos.
     * {@code types} null o vacio = todos los tipos.
     */
    public List<Entry> readDay(LocalDate date, java.util.Set<String> targets,
                               java.util.Set<String> types, int limit) {
        // Volcamos lo pendiente para que el archivo del dia incluya lo mas reciente.
        flush();
        List<Entry> out = new ArrayList<>();
        if (limit <= 0) return out;
        Path file = plugin.getDataFolder().toPath().resolve("logs")
                .resolve("chat-" + date.format(DAY) + ".log");
        if (!Files.exists(file)) return out;
        if (!Files.isReadable(file)) {
            throw new java.io.UncheckedIOException(
                    new IOException("Chat log is not readable: " + file));
        }
        // Lectura streaming y cola acotada: un log de un servidor grande no debe cargarse
        // entero en el heap al ejecutar /chatlog. Conservamos solo los ultimos N matches.
        Deque<Entry> tail = new ArrayDeque<>(Math.min(limit, 1024));
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var m = LINE.matcher(line);
                if (!m.matches() || !involves(m.group(3), targets)
                        || !isType(m.group(2), types)) continue;
                if (tail.size() >= limit) tail.pollFirst();
                tail.addLast(new Entry(m.group(1), m.group(2), m.group(3), m.group(4)));
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not read log for " + date + ": " + ex.getMessage());
            throw new java.io.UncheckedIOException(ex);
        }
        return new ArrayList<>(tail);
    }

    /** Ejecuta flush + recorrido del archivo exclusivamente en el scheduler asincrono. */
    public java.util.concurrent.CompletableFuture<List<Entry>> readDayAsync(
            LocalDate date, java.util.Set<String> targets, int limit) {
        return readDayAsync(date, targets, null, limit);
    }

    /** Variante asincrona con filtro de tipo (ver {@link #readDay}). */
    public java.util.concurrent.CompletableFuture<List<Entry>> readDayAsync(
            LocalDate date, java.util.Set<String> targets,
            java.util.Set<String> types, int limit) {
        java.util.concurrent.CompletableFuture<List<Entry>> result =
                new java.util.concurrent.CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    result.complete(readDay(date, targets, types, limit));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    // ¿La linea (cuyo "who" puede ser "A" o "A -> B" en PMs) involucra a alguno de los
    // jugadores buscados? Sin filtro (null/vacio) -> todas las lineas.
    /** True si el tipo de la linea esta entre los pedidos (null/vacio = todos). */
    private static boolean isType(String type, java.util.Set<String> types) {
        if (types == null || types.isEmpty()) return true;
        for (String wanted : types) {
            if (wanted.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static boolean involves(String who, java.util.Set<String> targets) {
        if (targets == null || targets.isEmpty()) return true;
        for (String token : who.split("[^A-Za-z0-9_]+")) {
            for (String t : targets) {
                if (token.equalsIgnoreCase(t)) return true;
            }
        }
        return false;
    }

    private void flush() {
        synchronized (fileLock) {
            if (buffer.isEmpty()) return;
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = buffer.poll()) != null) lines.add(line);
            if (lines.isEmpty()) return;
            try {
                Path dir = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(dir);
                // Rotacion diaria: un archivo por dia (chat-YYYY-MM-DD.log).
                Path file = dir.resolve("chat-" + LocalDate.now().format(DAY) + ".log");
                Files.write(file,
                        (String.join(System.lineSeparator(), lines) + System.lineSeparator())
                                .getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                bufferSize.addAndGet(-lines.size()); // solo se descuenta lo YA escrito
            } catch (IOException ex) {
                // Conserva el lote para el siguiente flush. Se reinserta al frente y en el
                // orden original para que un fallo temporal de disco no borre auditoria.
                for (int i = lines.size() - 1; i >= 0; i--) {
                    buffer.addFirst(lines.get(i));
                }
                plugin.getLogger().warning("Could not write the chat log: " + ex.getMessage());
            }
        }
    }

    // Borra los archivos chat-YYYY-MM-DD.log mas viejos que Keep_Days (0 = conservar todos).
    private void cleanOldLogs() {
        if (keepDays <= 0) return;
        Path dir = plugin.getDataFolder().toPath().resolve("logs");
        if (!Files.isDirectory(dir)) return;
        LocalDate cutoff = LocalDate.now().minusDays(keepDays);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "chat-*.log")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                // chat-YYYY-MM-DD.log -> extraemos la fecha entre "chat-" y ".log"
                String datePart = name.substring(5, name.length() - 4);
                try {
                    if (LocalDate.parse(datePart, DAY).isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception ignore) {
                    // nombre que no encaja (p. ej. el viejo chat.log): lo dejamos en paz
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not clean old logs: " + ex.getMessage());
        }
    }

    static int normalizeKeepDays(int configured) {
        // LocalDate tiene un rango finito. Un entero extremo no debe cancelar para siempre
        // la tarea periodica de limpieza con DateTimeException.
        return Math.max(0, Math.min(36_500, configured));
    }
}
