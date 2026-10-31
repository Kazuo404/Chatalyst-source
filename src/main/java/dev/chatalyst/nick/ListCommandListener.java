package dev.chatalyst.nick;

import dev.chatalyst.lang.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /list} decia el nombre REAL, y con eso se cae el disfraz entero.
 *
 * <p>El comando vanilla no pasa por ningun formato del plugin: construye su linea con
 * {@code Player.getDisplayName()}, que en Minecraft es el nombre de la cuenta con el color del
 * equipo — no el display name de Bukkit que escribe {@link NickManager}. Asi que el apodo
 * mandaba en el chat, en el tab y en los nametags, y cualquiera escribia {@code /list} y leia
 * el nombre de verdad. Con {@code /list uuids} salia ademas el UUID, que sirve para buscar la
 * cuenta aunque el nombre no se reconozca.
 *
 * <p>Y no es un fallo de {@code /anonymous} ni de {@code /nick} en particular: es la ultima
 * superficie del servidor donde el nombre seguia saliendo entero. Aqui el comando se atiende
 * con los nombres MOSTRADOS, respetando lo mismo que respetaba el vanilla — quien no te ve
 * (vanish, transiciones) sigue sin contarte.
 *
 * <p>El staff que puede usar {@code /realname} no se intercepta: ya tiene permitido resolver
 * identidades, y quitarle el {@code uuids} solo le complicaria el trabajo sin esconder nada
 * que no pudiera pedir de otra forma.
 */
public final class ListCommandListener implements Listener {

    /** Quien resuelve identidades por permiso sigue viendo la salida vanilla, uuids incluidos. */
    private static final String BYPASS = "chatalyst.command.realname";

    private final JavaPlugin plugin;

    public ListCommandListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("Nick.Hide_Real_Names_In_List", true)) return;
        Form form = form(event.getMessage());
        if (form == Form.OTHER) return;
        // Quien de verdad contesta a un /list a secas puede no ser Minecraft: `list` es alias
        // de /players en este mismo plugin, y Essentials y compañia tambien lo reclaman. Si lo
        // sirve un plugin, ya imprime nombres mostrados y aqui no hay nada que tapar —
        // cancelarlo le robaria su comando. Con `minecraft:` delante no hay duda: ese es el
        // vanilla, y ese es el que dice el nombre de la cuenta.
        if (form == Form.BARE && plugin.getServer().getPluginCommand("list") != null) return;
        Player viewer = event.getPlayer();
        if (viewer.hasPermission(BYPASS)) return;
        event.setCancelled(true);
        List<String> shown = new ArrayList<>();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            // Igual que el vanilla, que filtra por canSee: a quien no ves no se te cuenta.
            if (!viewer.canSee(other)) continue;
            shown.add(NickManager.safeName(other));
        }
        viewer.sendMessage(Lang.get(viewer, "vanilla.list",
                "count", String.valueOf(shown.size()),
                "max", String.valueOf(plugin.getServer().getMaxPlayers()),
                "players", String.join("<#7B68A0>, <#FBF2FF>", shown)));
    }

    /** Como se escribio el comando: a secas, con {@code minecraft:} delante, o ninguno de los dos. */
    enum Form { BARE, NAMESPACED, OTHER }

    /**
     * Reconoce el {@code /list} del servidor y distingue las dos formas de escribirlo, porque
     * no significan lo mismo: {@code minecraft:list} es el vanilla con seguridad, y {@code list}
     * a secas es de quien lo haya reclamado. Un {@code /list} de otro namespace no es asunto
     * nuestro.
     */
    static Form form(String message) {
        if (message == null) return Form.OTHER;
        String label = message.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) label = label.substring(1);
        int namespace = label.indexOf(':');
        if (namespace < 0) return label.equals("list") ? Form.BARE : Form.OTHER;
        if (!"minecraft".equals(label.substring(0, namespace))) return Form.OTHER;
        return label.substring(namespace + 1).equals("list") ? Form.NAMESPACED : Form.OTHER;
    }
}
