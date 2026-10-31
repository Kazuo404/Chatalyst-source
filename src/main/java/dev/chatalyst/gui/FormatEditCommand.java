package dev.chatalyst.gui;

import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * /formatedit <formato>  Abre el editor grafico de un formato de chat.
 */
public final class FormatEditCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final FormatEditManager editor;

    public FormatEditCommand(JavaPlugin plugin, ConfigManager config, FormatEditManager editor) {
        this.plugin = plugin;
        this.config = config;
        this.editor = editor;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!p.hasPermission("chatalyst.command.editor")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        if (args.length < 1) {
            p.sendMessage(Lang.get(p, "editor.usage", "formats", String.join(", ", listFormats())));
            return true;
        }
        String format = FormatEditManager.normalizeFormatName(args[0]);
        if (format == null || !editor.formatExists(format)) {
            p.sendMessage(Lang.get(p, "editor.unknown-format", "format", format));
            return true;
        }
        Menu.open(p, new FormatEditorMenu(config, editor, format));
        return true;
    }

    private List<String> listFormats() {
        List<String> out = new ArrayList<>();
        File dir = new File(plugin.getDataFolder(), "formats");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("chatalyst.command.editor")) return List.of();
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String f : listFormats()) {
                if (f.startsWith(args[0].toLowerCase())) out.add(f);
            }
            return out;
        }
        return List.of();
    }
}
