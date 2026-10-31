package dev.chatalyst.nick;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * El fichero {@code protected-names.txt}: la lista de nombres reservados que MANTIENE el
 * plugin, separada de la que mantiene el dueño del servidor.
 *
 * <p>Existe por una razon concreta: una lista larga dentro de {@code players.yml} no se puede
 * actualizar nunca. La config es del comprador —lleva sus decisiones— y reescribirla para
 * meterle nombres nuevos seria pisarle sus valores. Con el fichero aparte, una version nueva
 * de Chatalyst trae su lista al dia y la config del comprador no se toca.
 *
 * <p>El contrato es el que dice la cabecera del propio fichero: este lo reescribe el plugin,
 * el de {@code Nick.Protected_Names} es del dueño y no se toca jamas. Las dos listas suman.
 */
public final class ProtectedNamesFile {

    static final String NAME = "protected-names.txt";
    // Tolerante con el formato de la cabecera: quien reordene o realinee el comentario no
    // debe convertir su fichero en "version 0" y llevarse una sobreescritura por sorpresa.
    private static final java.util.regex.Pattern VERSION_LINE =
            java.util.regex.Pattern.compile("^#\\s*version\\s*:\\s*(\\d+)\\s*$");

    private ProtectedNamesFile() {
    }

    /**
     * Instala o actualiza el fichero y deja sus nombres cargados.
     *
     * <p>Solo se sobreescribe cuando la version del jar es MAYOR que la del disco: asi un
     * servidor que se quede en una version no ve cambiar su lista bajo los pies, y uno que
     * actualiza la recibe sin intervencion. Si algo falla no se propaga: quedarse sin la
     * lista larga degrada la proteccion, pero tumbar el arranque por un fichero de texto
     * seria mucho peor.
     */
    public static void install(JavaPlugin plugin) {
        try {
            Path target = plugin.getDataFolder().toPath().resolve(NAME);
            List<String> bundled = readBundled(plugin);
            if (bundled.isEmpty()) {
                plugin.getLogger().warning("The bundled " + NAME + " is missing from the jar;"
                        + " only Nick.Protected_Names will apply.");
                return;
            }
            List<String> installed = Files.exists(target)
                    ? Files.readAllLines(target, StandardCharsets.UTF_8) : List.of();
            if (installed.isEmpty() || version(bundled) > version(installed)) {
                Files.createDirectories(target.getParent());
                Files.write(target, bundled, StandardCharsets.UTF_8);
                installed = bundled;
                plugin.getLogger().info("Installed " + NAME + " v" + version(bundled)
                        + " (" + ProtectedNames.parse(bundled).size() + " names).");
            }
            ProtectedNames.installBundled(ProtectedNames.parse(installed));
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().warning("Could not load " + NAME + ": " + failure.getMessage()
                    + ". Only Nick.Protected_Names will apply.");
        }
    }

    /** La version declarada en la cabecera, o 0 si el fichero no la trae. */
    static int version(List<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            java.util.regex.Matcher header = VERSION_LINE.matcher(line.trim());
            if (!header.matches()) continue;
            try {
                return Integer.parseInt(header.group(1));
            } catch (NumberFormatException tooBig) {
                return 0;
            }
        }
        return 0;
    }

    private static List<String> readBundled(JavaPlugin plugin) throws IOException {
        try (InputStream in = plugin.getResource(NAME)) {
            if (in == null) return List.of();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            return lines;
        }
    }
}
