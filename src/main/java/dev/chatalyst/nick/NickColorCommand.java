package dev.chatalyst.nick;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * /nickcolor — abre la GUI para colorear tu apodo.
 *
 * <p>{@code /nickcolor <jugador> <color|reset>} lo hace sobre OTRO jugador, con
 * {@code chatalyst.nick.color.others}. Mismo patron que /chatcolor y /nick: el staff
 * gestiona la identidad visual de alguien sin tener que suplantarlo.
 */
public final class NickColorCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final NickManager nicks;

    public NickColorCommand(JavaPlugin plugin, NickManager nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // Sobre OTRO jugador: funciona tambien desde consola, que es lo util para el staff.
        if (args.length >= 2 && sender.hasPermission("chatalyst.nick.color.others")) {
            return other(sender, args);
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        // Colorear el nick es un permiso de rango (el mismo que permite color al escribir /nick).
        if (!p.hasPermission("chatalyst.nick.color")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }
        if (anonymous(p)) {
            p.sendMessage(Lang.get(p, "nick.anonymous-self"));
            return true;
        }
        Menu.open(p, new NickColorHub(plugin, nicks));
        return true;
    }

    /** /nickcolor &lt;jugador&gt; &lt;color|reset&gt; — el staff colorea el apodo de otro. */
    private boolean other(CommandSender sender, String[] args) {
        Player target = NickManager.online(args[0]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[0]));
            return true;
        }
        // Un anonimo no: su apodo ES su disfraz, y recolorearlo lo delataria.
        var anon = dev.chatalyst.ChatalystPlugin.get() == null ? null
                : dev.chatalyst.ChatalystPlugin.get().getAnonymousManager();
        if (anon != null && anon.isAnonymous(target.getUniqueId())) {
            sender.sendMessage(Lang.get(sender, "nick.anonymous-other"));
            return true;
        }
        String valor = args[1].toLowerCase(java.util.Locale.ROOT);
        String plano = dev.chatalyst.util.MM.strip(
                nicks.getNick(target.getUniqueId()) != null
                        ? nicks.getNick(target.getUniqueId()) : target.getName());
        boolean ok;
        String etiqueta;
        if (valor.equals("reset") || valor.equals("off")) {
            // Sin color y con el nombre real: se quita el apodo del todo en vez de dejar
            // uno "invisible" que solo envuelve su propio nombre.
            ok = plano.equalsIgnoreCase(target.getName())
                    ? nicks.removeNick(target.getUniqueId())
                    : nicks.setNick(target.getUniqueId(), dev.chatalyst.util.MM.escape(plano));
            etiqueta = null;
        } else {
            var option = dev.chatalyst.gui.ChatColorPalette.find(plugin, valor);
            if (option == null) {
                sender.sendMessage(Lang.get(sender, "color.unknown"));
                return true;
            }
            ok = nicks.setNick(target.getUniqueId(),
                    dev.chatalyst.gui.TextStyleOption.decorate(option.tag(),
                            dev.chatalyst.gui.TextStyleOption.detect(
                                    nicks.getNick(target.getUniqueId())), plano));
            etiqueta = option.displayLabel(sender);
        }
        if (!ok) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        sender.sendMessage(etiqueta == null
                ? Lang.get(sender, "nickcolor.reset-other", "player", target.getName())
                : Lang.get(sender, "nickcolor.set-other",
                        "player", target.getName(), "color", etiqueta));
        return true;
    }

    private static boolean anonymous(Player player) {
        var current = dev.chatalyst.ChatalystPlugin.get();
        return current != null && current.getAnonymousManager() != null
                && current.getAnonymousManager().isAnonymous(player.getUniqueId());
    }
}
