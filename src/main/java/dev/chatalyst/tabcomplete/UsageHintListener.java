package dev.chatalyst.tabcomplete;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guia de argumentos en el TAB para los comandos de Chatalyst.
 *
 * <p>Cuando el autocompletado propio de un comando no ofrece nada en la posicion actual,
 * se muestra lo que va ahi segun su {@code usage:} ({@code /cht sendformat <jugador>
 * <formato> <texto>}), asi quien no conoce el comando ve como se forma. Y si alguien envia
 * el hueco tal cual ({@code <jugador>}), se le avisa en vez de ejecutar el comando con ese
 * texto literal.
 */
public final class UsageHintListener implements Listener {

    private final JavaPlugin plugin;
    /** usage -> guia ya parseada; el usage cambia solo al recargar el plugin entero. */
    private final Map<String, UsageGuide> cache = new ConcurrentHashMap<>();

    public UsageHintListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!event.getCompletions().isEmpty()) return;
        String buffer = event.getBuffer();
        if (buffer == null || !buffer.startsWith("/")) return;
        int space = buffer.indexOf(' ');
        if (space < 0) return; // todavia escribiendo el nombre del comando
        PluginCommand command = ownCommand(buffer.substring(1, space));
        if (command == null || !command.testPermissionSilent(event.getSender())) return;
        String[] args = buffer.substring(space + 1).split(" ", -1);
        List<String> hints = guide(command).suggest(args, spanish(event.getSender()));
        if (!hints.isEmpty()) event.setCompletions(hints);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        int space = message.indexOf(' ');
        if (space < 0 || !message.startsWith("/")) return;
        PluginCommand command = ownCommand(message.substring(1, space));
        if (command == null) return;
        UsageGuide guide = guide(command);
        for (String arg : message.substring(space + 1).split(" ")) {
            if (guide.isSlotPlaceholder(arg)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Lang.get(event.getPlayer(),
                        "plugin.usage-fill-placeholder",
                        "arg", dev.chatalyst.util.MM.escape(arg),
                        "usage", dev.chatalyst.util.MM.escape(
                                UsageGuide.translate(command.getUsage(),
                                        spanish(event.getPlayer())))));
                return;
            }
        }
    }

    private UsageGuide guide(PluginCommand command) {
        String usage = command.getUsage();
        return cache.computeIfAbsent(usage == null ? "" : usage, UsageGuide::parse);
    }

    /** El comando de Chatalyst con ese nombre o alias ({@code cht}, {@code chatalyst:cht}). */
    private PluginCommand ownCommand(String label) {
        String name = label.toLowerCase(Locale.ROOT);
        Command command = plugin.getServer().getCommandMap().getCommand(name);
        if (command instanceof PluginCommand pc && pc.getPlugin() == plugin) return pc;
        return null;
    }

    private static boolean spanish(CommandSender sender) {
        return Lang.isSpanish(sender);
    }
}
