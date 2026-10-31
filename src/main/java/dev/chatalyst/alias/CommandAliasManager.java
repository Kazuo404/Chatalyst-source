package dev.chatalyst.alias;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Comandos personalizados definidos en config (Command_Aliases): al usarlos, envian unas
 * lineas de texto (ej. /discord muestra el enlace) o ejecutan comandos de consola. Se
 * registran dinamicamente en el CommandMap del servidor.
 *
 * <p>{@link #apply()} se puede llamar tantas veces como haga falta: primero retira lo que
 * registro la vez anterior. Antes solo se registraba una vez al arrancar, asi que editar un
 * alias y hacer /chatalyst reload no cambiaba nada y habia que reiniciar el servidor sin que
 * nada lo dijera.
 */
public final class CommandAliasManager {

    private final JavaPlugin plugin;

    public CommandAliasManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Deja registrados exactamente los alias que hay ahora mismo en la configuracion. */
    public synchronized void apply() {
        unregister();
        register();
        syncCommandTree();
    }

    /**
     * Retira solo los alias de ESTE gestor. No se usa {@code entrySet().removeIf}: en Paper
     * el mapa de comandos conocidos es una vista cuyo iterador rechaza el borrado, y eso
     * lanzaba UnsupportedOperationException("remove") en mitad del reload.
     */
    private void unregister() {
        var known = plugin.getServer().getCommandMap().getKnownCommands();
        List<String> ours = new ArrayList<>();
        for (var entry : new LinkedHashSet<>(known.entrySet())) {
            if (entry.getValue() instanceof AliasCommand alias && alias.owner() == this) {
                ours.add(entry.getKey());
            }
        }
        for (String key : ours) {
            try {
                known.remove(key);
            } catch (UnsupportedOperationException immutable) {
                plugin.getLogger().warning("No se pudo retirar el alias /" + key
                        + ": este servidor no permite quitar comandos en caliente.");
            }
        }
    }

    /** Un reenvio por tanda, no uno por alias: ver {@link dev.chatalyst.command.CommandTreeSync}. */
    private void syncCommandTree() {
        dev.chatalyst.command.CommandTreeSync.request(plugin);
    }

    private void register() {
        CommandMap map = plugin.getServer().getCommandMap();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("Command_Aliases");
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(name);
            if (sec == null) continue;
            List<String> aliases = sec.getStringList("Aliases");
            List<String> messages = sec.getStringList("Messages");
            // Optional Spanish variant: Messages_es. Falls back to Messages if missing.
            List<String> messagesEs = sec.getStringList("Messages_es");
            List<String> commands = sec.getStringList("Commands");
            String permission = sec.getString("Permission", "");

            Command cmd = new AliasCommand(name.toLowerCase(java.util.Locale.ROOT), this) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                    if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                        sender.sendMessage(dev.chatalyst.lang.Lang.get(sender, "common.no-permission"));
                        return true;
                    }
                    Player p = sender instanceof Player pl ? pl : null;
                    List<String> lines = (!messagesEs.isEmpty() && dev.chatalyst.lang.Lang.isSpanish(sender))
                            ? messagesEs : messages;
                    for (String line : lines) {
                        sender.sendMessage(MM.full(p != null ? Placeholders.apply(p, line) : line));
                    }
                    for (String c : commands) {
                        String resolved = p != null ? Placeholders.apply(p, c) : c;
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), resolved);
                    }
                    return true;
                }
            };
            cmd.setAliases(aliases);
            cmd.setDescription("Alias de Chatalyst");
            map.register("chatalyst", cmd);
        }
    }

    /** Marca de propiedad: permite retirar los alias propios sin tocar los de otro plugin. */
    private abstract static class AliasCommand extends Command {
        private final CommandAliasManager owner;

        AliasCommand(String name, CommandAliasManager owner) {
            super(name);
            this.owner = owner;
        }

        CommandAliasManager owner() {
            return owner;
        }
    }
}
