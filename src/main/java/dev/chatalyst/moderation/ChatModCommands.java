package dev.chatalyst.moderation;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /clearchat          Limpia el chat de todos.
 * /chatlock           Bloquea el chat global (solo staff puede hablar).
 * /chatunlock         Desbloquea el chat.
 * /slowmode <seg|off> Cooldown global entre mensajes.
 */
public final class ChatModCommands implements CommandExecutor {

    private final JavaPlugin plugin;
    private final ChatModeration mod;

    public ChatModCommands(JavaPlugin plugin, ChatModeration mod) {
        this.plugin = plugin;
        this.mod = mod;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "clearchat" -> {
                if (notAllowed(sender, "chatalyst.command.clearchat")) return true;
                // No hay API para borrar el historial del cliente: empujamos el chat viejo
                // fuera del scrollback con lineas en blanco. El cliente vanilla guarda 100
                // lineas, pero clientes con buffer ampliado necesitan mas -> configurable.
                // Una linea con un espacio (no Component.empty) para que TODAS cuenten.
                int lines = normalizeClearLines(
                        plugin.getConfig().getInt("Chat.Clear_Lines", 150));
                Component blank = Component.text(" ");
                String bypass = "chatalyst.command.clearchat.bypass";
                // Despacho por entidad (Folia): staff con bypass conserva su chat.
                FoliaTasks.onlinePlayers(plugin,
                        p -> !p.hasPermission(bypass),
                        p -> {
                            for (int i = 0; i < lines; i++) p.sendMessage(blank);
                        });
                localizedBroadcast("chatmod.cleared", "player", sender.getName());
            }
            case "chatlock" -> {
                if (notAllowed(sender, "chatalyst.command.chatlock")) return true;
                mod.setLocked(true);
                localizedBroadcast("chatmod.locked");
            }
            case "chatunlock" -> {
                if (notAllowed(sender, "chatalyst.command.chatlock")) return true;
                mod.setLocked(false);
                localizedBroadcast("chatmod.unlocked");
            }
            case "slowmode" -> {
                if (notAllowed(sender, "chatalyst.command.slowmode")) return true;
                if (args.length < 1) {
                    sender.sendMessage(Lang.get(sender, "chatmod.slowmode-current", "seconds", mod.getSlowmode()));
                    return true;
                }
                int seconds;
                if (args[0].equalsIgnoreCase("off")) {
                    seconds = 0;
                } else {
                    try {
                        seconds = Integer.parseInt(args[0]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Lang.get(sender, "common.invalid-number"));
                        return true;
                    }
                }
                mod.setSlowmode(seconds);
                if (seconds > 0) {
                    localizedBroadcast("chatmod.slowmode-on", "seconds", seconds);
                } else {
                    localizedBroadcast("chatmod.slowmode-off");
                }
            }
            default -> {
            }
        }
        return true;
    }

    static int normalizeClearLines(int configured) {
        // Una configuracion accidental de Integer.MAX_VALUE no debe bloquear cada region
        // intentando enviar miles de millones de componentes al cliente.
        return Math.max(1, Math.min(1_000, configured));
    }

    private boolean notAllowed(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        return false;
    }

    private void localizedBroadcast(String key, Object... replacements) {
        FoliaTasks.onlinePlayers(plugin, player -> player.sendMessage(Lang.get(player, key, replacements)));
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(Lang.get(key, replacements)));
    }
}
