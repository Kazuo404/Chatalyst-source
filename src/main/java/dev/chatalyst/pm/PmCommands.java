package dev.chatalyst.pm;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Un unico handler para /msg, /r (reply), /ignore y /spy; despacha por el nombre del comando.
 */
public final class PmCommands implements CommandExecutor, TabCompleter {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final PrivateMessages pm;

    public PmCommands(org.bukkit.plugin.java.JavaPlugin plugin, PrivateMessages pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("spy")) {
            handleSpy(sender, args);
            return true;
        }
        // Interruptor maestro del dueño: sin Private_Messages.Enabled los MP no existen.
        // /spy queda fuera: tambien cubre comandos, carteles, libros y yunques.
        if (!PrivateMessages.systemEnabled(plugin)) {
            sender.sendMessage(Lang.get(sender, "pm.disabled"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "msg" -> handleMsg(p, args);
            case "reply" -> handleReply(p, args);
            case "ignore" -> handleIgnore(p, args);
            default -> {
            }
        }
        return true;
    }

    private void handleMsg(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(Lang.get(p, "pm.usage-msg"));
            return;
        }
        // Acepta nombre real o apodo (/msg Pepo funciona aunque su cuenta sea otra).
        Player target = dev.chatalyst.nick.NickManager.online(args[0]);
        if (target == null) {
            p.sendMessage(Lang.get(p, "common.player-offline"));
            return;
        }
        if (target.equals(p)) {
            p.sendMessage(Lang.get(p, "pm.self"));
            return;
        }
        if (!PrivateMessages.canReachVanished(p, target)) {
            p.sendMessage(Lang.get(p, "common.player-offline"));
            return;
        }
        if (pm.blockedByMute(p) || silencedByTransition(p)) return;
        pm.send(p, target, join(args, 1));
    }

    private void handleReply(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(Lang.get(p, "pm.usage-reply"));
            return;
        }
        Player target = pm.getReplyTarget(p);
        if (target == null) {
            p.sendMessage(Lang.get(p, "pm.no-reply"));
            return;
        }
        if (!PrivateMessages.canReachVanished(p, target)) {
            p.sendMessage(Lang.get(p, "pm.no-reply"));
            return;
        }
        if (pm.blockedByMute(p) || silencedByTransition(p)) return;
        pm.send(p, target, join(args, 0));
    }

    /**
     * En el hueco de /anonymous tampoco se escriben privados.
     *
     * <p>El chat publico ya se calla ahi, pero un privado sale por otra puerta y llega igual:
     * quien lo recibe acaba de leer que ese jugador se fue, y ahora le escribe alguien con el
     * otro nombre. La audiencia es una sola persona en vez de todo el server, que es la unica
     * diferencia — la correlacion que delata al anonimo es exactamente la misma.
     */
    private boolean silencedByTransition(Player p) {
        var active = dev.chatalyst.ChatalystPlugin.get();
        if (active == null || active.getAnonymousManager() == null
                || !active.getAnonymousManager().departureAnnounced(p.getUniqueId())) {
            return false;
        }
        p.sendMessage(Lang.get(p, "anonymous.transition-silent"));
        return true;
    }

    private void handleIgnore(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(Lang.get(p, "pm.ignore-usage"));
            return;
        }
        OfflinePlayer target = dev.chatalyst.nick.NickManager.resolveOffline(args[0]);
        if (target == null || target.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(Lang.get(p, "pm.ignore-invalid"));
            return;
        }
        boolean nowIgnoring = pm.toggleIgnore(p, target.getUniqueId());
        p.sendMessage(Lang.get(p, nowIgnoring ? "pm.ignore-now" : "pm.ignore-no", "player", args[0]));
    }

    private void handleSpy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return;
        }
        if (!PrivateMessages.canSpyAny(p)) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return;
        }
        if (args.length == 0) {
            Menu.open(p, new SpyMenu(pm));
            return;
        }
        if (args[0].equalsIgnoreCase("toggle")) {
            boolean on = pm.toggleSpy(p);
            p.sendMessage(Lang.get(p, on ? "pm.spy-on" : "pm.spy-off"));
            return;
        }
        // /spy all: vuelve al spy GLOBAL (sin filtro por jugador).
        if (args[0].equalsIgnoreCase("all") || args[0].equalsIgnoreCase("todos")) {
            p.sendMessage(Lang.get(p, pm.clearSpyTargets(p)
                    ? "pm.spy-target-cleared" : "pm.spy-already-global"));
            return;
        }
        // /spy <jugador>: alterna la vigilancia SOLO de ese jugador (se pueden acumular).
        // Se acepta cualquiera que haya jugado aqui: vigilar a alguien desconectado es
        // valido — sus lineas llegaran en cuanto entre.
        org.bukkit.OfflinePlayer target =
                dev.chatalyst.nick.NickManager.resolveOffline(args[0]);
        if (target == null || target.getName() == null
                || !(target.isOnline() || target.hasPlayedBefore())) {
            p.sendMessage(Lang.get(p, "common.unknown-player",
                    "player", dev.chatalyst.util.MM.escape(args[0])));
            return;
        }
        String name = target.getName();
        p.sendMessage(spyTargetMessage(p, pm.toggleSpyTarget(p, name), name));
    }

    /** Respuesta de /spy &lt;jugador&gt;, segun lo que paso con la lista de vigilados. */
    private net.kyori.adventure.text.Component spyTargetMessage(
            Player p, PrivateMessages.TargetChange change, String name) {
        String key = switch (change) {
            case ADDED -> "pm.spy-target-added";
            case REMOVED -> "pm.spy-target-removed";
            case REMOVED_LAST_OFF -> "pm.spy-target-removed-off";
            case REMOVED_LAST_GLOBAL -> "pm.spy-target-removed-global";
        };
        return Lang.get(p, key, "player", dev.chatalyst.util.MM.escape(name),
                "count", pm.spyTargets(p).size());
    }

    private static String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("spy") && args.length == 1) {
            if (!(sender instanceof Player p) || !PrivateMessages.canSpyAny(p)) return out;
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String option : List.of("toggle", "all")) {
                if (option.startsWith(pre)) out.add(option);
            }
            // Jugadores conectados: para vigilar a uno concreto (/spy <jugador>).
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(online)
                        && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) {
                    continue;
                }
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(pre)) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        if ((name.equals("msg") || name.equals("ignore")) && args.length == 1) {
            if (!PrivateMessages.systemEnabled(plugin)) return out;
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(online)) {
                    if (name.equals("msg")) {
                        if (!(sender instanceof Player p) || !PrivateMessages.canReachVanished(p, online)) continue;
                    } else if (!dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) {
                        continue;
                    }
                }
                String shown = dev.chatalyst.nick.NickManager.suggestedName(online);
                if (shown.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(shown);
            }
        }
        return out;
    }
}
