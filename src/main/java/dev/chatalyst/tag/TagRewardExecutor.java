package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.Store;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ejecuta recompensas ya validadas desde el scheduler global.
 *
 * <p>Las recompensas pendientes se apuntan en disco ANTES de encolarse. El desbloqueo ya
 * estaba guardado, pero la entrega solo vivia en la cola del scheduler: si el servidor se
 * apagaba entre una cosa y otra, el titulo figuraba desbloqueado y su premio no llegaba nunca,
 * porque ninguna evaluacion vuelve a lanzarlo. Ahora lo que no llego a ejecutarse se lanza la
 * proxima vez que el jugador entra.
 *
 * <p>La marca se borra justo ANTES de despachar, en el mismo tick: un premio puede perderse
 * solo si el proceso muere en ese instante, nunca darse dos veces. Un comando arbitrario no
 * se puede deshacer, asi que repetirlo seria peor que perderlo.
 */
public final class TagRewardExecutor {

    /**
     * Por servidor: los comandos son de la consola y economia de ESTE backend. Compartido en
     * red, un premio pendiente del survival se pagaba en el lobby al entrar alli.
     */
    static final String STORE = "tag-reward-pending";
    /** Separador de comandos: imprimible. Un caracter de control vuelve como byte[] del YAML. */
    private static final String SEPARATOR = "\n";

    private TagRewardExecutor() {
    }

    public static void execute(ChatalystPlugin plugin, Player player,
                               TagDefinition definition) {
        if (plugin == null || player == null || definition == null
                || definition.rewards().commands().isEmpty()) {
            return;
        }
        String playerName = player.getName();
        String playerId = player.getUniqueId().toString();
        List<String> commands = new ArrayList<>();
        for (String template : definition.rewards().commands()) {
            commands.add(template
                    .replace("{player}", playerName)
                    .replace("{uuid}", playerId)
                    .replace("{title_id}", definition.id())
                    .replace("{title_name}", definition.displayName())
                    .replace("{points}", String.valueOf(definition.points()))
                    .replace("{season}", definition.season().id())
                    .replace(SEPARATOR, " "));
        }
        Store pending = pendingStore(plugin);
        String key = key(playerId, definition);
        String value = String.join(SEPARATOR, commands);
        if (pending == null || !pending.putDurably(key, value)) {
            // Sin donde apuntarlo: como antes, se intenta en el acto.
            FoliaTasks.global(plugin, () -> dispatch(plugin, definition.id(), commands));
            return;
        }
        FoliaTasks.global(plugin, () -> claimAndDispatch(plugin, pending, key, definition.id()));
    }

    /** Lanza lo que quedo sin entregar a este jugador (se llama al entrar). */
    public static void replay(ChatalystPlugin plugin, Player player) {
        Store pending = pendingStore(plugin);
        if (pending == null || player == null) return;
        String prefix = player.getUniqueId() + ":";
        for (Map.Entry<String, String> entry : pending.all().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String key = entry.getKey();
            String titleId = key.substring(prefix.length());
            FoliaTasks.global(plugin, () -> claimAndDispatch(plugin, pending, key, titleId));
        }
    }

    private static void claimAndDispatch(ChatalystPlugin plugin, Store pending, String key,
                                         String titleId) {
        String value = pending.get(key);
        if (value == null) return;
        // Quien consigue borrar la marca es quien entrega: dos replays a la vez no duplican.
        if (!pending.compareAndRemoveDurably(key, value)) return;
        dispatch(plugin, titleId, List.of(value.split(SEPARATOR, -1)));
    }

    private static void dispatch(ChatalystPlugin plugin, String titleId, List<String> commands) {
        for (String command : commands) {
            if (command.isBlank()) continue;
            try {
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), command);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not execute reward command for title '"
                        + titleId + "': " + failure.getMessage());
            }
        }
    }

    static String key(String playerId, TagDefinition definition) {
        return dev.chatalyst.db.StoreKeys.bounded(playerId + ":" + definition.id());
    }

    private static Store pendingStore(ChatalystPlugin plugin) {
        try {
            return plugin.getStorageManager() == null
                    ? null : plugin.getStorageManager().perServerStore(STORE);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
