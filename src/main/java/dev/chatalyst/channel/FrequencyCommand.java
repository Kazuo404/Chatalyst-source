package dev.chatalyst.channel;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /frequency (alias /freq, /frecuencia): sintoniza tu frecuencia del chat local por radio.
 * Sin argumentos abre la GUI; con {@code NN.NN} (10.00 a 99.99) sintoniza directo. Cambiar de
 * frecuencia decide con QUIEN te oyes en el canal local, sin importar la distancia.
 */
public final class FrequencyCommand implements CommandExecutor, TabCompleter {

    private final FrequencyManager frequencies;

    public FrequencyCommand(FrequencyManager frequencies) {
        this.frequencies = frequencies;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "channel.players-only"));
            return true;
        }
        if (args.length == 0) {
            Menu.open(p, new FrequencyMenu(frequencies));
            return true;
        }
        // /frequency off | apagar: desactiva la radio (dejas de oir y hablar por frecuencia).
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        if (first.equals("off") || first.equals("apagar") || first.equals("desactivar")) {
            if (!frequencies.set(p.getUniqueId(), FrequencyManager.OFF)) {
                storageError(p);
                return true;
            }
            p.sendMessage(Lang.get(p, "frequency.disabled"));
            return true;
        }
        Integer cents = FrequencyManager.parse(args[0]);
        if (cents == null) {
            p.sendMessage(Lang.get(p, "frequency.invalid"));
            return true;
        }
        if (!frequencies.set(p.getUniqueId(), cents)) {
            storageError(p);
            return true;
        }
        // No cambia tu canal: te quedas donde estabas y hablas por radio con /f <mensaje>.
        p.sendMessage(Lang.get(p, "frequency.tuned", "freq", FrequencyManager.format(cents)));
        return true;
    }

    private static void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // Sugerencias de ejemplo del formato NN.NN.
        if (args.length == 1) {
            return List.of("10.00", "20.20", "42.50", "99.99");
        }
        return List.of();
    }
}
