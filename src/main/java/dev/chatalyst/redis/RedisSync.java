package dev.chatalyst.redis;

import org.bukkit.plugin.java.JavaPlugin;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import dev.chatalyst.util.StableServerIdentity;

/** Minimal Redis pub/sub transport for network sync without adding a shaded client. */
public final class RedisSync {

    // Limites del parser RESP: nadie legitimo manda frames mas grandes que esto por este
    // canal; sin limite, un peer comprometido podria agotar la memoria del server.
    private static final int MAX_BULK_BYTES = 256 * 1024;
    private static final int MAX_ARRAY_ELEMENTS = 64;
    private static final int MAX_LINE_BYTES = 4096;

    private final JavaPlugin plugin;
    private final Consumer<byte[]> receiver;

    // Volatile: publish() lo lee desde hilos async y stop() lo apaga desde el main thread.
    private volatile boolean enabled;
    private String host;
    private int port;
    private String password;
    private int database;
    private String channel;
    private String serverId;
    private boolean tls;
    private RedisMessageCodec codec;
    private volatile boolean running;
    private volatile Thread subscriber;
    private volatile Socket subscriptionSocket;
    // Conexion dedicada de publicacion, reutilizada entre mensajes (ver publishBlocking).
    private final Object publishLock = new Object();
    private volatile Socket publishSocket;
    // Sockets a medio abrir (TCP/TLS/AUTH en curso). stop() tambien los cierra: antes solo
    // conocia los ya instalados, y un handshake TLS colgado no se podia interrumpir.
    private final java.util.Set<Socket> connecting =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RedisSync(JavaPlugin plugin, Consumer<byte[]> receiver) {
        this.plugin = plugin;
        this.receiver = receiver;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Redis.Enabled", false);
        host = normalizeHost(plugin.getConfig().getString("Redis.Host", "127.0.0.1"));
        port = normalizePort(plugin.getConfig().getInt("Redis.Port", 6379));
        password = plugin.getConfig().getString("Redis.Password", "");
        database = Math.max(0, Math.min(1_024,
                plugin.getConfig().getInt("Redis.Database", 0)));
        channel = normalizeChannel(plugin.getConfig().getString(
                "Redis.Channel", "chatalyst:network"));
        serverId = plugin.getConfig().getString("Redis.Server_Id", "");
        if (serverId == null || serverId.isBlank()) {
            serverId = StableServerIdentity.resolve(plugin,
                    plugin.getConfig().getString("Database.Server_Id", ""));
        } else {
            serverId = StableServerIdentity.sanitize(serverId);
        }
        tls = plugin.getConfig().getBoolean("Redis.TLS", false);
        // Autenticacion por mensaje: sin secreto compartido no arrancamos (fail closed).
        // El AUTH de Redis solo protege la conexion propia; cualquier cosa con acceso al
        // canal podria inyectar mensajes que este plugin ejecutaria/mostraria.
        String secret = plugin.getConfig().getString("Redis.Shared_Secret", "");
        if (secret != null) secret = secret.trim();
        if (enabled && (secret == null || secret.length() < 16)) {
            plugin.getLogger().severe("Redis.Shared_Secret is missing or shorter than 16 chars; "
                    + "Redis sync stays DISABLED. Set the same long random secret on every "
                    + "backend server (e.g. 32+ random characters).");
            enabled = false;
            secret = null;
        }
        // Apagado no necesita codec, y construirlo con un secreto vacio lanzaba
        // IllegalArgumentException antes de que nadie pudiera desactivar el transporte.
        codec = !enabled || secret == null || secret.length() < 16
                ? null : new RedisMessageCodec(serverId, secret);
    }

    public void start() {
        stop();
        load();
        if (!enabled) return;
        running = true;
        subscriber = new Thread(this::subscribeLoop, "Chatalyst-Redis");
        subscriber.setDaemon(true);
        subscriber.start();
        plugin.getLogger().info("Redis sync enabled on channel '" + channel + "' as " + serverId + ".");
    }

    public void stop() {
        running = false;
        // Un publish encolado que corra DESPUES del stop no debe recrear la conexion de
        // publicacion (quedaria huerfana). start() rehace enabled en load(), asi que esto
        // solo apaga el transporte durante la ventana de apagado/reload.
        enabled = false;
        Socket socket = subscriptionSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        // Cerrar sin esperar el publishLock: si un publisher esta bloqueado en I/O, el close
        // (thread-safe en Socket) lo desbloquea con IOException en vez de colgar el apagado.
        closePublisher();
        for (Socket pending : List.copyOf(connecting)) {
            try {
                pending.close();
            } catch (IOException ignored) {
            }
        }
        if (subscriber != null) {
            subscriber.interrupt();
            subscriber = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void publish(byte[] data) {
        if (!enabled || codec == null || data == null || data.length == 0) return;
        String payload = codec.encode(data, System.currentTimeMillis());
        if (payload == null) {
            plugin.getLogger().warning("Redis sync payload too large ("
                    + data.length + " bytes); not published.");
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    task -> publishBlocking(payload));
        } catch (RuntimeException rejected) {
            // El plugin se apago entre el guard y el enqueue: perder esta invalidacion es
            // preferible a lanzar desde el evento que estaba terminando.
        }
    }

    /**
     * Publica reutilizando UNA conexion dedicada (antes se abria un socket — y con TLS, un
     * handshake completo — por CADA mensaje publicado). El lock serializa a los publishers;
     * si la conexion caduco (timeout de Redis), se reintenta una vez con una fresca.
     */
    private void publishBlocking(String payload) {
        synchronized (publishLock) {
            if (!enabled) return; // stop() gano la carrera: no recrear la conexion
            try {
                publishOnce(payload);
            } catch (IOException stale) {
                closePublisher();
                // stop() cierra el socket para desbloquear este publish: eso NO es una
                // conexion caducada y no hay que reabrir otra (quedaria huerfana tras el stop).
                if (!enabled) return;
                try {
                    publishOnce(payload);
                } catch (IOException failed) {
                    closePublisher();
                    plugin.getLogger().warning("Redis publish failed: " + failed.getMessage());
                }
            }
        }
    }

    /** Debe llamarse con {@code publishLock}. Abre la conexion dedicada si no existe. */
    private void publishOnce(String payload) throws IOException {
        Socket socket = publishSocket;
        if (socket == null || socket.isClosed()) {
            socket = connect();
            // El PUBLISH responde al instante: un timeout corto evita que un Redis colgado
            // retenga el lock (y el hilo async) para siempre.
            socket.setSoTimeout(5_000);
            if (!enabled) {
                // stop() paso mientras se conectaba: no instalar una conexion que ya nadie
                // va a cerrar.
                socket.close();
                throw new IOException("Redis sync stopped");
            }
            publishSocket = socket;
        }
        writeCommand(socket.getOutputStream(), "PUBLISH", channel, payload);
        parse(socket.getInputStream());
    }

    private void closePublisher() {
        Socket socket = publishSocket;
        publishSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * True mientras este hilo siga siendo el subscriber vigente. En un stop()+start()
     * (reload) el hilo viejo podria ver running==true otra vez y quedarse en paralelo
     * con el nuevo; comparar contra el campo evita dos loops suscritos a la vez.
     */
    private boolean current() {
        return running && subscriber == Thread.currentThread();
    }

    private void subscribeLoop() {
        long backoffSeconds = 1;
        while (current()) {
            Socket mine = null;
            try (Socket socket = connect()) {
                mine = socket;
                subscriptionSocket = socket;
                writeCommand(socket.getOutputStream(), "SUBSCRIBE", channel);
                InputStream in = socket.getInputStream();
                parse(in); // confirmacion del SUBSCRIBE, aun bajo el timeout de handshake
                // Solo el socket suscrito espera frames sin limite (es su trabajo); el
                // timeout finito de connect() cubre el handshake para no colgar el hilo
                // contra un Redis que acepta TCP pero nunca responde.
                socket.setSoTimeout(0);
                backoffSeconds = 1;
                while (current()) {
                    Object frame = parse(in); // IOException -> reconecta (catch de abajo)
                    try {
                        handleFrame(frame);
                    } catch (RuntimeException ex) {
                        // Un solo mensaje problematico (payload raro, error SQL en una
                        // invalidacion, etc.) NO debe tumbar el subscriber y cortar toda la
                        // sync de la red hasta reiniciar: se registra y se sigue leyendo.
                        plugin.getLogger().warning("Error handling a Redis sync message: " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                if (!current()) return;
                plugin.getLogger().warning("Redis sync disconnected: " + ex.getMessage());
                try {
                    TimeUnit.SECONDS.sleep(backoffSeconds);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffSeconds = Math.min(30, backoffSeconds * 2);
            } finally {
                // Solo limpiar si sigue siendo NUESTRO socket: un hilo viejo que muere tras
                // un reload no debe borrar la referencia del subscriber nuevo (stop() ya no
                // podria cerrar ese socket).
                if (subscriptionSocket == mine) subscriptionSocket = null;
            }
        }
    }

    private void handleFrame(Object frame) {
        if (!(frame instanceof List<?> parts) || parts.size() < 3) return;
        if (!"message".equalsIgnoreCase(String.valueOf(parts.get(0)))) return;
        if (codec == null) return;
        // El codec verifica HMAC, ventana temporal, nonce (anti-replay), tamano y origen.
        byte[] data = codec.decode(String.valueOf(parts.get(2)), System.currentTimeMillis());
        if (data == null) return;
        receiver.accept(data);
    }

    private Socket connect() throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        boolean handedOff = false;
        connecting.add(socket);
        try {
            // ANTES del handshake: startHandshake lee del socket, y sin timeout un endpoint
            // que acepta el TCP pero no contesta al saludo TLS colgaba el hilo para siempre.
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(host, port), 5000);
            if (socket instanceof SSLSocket ssl) {
                // Verificacion de hostname contra el certificado (evita MITM con cert ajeno).
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
            }
            // Timeout de lectura finito: un Redis que acepta el TCP pero no responde (caido a
            // medias, firewall que descarta en silencio) no debe congelar el hilo que publica
            // (agotaria el pool del async scheduler) ni el arranque del subscriber. El socket
            // de suscripcion lo sube a 0 (bloqueo indefinido) tras confirmar el SUBSCRIBE.
            socket.setSoTimeout(5000);
            if (password != null && !password.isBlank()) {
                writeCommand(socket.getOutputStream(), "AUTH", password);
                parse(socket.getInputStream());
            }
            if (database > 0) {
                writeCommand(socket.getOutputStream(), "SELECT", String.valueOf(database));
                parse(socket.getInputStream());
            }
            handedOff = true;
            return socket;
        } finally {
            connecting.remove(socket);
            // Si connect/TLS/AUTH/SELECT falla, el llamador nunca recibe el socket y su
            // try-with-resources no puede cerrarlo. Sin este finally cada reintento filtraba
            // un descriptor (especialmente visible con Redis caido o credenciales malas).
            if (!handedOff) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static int normalizePort(int configured) {
        return configured >= 1 && configured <= 65_535 ? configured : 6_379;
    }

    static String normalizeHost(String configured) {
        if (configured == null) return "127.0.0.1";
        String clean = configured.trim().replace("\r", "").replace("\n", "")
                .replace("\0", "");
        return clean.isEmpty() || clean.length() > 253 ? "127.0.0.1" : clean;
    }

    static String normalizeChannel(String configured) {
        if (configured == null) return "chatalyst:network";
        String clean = configured.replace("\r", "").replace("\n", "")
                .replace("\0", "").trim();
        if (clean.isEmpty()) return "chatalyst:network";
        return clean.length() <= 256 ? clean : clean.substring(0, 256);
    }

    private static void writeCommand(OutputStream out, String... parts) throws IOException {
        out.write(('*' + String.valueOf(parts.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    private static Object parse(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) throw new EOFException();
        return switch (type) {
            case '+' -> readLine(in);
            case '-' -> throw new IOException(readLine(in));
            case ':' -> parseLong(readLine(in));
            case '$' -> readBulk(in);
            case '*' -> readArray(in);
            default -> throw new IOException("Unknown Redis frame type: " + (char) type);
        };
    }

    /**
     * Un entero corrupto en un frame RESP debe ser IOException (reconectar), no
     * NumberFormatException: esa RuntimeException escaparia del catch de subscribeLoop
     * y mataria el hilo subscriber, cortando la sync de red en silencio hasta reiniciar.
     */
    private static long parseLong(String line) throws IOException {
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid Redis integer frame: " + line);
        }
    }

    private static int parseSize(String line) throws IOException {
        long size = parseLong(line);
        if (size < -1 || size > Integer.MAX_VALUE) {
            throw new IOException("Invalid Redis frame size: " + size);
        }
        return (int) size;
    }

    private static List<Object> readArray(InputStream in) throws IOException {
        int size = parseSize(readLine(in));
        if (size > MAX_ARRAY_ELEMENTS) {
            throw new IOException("Redis array too large: " + size);
        }
        List<Object> out = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            out.add(parse(in));
        }
        return out;
    }

    private static String readBulk(InputStream in) throws IOException {
        int size = parseSize(readLine(in));
        if (size < 0) return "";
        if (size > MAX_BULK_BYTES) {
            throw new IOException("Redis bulk frame too large: " + size + " bytes");
        }
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        consumeCrlf(in);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next == -1) throw new EOFException();
            if (previous == '\r' && next == '\n') break;
            if (previous != -1) out.write(previous);
            if (out.size() > MAX_LINE_BYTES) {
                throw new IOException("Redis line frame too long.");
            }
            previous = next;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void consumeCrlf(InputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Invalid Redis bulk terminator.");
        }
    }
}
