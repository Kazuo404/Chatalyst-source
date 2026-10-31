package dev.chatalyst.book;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.YamlFiles;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Carga los libros definidos en books.yml. Cada libro se abre con /book &lt;nombre&gt; y su
 * contenido (paginas MiniMessage) se resuelve por-jugador al abrirlo.
 */
public final class BookManager {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_PAGES = 100;
    private static final int MAX_PAGE_LENGTH = 4096;

    /** Datos crudos de un libro (el contenido se renderiza al abrirlo, por jugador). */
    public record BookDef(String title, String author, String permission, List<String> pages) {
        public BookDef {
            title = limit(title, 256);
            author = limit(author, 128);
            permission = limit(permission, 128);
            pages = pages == null ? List.of() : pages.stream().limit(MAX_PAGES)
                    .map(page -> limit(page, MAX_PAGE_LENGTH)).toList();
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Object fileLock = new Object();
    // Mapa inmutable reemplazado entero en load(): /book puede correr en otro hilo (Folia)
    // mientras /chatalyst reload recarga los libros.
    private volatile Map<String, BookDef> books = Map.of();

    public BookManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "books.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("books.yml", false);
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception broken) {
            // Carga estricta: un books.yml roto NO se trata como vacio — mutate() reescribe
            // el archivo completo y borraria todos los libros configurados.
            plugin.getLogger().severe("books.yml has a syntax error; keeping the books"
                    + " already in memory. Fix the file and reload: " + broken.getMessage());
            return;
        }
        books = readDefinitions(yaml);
    }

    private static Map<String, BookDef> readDefinitions(YamlConfiguration yaml) {
        // LinkedHashMap: conserva el orden de books.yml en /book (tab-complete y listado).
        Map<String, BookDef> next = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection("Books");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection b = sec.getConfigurationSection(name);
                if (b == null) continue;
                next.put(name.toLowerCase(Locale.ROOT), new BookDef(
                        b.getString("Title", name),
                        b.getString("Author", "Server"),
                        b.getString("Permission", ""),
                        new ArrayList<>(b.getStringList("Pages"))));
            }
        }
        return java.util.Collections.unmodifiableMap(next);
    }

    public BookDef get(String name) {
        return name == null ? null : books.get(name.toLowerCase(Locale.ROOT));
    }

    public List<String> names() {
        return new ArrayList<>(books.keySet());
    }

    public static boolean validName(String name) {
        return name != null && SAFE_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /** Persists a book away from the entity thread and atomically publishes the new snapshot. */
    public CompletableFuture<Boolean> save(String name, BookDef definition) {
        String normalized = normalizeName(name);
        if (normalized == null || definition == null || definition.pages().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(yaml -> {
            String path = "Books." + normalized;
            yaml.set(path + ".Title", definition.title());
            yaml.set(path + ".Author", definition.author());
            yaml.set(path + ".Permission", definition.permission());
            yaml.set(path + ".Pages", definition.pages());
        });
    }

    public CompletableFuture<Boolean> delete(String name) {
        String normalized = normalizeName(name);
        if (normalized == null || get(normalized) == null) {
            return CompletableFuture.completedFuture(false);
        }
        return mutate(yaml -> yaml.set("Books." + normalized, null));
    }

    private CompletableFuture<Boolean> mutate(java.util.function.Consumer<YamlConfiguration> change) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                synchronized (fileLock) {
                    try {
                        // Estricto: si books.yml esta roto, el mutate falla (complete(false))
                        // en vez de cargarlo vacio y machacar todos los libros al escribir.
                        YamlConfiguration yaml = YamlFiles.load(file);
                        change.accept(yaml);
                        writeAtomically(yaml.saveToString());
                        books = readDefinitions(yaml);
                        result.complete(true);
                    } catch (Exception failure) {
                        plugin.getLogger().warning("Could not update books.yml: " + failure.getMessage());
                        result.complete(false);
                    }
                }
            });
        } catch (Throwable rejected) {
            // Durante disable el scheduler puede rechazar la tarea de forma sincrona. El
            // future debe terminar igualmente para no dejar comandos/GUI esperando siempre.
            result.complete(false);
        }
        return result;
    }

    private void writeAtomically(String content) throws java.io.IOException {
        var target = file.toPath();
        var temporary = target.resolveSibling(file.getName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeName(String name) {
        if (!validName(name)) return null;
        return name.toLowerCase(Locale.ROOT);
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value.replace('\u001e', ' ').replace('\u001f', ' ');
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    /** Opens one configured book; optionally enforces its player-facing permission. */
    public boolean open(Player player, String name, boolean enforcePermission) {
        BookDef definition = get(name);
        if (definition == null) return false;
        if (enforcePermission && !definition.permission().isEmpty()
                && !player.hasPermission(definition.permission())) return false;
        List<Component> pages = definition.pages().stream()
                .map(page -> MM.full(Placeholders.apply(player, page)))
                .toList();
        player.openBook(Book.book(
                MM.full(Placeholders.apply(player, definition.title())),
                MM.full(Placeholders.apply(player, definition.author())), pages));
        return true;
    }
}
