package dev.chatalyst.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copia intacta de la configuracion ANTES de que las migraciones por version la toquen.
 *
 * <p>Subir de version no solo añade claves nuevas: hay migraciones que REESCRIBEN texto que ya
 * estaba escrito — la paleta morada sustituye colores en todos los {@code .yml}, {@code lang/}
 * y {@code formats/}; otras cambian tokens de los formatos, recolorean hovers o quitan cierres
 * de color huerfanos. Todas ellas apuntan al texto de fabrica y son idempotentes, pero
 * trabajan sobre ficheros que el dueño ha podido editar, y un reemplazo de texto no sabe
 * distinguir un color de fabrica de uno que alguien eligio a proposito.
 *
 * <p>Asi que antes de la primera migracion se guarda la carpeta entera. Si el resultado no le
 * gusta al dueño, recuperar su configuracion es copiar una carpeta, no reconstruirla de
 * memoria. Y si la copia no se puede escribir, {@link ConfigUpgrader} no migra: en esta base
 * de codigo la regla ya es que sin respaldo verificado se prefiere dejar el fichero como esta.
 *
 * <p>Solo corre cuando hay salto de version, de modo que un arranque normal no copia nada.
 */
public final class ConfigSnapshot {

    /** Donde viven las copias. Empieza por {@code _} para que quede junta y arriba del todo. */
    static final String FOLDER = "_pre-upgrade";

    /**
     * Carpetas cuyo contenido pueden reescribir las migraciones. Es una lista blanca por el
     * mismo motivo que en {@link OrphanTagRepair}: {@code data/} son registros de jugadores y
     * no configuracion, copiarlos en cada actualizacion seria duplicar la base de datos.
     */
    private static final Set<String> MIGRATED_FOLDERS = Set.of("lang", "formats");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ConfigSnapshot() {
    }

    /**
     * Copia la configuracion migrable a {@code _pre-upgrade/v&lt;from&gt;-&lt;fecha&gt;/}.
     *
     * @return {@code true} si la copia quedo completa (o no habia nada que copiar).
     */
    public static boolean take(JavaPlugin plugin, int from) {
        Path root = plugin.getDataFolder().toPath();
        if (!Files.isDirectory(root)) return true; // aun no hay configuracion que respaldar
        Path destination = root.resolve(FOLDER)
                .resolve("v" + from + "-" + LocalDateTime.now().format(STAMP));
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root, 2)) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> snapshots(root.relativize(path)))
                    .toList();
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().severe("Could not read the configuration to back it up before"
                    + " upgrading: " + failure);
            return false;
        }
        if (sources.isEmpty()) return true;

        try {
            for (Path source : sources) {
                Path target = destination.resolve(root.relativize(source));
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().severe("Could not back up the configuration before upgrading: "
                    + failure);
            return false;
        }
        plugin.getLogger().info("Backed up " + sources.size() + " configuration file(s) to "
                + FOLDER + "/" + destination.getFileName() + " before upgrading from v" + from
                + " to v" + ConfigUpgrader.CURRENT_VERSION + ". Restore that folder if any"
                + " customised text changed in a way you did not want.");
        return true;
    }

    /**
     * ¿Es un fichero que las migraciones pueden reescribir?
     *
     * <p>Los {@code .yml} sueltos de la carpeta del plugin y los de {@code lang/} y
     * {@code formats/}. Nunca los respaldos: copiar una copia solo hace crecer la carpeta, y
     * la copia mas antigua es justo la que no conviene sepultar. Ruta RELATIVA a la carpeta
     * del plugin.
     */
    static boolean snapshots(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml")) return false;
        if (name.contains(".bak") || name.contains(".pre-")) return false;
        int depth = relative.getNameCount();
        if (depth == 1) return true;
        if (depth != 2) return false;
        String folder = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        return MIGRATED_FOLDERS.contains(folder);
    }
}
