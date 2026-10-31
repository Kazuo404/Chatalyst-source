package dev.chatalyst.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Las imagenes de los carteles: {@code plugins/Chatalyst/images/} (y {@code images/cache/}
 * para las descargadas). NUNCA se lee, descarga ni descodifica nada en el hilo del servidor:
 * todo va a un pool propio de dos hilos, y quien pide recibe un futuro.
 *
 * <p>Se cachea en memoria la imagen descodificada (pocas, con tope) y, aparte, lo que se
 * pinta: el pixel art de cada (imagen, ancho) y los mapas de cada mural. Asi un GIF no se
 * vuelve a convertir ni a trocear jamas por tick: el reloj solo elige que fotograma enseñar.
 */
final class ImageLibrary {

    /** Pixel art listo: un componente por fotograma. */
    record PixelArtImage(List<Component> frames, int[] delays, int rows, int columns) {

        boolean animated() {
            return frames.size() > 1;
        }
    }

    /** Mapas de un mural listos: por fotograma, los bytes de cada mapa (por filas). */
    record MuralArt(List<List<byte[]>> frames, int[] delays, boolean[][] changed) {

        boolean animated() {
            return frames.size() > 1;
        }
    }

    private static final int MAX_DECODED = 24;
    private static final int MAX_DERIVED = 64;

    private final JavaPlugin plugin;
    private final File folder;
    private final File cacheFolder;
    private final ExecutorService pool;
    private final HttpClient http;
    private volatile ImageSettings settings;
    private volatile MapColorMatcher matcher;
    private final Map<String, CompletableFuture<DecodedImage>> decoded = lru(MAX_DECODED);
    private final Map<String, CompletableFuture<PixelArtImage>> pixelArt = lru(MAX_DERIVED);
    private final Map<String, CompletableFuture<MuralArt>> murals = lru(MAX_DERIVED);
    private volatile List<String> names = List.of();
    private volatile long namesAt;

    ImageLibrary(JavaPlugin plugin, ImageSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.folder = new File(plugin.getDataFolder(), "images");
        this.cacheFolder = new File(folder, "cache");
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "Chatalyst-Images");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        pool.execute(this::prepareFolder);
    }

    void reloadSettings(ImageSettings next) {
        settings = next;
        clear();
    }

    ImageSettings settings() {
        return settings;
    }

    /** Olvida lo cacheado (tras /cartel recargar: el dueño puede haber cambiado un archivo). */
    void clear() {
        synchronized (decoded) {
            decoded.clear();
        }
        synchronized (pixelArt) {
            pixelArt.clear();
        }
        synchronized (murals) {
            murals.clear();
        }
        namesAt = 0;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    // ---- Carpeta y nombres ---------------------------------------------------------------

    private void prepareFolder() {
        try {
            Files.createDirectories(cacheFolder.toPath());
            File readme = new File(folder, "LEEME.txt");
            if (!readme.exists()) {
                Files.writeString(readme.toPath(), README, StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not prepare the images folder: " + failure.getMessage());
        }
        refreshNames();
    }

    /** Nombres para autocompletar. La carpeta se relee en segundo plano, como mucho cada 10 s. */
    List<String> names() {
        if (System.currentTimeMillis() - namesAt > 10_000L) {
            namesAt = System.currentTimeMillis();
            try {
                pool.execute(this::refreshNames);
            } catch (RuntimeException shuttingDown) {
                // Apagandose: se queda la lista que habia.
            }
        }
        return names;
    }

    private void refreshNames() {
        File[] files = folder.listFiles();
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && ImageSource.parse(file.getName()) != null) out.add(file.getName());
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        names = List.copyOf(out);
        namesAt = System.currentTimeMillis();
    }

    // ---- Carga -------------------------------------------------------------------------------

    /** La imagen descodificada (cacheada). Falla con un mensaje claro si no existe o no vale. */
    CompletableFuture<DecodedImage> image(ImageSource source) {
        synchronized (decoded) {
            CompletableFuture<DecodedImage> cached = decoded.get(source.value());
            if (cached != null && !cached.isCompletedExceptionally()) return cached;
            CompletableFuture<DecodedImage> future = CompletableFuture.supplyAsync(() -> {
                try {
                    ImageSettings s = settings;
                    byte[] data = source.url() ? download(source.value(), s) : readLocal(source, s);
                    return ImageDecoder.decode(data, s.maxFrames(), s.maxDimension());
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, pool);
            decoded.put(source.value(), future);
            return future;
        }
    }

    private byte[] readLocal(ImageSource source, ImageSettings s) throws IOException {
        Path base = folder.toPath().toAbsolutePath().normalize();
        Path file = base.resolve(source.value()).normalize();
        // Segunda barrera ademas del patron del nombre: nunca fuera de images/.
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new IOException("image not found: images/" + source.value());
        }
        if (Files.size(file) > s.maxFileBytes()) {
            throw new IOException("image larger than " + (s.maxFileBytes() / 1024) + " KB");
        }
        return Files.readAllBytes(file);
    }

    private byte[] download(String url, ImageSettings s) throws IOException {
        if (!s.urlEnabled()) throw new IOException("images by URL are disabled");
        File cached = new File(cacheFolder, sha256(url) + ".img");
        if (cached.isFile() && cached.length() <= s.maxFileBytes()) {
            return Files.readAllBytes(cached.toPath());
        }
        refusePrivateHost(URI.create(url));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Chatalyst-Holograms")
                .GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("download failed: HTTP " + response.statusCode());
        }
        // Despues de las redirecciones tambien tiene que ser https.
        if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
            response.body().close();
            throw new IOException("the URL redirected to a non-https address");
        }
        try {
            refusePrivateHost(response.uri());
        } catch (IOException privateHost) {
            response.body().close();
            throw privateHost;
        }
        byte[] data;
        try (InputStream in = response.body()) {
            data = readLimited(in, s.maxFileBytes());
        }
        Files.createDirectories(cacheFolder.toPath());
        File temp = new File(cacheFolder, cached.getName() + ".tmp");
        Files.write(temp.toPath(), data);
        Files.move(temp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return data;
    }

    /**
     * Nada de pedir imagenes a la propia maquina ni a la red de casa (el panel del router, el
     * MySQL, la API interna...): solo direcciones publicas de internet. Se comprueba antes de
     * pedir y otra vez con la direccion final tras las redirecciones.
     */
    static void refusePrivateHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IOException("the URL has no host");
        for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()
                    || (address instanceof java.net.Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC)) {
                throw new IOException("images can only be downloaded from public addresses");
            }
        }
    }

    /** Lee como mucho {@code max} bytes; si hay mas, falla (no se queda con media imagen). */
    static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("image larger than " + (max / 1024) + " KB");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ---- Derivados -----------------------------------------------------------------------------

    /** Pixel art de esa imagen a ese ancho (cacheado). */
    CompletableFuture<PixelArtImage> pixelArt(ImageSource source, int width) {
        String key = source.value() + "@" + width;
        synchronized (pixelArt) {
            CompletableFuture<PixelArtImage> cached = pixelArt.get(key);
            if (cached != null && !cached.isCompletedExceptionally()) return cached;
            ImageSettings s = settings;
            CompletableFuture<PixelArtImage> future = image(source).thenApplyAsync(image -> {
                int height = ImageResize.proportionalHeight(image.width(), image.height(), width);
                List<Component> frames = new ArrayList<>(image.frames().size());
                int columns = 0;
                for (ImageFrame frame : image.frames()) {
                    ImageFrame small = ImageResize.resize(frame, width, height);
                    var runs = PixelArt.runs(small, s.colorBits());
                    columns = Math.max(columns, PixelArt.columns(runs));
                    frames.add(PixelArt.component(runs, PixelArt.DEFAULT_PIXEL, s.pixelAdvance()));
                }
                return new PixelArtImage(List.copyOf(frames), image.delays(), height,
                        Math.max(1, columns));
            }, pool);
            pixelArt.put(key, future);
            return future;
        }
    }

    /** Mapas de un mural (cacheados): escalado, paleta de mapas, dithering y troceado. */
    CompletableFuture<MuralArt> mural(ImageSource source, MuralLayout.Size size,
                                      ImageResize.Fit fit, boolean dither) {
        String key = source.value() + "|" + size.width() + "x" + size.height() + "|" + fit
                + "|" + dither;
        synchronized (murals) {
            CompletableFuture<MuralArt> cached = murals.get(key);
            if (cached != null && !cached.isCompletedExceptionally()) return cached;
            CompletableFuture<MuralArt> future = image(source).thenApplyAsync(image -> {
                MapColorMatcher colors = matcher();
                int w = size.width() * MuralLayout.TILE;
                int h = size.height() * MuralLayout.TILE;
                List<List<byte[]>> frames = new ArrayList<>(image.frames().size());
                for (ImageFrame frame : image.frames()) {
                    ImageFrame fitted = ImageResize.fit(frame, w, h, fit);
                    frames.add(MuralLayout.tiles(colors.convert(fitted, dither), size));
                }
                return new MuralArt(List.copyOf(frames), image.delays(), changedTiles(frames));
            }, pool);
            murals.put(key, future);
            return future;
        }
    }

    /**
     * Que mapas cambian al pasar a cada fotograma (respecto al anterior, en bucle). Se calcula
     * una vez: al animar solo se reenvian esos.
     */
    static boolean[][] changedTiles(List<List<byte[]>> frames) {
        int count = frames.size();
        int tiles = frames.getFirst().size();
        boolean[][] out = new boolean[count][tiles];
        for (int f = 0; f < count; f++) {
            List<byte[]> previous = frames.get(Math.floorMod(f - 1, count));
            for (int t = 0; t < tiles; t++) {
                out[f][t] = count == 1 || !java.util.Arrays.equals(previous.get(t), frames.get(f).get(t));
            }
        }
        return out;
    }

    /** La paleta de mapas del propio servidor (MapPalette), convertida una vez. */
    @SuppressWarnings("deprecation")
    private MapColorMatcher matcher() {
        MapColorMatcher current = matcher;
        if (current != null) return current;
        int[] palette = new int[256];
        java.util.Arrays.fill(palette, -1);
        for (int i = 4; i < 256; i++) {
            try {
                java.awt.Color color = org.bukkit.map.MapPalette.getColor((byte) i);
                if (color.getAlpha() >= 128) palette[i] = color.getRGB() & 0xFFFFFF;
            } catch (RuntimeException outOfPalette) {
                break; // los indices que el servidor no conoce no se usan
            }
        }
        current = new MapColorMatcher(palette);
        matcher = current;
        return current;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static <V> Map<String, V> lru(int max) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    /** Mensaje corto de por que fallo una carga (para el staff). */
    static String reason(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static final String README = """
            Imagenes para los carteles de Chatalyst (/cartel)
            =================================================

            Pon aqui archivos PNG, JPG o GIF (tambien GIF animados). El nombre del archivo es lo
            que se escribe en el comando (sin espacios raros ni carpetas).

              /cartel mural <id> <imagen> <ancho>x<alto> [ajustar|recortar|estirar] [brillo]
                  Mural de mapas en la pared, el suelo o el techo al que miras. Cada bloque es un
                  mapa de 128x128 pixeles. Tambien se ve en Bedrock.

              /cartel imagen <id> <imagen> [ancho]
                  Pixel art dentro de un cartel flotante (por defecto 32 pixeles de ancho). Se
                  combina con lineas de texto. Lo transparente se respeta.

            Despues de cambiar un archivo, usa /cartel recargar.
            La carpeta cache/ guarda las imagenes descargadas por URL: se puede borrar.

            ---

            Images for Chatalyst holograms (/cartel). Put PNG, JPG or GIF files here (animated
            GIFs work too) and use the file name in /cartel mural or /cartel imagen.
            Run /cartel recargar after changing a file. cache/ holds downloaded images.
            """;
}
