package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Escribe la lista de permisos de un premio en {@code giveaway.yml}.
 *
 * <p>Es la unica parte del sorteo que MODIFICA el fichero desde el juego, y por eso va aparte:
 * lo demas que se escribe por la GUI (la cantidad, la duracion) vale solo para ESE sorteo y
 * muere con el. Los permisos son la definicion del premio, y quien los edita espera encontrarlos
 * ahi la semana que viene.
 *
 * <p>Sigue el camino que ya usa el editor de formatos: lock del fichero, carga ESTRICTA y
 * escritura atomica. Lo estricto no es adorno — un giveaway.yml con un error de sintaxis se
 * cargaria como vacio, y guardar encima borraria todos los demas premios de un plumazo.
 *
 * <p>Los comentarios del fichero sobreviven: {@code YamlConfiguration} los conserva, asi que la
 * documentacion escrita a mano sigue ahi despues de editar desde el menu.
 */
final class GiveawayPermissionsFile {

    private final ChatalystPlugin plugin;

    GiveawayPermissionsFile(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true si quedo escrito en disco. False deja el fichero como estaba, y quien llama
     *         debe decirlo: un guardado que falla en silencio es peor que no ofrecer el boton.
     */
    boolean save(String prizeId, List<String> permissions) {
        File file = new File(plugin.getDataFolder(), "giveaway.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("giveaway.yml does not exist, so the permissions of '"
                    + prizeId + "' were not saved.");
            return false;
        }
        try {
            synchronized (YamlFiles.lock(file)) {
                YamlConfiguration yaml = YamlFiles.load(file);
                String path = "Giveaway_Prizes." + prizeId;
                if (yaml.getConfigurationSection(path) == null) {
                    // Un premio detectado (un rango que LuckPerms tiene y nadie escribio) no
                    // tiene entrada donde guardar. Se dice, no se inventa la entrada: crearla a
                    // medias dejaria un premio sin nombre ni icono en el fichero de todos.
                    plugin.getLogger().warning("Giveaway prize '" + prizeId + "' is not written"
                            + " in giveaway.yml, so its permissions cannot be saved there.");
                    return false;
                }
                yaml.set(path + ".Permissions", permissions.isEmpty() ? null : permissions);
                YamlFiles.saveAtomic(yaml, file);
            }
        } catch (IOException | InvalidConfigurationException broken) {
            plugin.getLogger().warning("Could not save the permissions of giveaway prize '"
                    + prizeId + "': " + broken.getMessage());
            return false;
        }
        // El fichero es un split: sin re-fusionar, getConfig() seguiria devolviendo la lista
        // vieja y el menu se repintaria con lo que acaba de dejar de ser verdad.
        plugin.reloadConfig();
        return true;
    }
}
