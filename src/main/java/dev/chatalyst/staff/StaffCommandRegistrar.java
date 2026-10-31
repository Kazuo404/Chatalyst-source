package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registro en runtime del set de comandos de staff. Los comandos NO viven en plugin.yml:
 * asi, con {@code Staff_Moderation.Enabled} apagado Chatalyst no le roba /ban, /kick o
 * /vanish al plugin de staff que el servidor prefiera. Con el modulo activo y
 * {@code Command_Priority} encendido (default) las etiquetas planas pasan a Chatalyst
 * aunque otro plugin las tuviera; el comando desplazado se recuerda y se restaura si el
 * modulo se apaga en un reload.
 */
final class StaffCommandRegistrar implements Listener {

    private final ChatalystPlugin plugin;
    private final StaffCommand handler;
    private final Map<String, Command> displaced = new HashMap<>();
    private boolean registered;

    StaffCommandRegistrar(ChatalystPlugin plugin, StaffCommand handler) {
        this.plugin = plugin;
        this.handler = handler;
    }

    private boolean priority() {
        return plugin.getConfig().getBoolean("Staff_Moderation.Command_Priority", true);
    }

    synchronized void register() {
        if (registered) return;
        CommandMap map = plugin.getServer().getCommandMap();
        boolean claim = priority();
        for (StaffCommandCatalog.Spec spec : StaffCommandCatalog.dynamicCommands()) {
            Command command = new DynamicStaffCommand(spec);
            if (claim) {
                claim(map, spec.name(), command);
                for (String alias : spec.aliases()) claim(map, alias, command);
            } else {
                // Sin prioridad: la etiqueta plana solo se toma si esta libre; si otro
                // plugin ya la tiene queda el respaldo con namespace (/chatalyst:ban).
                map.register("chatalyst", command);
            }
        }
        registered = true;
        syncCommandTree();
    }

    synchronized void unregister() {
        if (!registered) return;
        CommandMap map = plugin.getServer().getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        // Same trap as RuntimeCommandRegistrar: the map view's iterator refuses removal on
        // current Paper, so collect the keys and remove them one by one.
        List<String> ours = new java.util.ArrayList<>();
        for (var entry : new java.util.LinkedHashSet<>(known.entrySet())) {
            if (entry.getValue() instanceof DynamicStaffCommand) ours.add(entry.getKey());
        }
        for (String key : ours) {
            try {
                known.remove(key);
            } catch (UnsupportedOperationException immutable) {
                plugin.getLogger().warning("Could not unregister /" + key
                        + ": this server does not allow removing commands at runtime.");
            }
        }
        // Los comandos que desplazamos vuelven a su dueno original.
        displaced.forEach(known::put);
        displaced.clear();
        registered = false;
        syncCommandTree();
    }

    /** Reafirma el estado configurado tras un reload: alta, baja o re-toma de etiquetas. */
    synchronized void apply(boolean enabled) {
        if (enabled && !registered) {
            register();
        } else if (!enabled && registered) {
            unregister();
        } else if (enabled && priority()) {
            reclaim();
            syncCommandTree();
        }
    }

    /**
     * Los comandos de plugin.yml de plugins que cargan despues de Chatalyst no pisan una
     * etiqueta ocupada, pero un plugin que escribe el CommandMap directamente si puede.
     * Al terminar de arrancar el servidor se reafirma la propiedad una ultima vez.
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        synchronized (this) {
            if (registered && priority()) {
                reclaim();
                syncCommandTree();
            }
        }
    }

    private void reclaim() {
        CommandMap map = plugin.getServer().getCommandMap();
        for (StaffCommandCatalog.Spec spec : StaffCommandCatalog.dynamicCommands()) {
            Command ours = null;
            for (Command candidate : map.getKnownCommands().values()) {
                if (candidate instanceof DynamicStaffCommand dynamic
                        && dynamic.getName().equals(spec.name())) {
                    ours = candidate;
                    break;
                }
            }
            if (ours == null) continue;
            claim(map, spec.name(), ours);
            for (String alias : spec.aliases()) claim(map, alias, ours);
        }
    }

    private void claim(CommandMap map, String label, Command command) {
        String key = label.toLowerCase(Locale.ROOT);
        Map<String, Command> known = map.getKnownCommands();
        Command previous = known.put(key, command);
        known.put("chatalyst:" + key, command);
        if (previous != null && previous != command
                && !(previous instanceof DynamicStaffCommand)) {
            displaced.putIfAbsent(key, previous);
            plugin.getLogger().info("/" + key + " now runs Chatalyst's staff suite (was "
                    + owner(previous) + "). Disable Staff_Moderation.Command_Priority to"
                    + " keep the original owner.");
        }
    }

    private static String owner(Command command) {
        return command instanceof PluginIdentifiableCommand owned
                ? owned.getPlugin().getName() : command.getClass().getSimpleName();
    }

    /**
     * Un alta o una baja despues del arranque solo llega al CLIENTE cuando se le reenvia el
     * arbol; sin eso, el tab-complete sigue ofreciendo lo de antes. Lo hace
     * {@link dev.chatalyst.command.CommandTreeSync}, una vez por tanda y un tick despues, no
     * una vez por comando en mitad del registro.
     *
     * <p>Comprobado con javap sobre el jar del server: {@code CraftServer.syncCommands()} solo
     * recorre los jugadores conectados llamando a {@code sendCommands}. No reconstruye nada,
     * asi que no hay que seguirlo de un {@code updateCommands} que repita el mismo envio.
     */
    private void syncCommandTree() {
        dev.chatalyst.command.CommandTreeSync.request(plugin);
    }

    private final class DynamicStaffCommand extends Command implements PluginIdentifiableCommand {

        private DynamicStaffCommand(StaffCommandCatalog.Spec spec) {
            super(spec.name(), spec.description(), spec.usage(), spec.aliases());
            setPermission(spec.permission());
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label,
                               @NotNull String[] args) {
            return handler.onCommand(sender, this, label, args);
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
            List<String> out = handler.onTabComplete(sender, this, alias, args);
            return out == null ? List.of() : out;
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
