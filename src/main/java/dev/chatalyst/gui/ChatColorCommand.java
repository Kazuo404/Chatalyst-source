package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /chatcolor          Abre el hub de color de chat (categorias, gradiente propio, estilos).
 * /chatcolor reset    Quita el color.
 */
public final class ChatColorCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final ChatColorManager colors;

    public ChatColorCommand(JavaPlugin plugin, ChatColorManager colors) {
        this.plugin = plugin;
        this.colors = colors;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("chatalyst.command.chatcolor")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        // Manage someone else's color: /chatcolor <player> <color|reset>
        if (args.length >= 2 && sender.hasPermission("chatalyst.chat.color.others")) {
            Player target = dev.chatalyst.nick.NickManager.online(args[0]);
            if (target == null) {
                sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[0]));
                return true;
            }
            String val = args[1].toLowerCase(Locale.ROOT);
            if (val.equals("reset") || val.equals("off")) {
                // "reset" debe dejar el chat como recien llegado: color Y estilos fuera,
                // igual que el boton Restablecer del menu.
                if (!colors.clearColor(target.getUniqueId())
                        || !colors.clearStyles(target.getUniqueId())) {
                    storageError(sender);
                    return true;
                }
                sender.sendMessage(Lang.get(sender, "color.reset-other", "player", target.getName()));
            } else {
                ChatColorPalette.Option option = ChatColorPalette.find(plugin, val);
                if (option == null) {
                    sender.sendMessage(Lang.get(sender, "color.unknown"));
                    return true;
                }
                if (!colors.setColor(target.getUniqueId(), option.tag())) {
                    storageError(sender);
                    return true;
                }
                sender.sendMessage(Lang.get(sender, "color.set-other",
                        "player", target.getName(), "color", option.displayLabel(sender)));
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("off"))) {
            if (!colors.clearColor(p.getUniqueId())
                    || !colors.clearStyles(p.getUniqueId())) {
                storageError(p);
                return true;
            }
            p.sendMessage(Lang.get(p, "color.reset"));
            return true;
        }
        // Set directo por id de la paleta.
        if (args.length >= 1) {
            String id = args[0].toLowerCase(Locale.ROOT);
            ChatColorPalette.Option option = ChatColorPalette.find(plugin, id);
            if (option == null) {
                p.sendMessage(Lang.get(p, "color.unknown"));
                return true;
            }
            if (option.canUse(p)) {
                if (!colors.setColor(p.getUniqueId(), option.tag())) {
                    storageError(p);
                    return true;
                }
                p.sendMessage(Lang.get(p, "color.selected", "color", option.displayLabel(p)));
                return true;
            }
            p.sendMessage(Lang.get(p, "color.no-permission"));
            return true;
        }
        // Mismo hub que /nickcolor: categorias, gradiente propio y estilos.
        Menu.open(p, new ChatColorHub(plugin, colors));
        return true;
    }

    private void storageError(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
    }
}
