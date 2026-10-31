package dev.chatalyst.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Removes MiniMessage colour-closing tags that have nothing to close.
 *
 * <p>A close with no matching open is printed as literal text, so {@code <#FBF2FF>{player}</white>}
 * puts the characters {@code </white>} on the player's screen. They are residue of the old
 * named-colour palette: the palette is hex now, and a hex colour cannot be closed by name.
 *
 * <p>The files that ship inside the jar were repaired at the source, but that helps nobody who
 * already had Chatalyst installed: a file on disk always wins over the jar's copy, so an
 * existing server keeps showing the broken text forever. This repairs those files in place.
 *
 * <p>Deliberately narrow, because it edits text the owner may have written:
 * <ul>
 *   <li>only <b>named colour</b> closes — never {@code </bold>}, {@code </gradient>},
 *       {@code </click>} or anything else that pairs legitimately;</li>
 *   <li>only when that exact colour is never opened anywhere in the file, so a deliberate
 *       {@code <white>text</white>} is left completely alone;</li>
 *   <li>a backup is written first, and every change is logged.</li>
 * </ul>
 *
 * <p>Idempotent: once the orphans are gone there is nothing left to match, so it is safe on
 * every startup and needs no version marker to remember it already ran.
 */
public final class OrphanTagRepair {

    /** Named colours only. A hex open cannot be closed by name, which is why these are orphans. */
    private static final Set<String> COLOURS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white");

    private static final Pattern CLOSE = Pattern.compile("</([a-z_]+)>");
    private static final Pattern OPEN = Pattern.compile("<([a-z_]+)>");

    private OrphanTagRepair() {
    }

    /**
     * Folders whose yml files are CONFIGURATION — written by the owner, read as templates.
     *
     * <p>An allowlist rather than a denylist, because the file this must never touch is
     * {@code data/}: those are persisted records, and their text belongs to players and staff
     * — a mail someone sent, an item name, the reason typed on a ban. Rewriting a record to
     * tidy a tag is not tidying, it is editing what somebody said. A denylist would quietly
     * swallow whatever folder gets added next.
     */
    private static final Set<String> CONFIGURATION_FOLDERS = Set.of("lang", "formats");

    /** Repairs the configuration yml files. Returns the number of files changed. */
    public static int run(JavaPlugin plugin) {
        Path root = plugin.getDataFolder().toPath();
        if (!Files.isDirectory(root)) return 0;
        int repaired = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            List<Path> candidates = files
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .filter(path -> isConfiguration(root, path))
                    // Backups are history: repairing them would destroy the copy they exist to be.
                    .filter(path -> !path.getFileName().toString().contains(".bak"))
                    .toList();
            for (Path file : candidates) {
                if (repair(plugin, file)) repaired++;
            }
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().warning("Could not scan for orphan colour tags: " + failure);
        }
        return repaired;
    }

    /** A file directly in the plugin folder, or inside one of the configuration folders. */
    static boolean isConfiguration(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() == 1) return true;
        if (relative.getNameCount() != 2) return false;
        return CONFIGURATION_FOLDERS.contains(
                relative.getName(0).toString().toLowerCase(Locale.ROOT));
    }

    private static boolean repair(JavaPlugin plugin, Path file) {
        String original;
        try {
            original = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return false; // no es asunto nuestro si el archivo no se puede leer
        }
        Set<String> opened = openedColours(original);
        List<String> removed = new ArrayList<>();
        String repaired = strip(original, opened, removed);
        if (repaired.equals(original)) return false;

        try {
            Path backup = file.resolveSibling(file.getFileName() + ".bak-tags");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            writeAtomically(file, repaired);
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().warning("Could not repair orphan colour tags in "
                    + file.getFileName() + ": " + failure);
            return false;
        }
        plugin.getLogger().info("Repaired " + removed.size() + " colour tag(s) in "
                + file.getFileName() + " that were being printed as literal text ("
                + String.join(", ", removed.stream().distinct().sorted().toList())
                + "). The previous file is kept as " + file.getFileName() + ".bak-tags.");
        return true;
    }

    /**
     * Escribe a un temporal y lo mueve encima. Escribir en sitio trunca primero: si el disco
     * se llena o el proceso muere a mitad, el dueño se queda con una configuracion cortada
     * por la mitad — y esto corre en el arranque, antes de que nadie pueda reaccionar.
     */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(),
                ".repairing");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Colours the file opens by name anywhere: those closes are legitimate and stay. */
    static Set<String> openedColours(String text) {
        Deque<String> found = new ArrayDeque<>();
        Matcher matcher = OPEN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (COLOURS.contains(name)) found.add(name);
        }
        return Set.copyOf(found);
    }

    /** Drops every close of a colour the file never opens, recording what it dropped. */
    static String strip(String text, Set<String> opened, List<String> removed) {
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = CLOSE.matcher(text);
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!COLOURS.contains(name) || opened.contains(name)) continue;
            // "\</red>" es un cierre ESCAPADO: texto que el dueño quiere que se lea tal cual
            // (una ayuda que explica MiniMessage), no una etiqueta huerfana que reparar.
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '\\') continue;
            out.append(text, last, matcher.start());
            removed.add(matcher.group());
            last = matcher.end();
        }
        return out.append(text, last, text.length()).toString();
    }
}
