package dev.chatalyst.channel;

import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /channel                          -> lista de canales y en cual escribes.
 * /channel <nombre>                 -> cambia tu canal de escritura.
 * /channel mute <nombre>            -> deja de ver / vuelve a ver un canal.
 * /channel send <canal> <msg>       -> (staff) envia al canal sin unirse.
 * /channel sendas <jugador> <canal> <msg> -> (staff/consola) envia como otro jugador.
 */
public final class ChannelCommand implements CommandExecutor, TabCompleter {

    private final ChannelManager channels;
    private final ChannelHistory history;
    private final FrequencyManager frequencies;

    public ChannelCommand(ChannelManager channels, ChannelHistory history, FrequencyManager frequencies) {
        this.channels = channels;
        this.history = history;
        this.frequencies = frequencies;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!channels.isEnabled()) {
            sender.sendMessage(Lang.get(sender, "channel.disabled"));
            return true;
        }

        // sendas admite consola (envia "como" un jugador conectado); el resto es de jugadores.
        if (args.length >= 1 && args[0].equalsIgnoreCase("sendas")) {
            handleSendAs(sender, args);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "channel.players-only"));
            return true;
        }

        if (args.length == 0) {
            // Sin argumentos: GUI de canales. La lista de texto sigue en /channel list.
            dev.chatalyst.gui.Menu.open(p, new ChannelMenu(channels, history, frequencies));
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            sendList(p);
            return true;
        }

        if (args[0].equalsIgnoreCase("send")) {
            handleSend(p, args);
            return true;
        }

        // /channel history [nombre]: reproduce los ultimos mensajes del canal, marcados
        // como ya enviados. Sin nombre usa el canal actual.
        if (args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("historial")) {
            Channel target = args.length >= 2 ? channels.byAlias(args[1]) : channels.getWriteChannel(p);
            if (target == null || !target.canUse(p)) {
                p.sendMessage(Lang.get(p, "channel.unknown", "name", args.length >= 2 ? args[1] : "-"));
                return true;
            }
            showHistory(p, target, Integer.MAX_VALUE);
            return true;
        }

        if (args[0].equalsIgnoreCase("mute") && args.length >= 2) {
            Channel ch = channels.byAlias(args[1]);
            if (ch == null || !ch.canUse(p)) {
                p.sendMessage(Lang.get(p, "channel.unknown", "name", args[1]));
                return true;
            }
            ChannelManager.StateChange changed = channels.toggleMutedResult(p, ch);
            if (!changed.persisted()) {
                storageError(p);
                return true;
            }
            p.sendMessage(Lang.get(p, changed.state() ? "channel.muted" : "channel.unmuted",
                    "name", ch.name));
            return true;
        }

        // Suscripcion de lectura: seguir recibiendo un canal sin escribirlo (tambien con
        // Channels.Exclusive activado). /channel listen <canal> alterna.
        if ((args[0].equalsIgnoreCase("listen") || args[0].equalsIgnoreCase("escuchar")) && args.length >= 2) {
            Channel ch = channels.byAlias(args[1]);
            if (ch == null || !ch.canUse(p)) {
                p.sendMessage(Lang.get(p, "channel.unknown", "name", args[1]));
                return true;
            }
            ChannelManager.StateChange changed = channels.toggleListeningResult(p, ch);
            if (!changed.persisted()) {
                storageError(p);
                return true;
            }
            boolean nowOn = changed.state();
            p.sendMessage(Lang.get(p, nowOn ? "channel.listen-on" : "channel.listen-off", "name", ch.name));
            if (nowOn && history != null && history.isEnabled() && history.showOnSwitch() > 0) {
                showHistory(p, ch, history.showOnSwitch());
            }
            return true;
        }

        Channel ch = channels.byAlias(args[0]);
        if (ch == null) {
            p.sendMessage(Lang.get(p, "channel.unknown", "name", args[0]));
            return true;
        }
        if (!ch.canUse(p)) {
            p.sendMessage(Lang.get(p, "channel.no-access", "name", ch.name));
            return true;
        }
        // API publica: otros plugins pueden observar o cancelar el cambio de canal.
        Channel current = channels.getWriteChannel(p);
        var apiEvent = new dev.chatalyst.api.ChannelSwitchEvent(p,
                current != null ? current.name : "", ch.name);
        p.getServer().getPluginManager().callEvent(apiEvent);
        if (apiEvent.isCancelled()) return true;

        if (!channels.setWriteChannel(p, ch)) {
            storageError(p);
            return true;
        }
        p.sendMessage(Lang.get(p, "channel.changed", "name", ch.name));
        // Canal de radio sin frecuencia sintonizada: avisarias al vacio; guiar a sintonizar.
        if (ch.type == ChannelType.FREQUENCY && !FrequencyManager.active(p.getUniqueId())) {
            p.sendMessage(Lang.get(p, "frequency.channel-off"));
        }
        // Ponerse al dia: reproduce lo ultimo que se dijo en el canal, marcado como pasado.
        if (history != null && history.isEnabled() && history.showOnSwitch() > 0) {
            showHistory(p, ch, history.showOnSwitch());
        }
        return true;
    }

    private void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }

    /** Reproduce el historial reciente de un canal, claramente marcado como ya enviado. */
    private void showHistory(Player p, Channel ch, int limit) {
        if (history == null || !history.replayTo(p, ch.name, limit)) {
            p.sendMessage(Lang.get(p, "channel.history-empty", "name", ch.name));
        }
    }

    /** /channel send <canal> <mensaje>: envia al canal con tu identidad, sin cambiar de canal. */
    private void handleSend(Player p, String[] args) {
        if (!p.hasPermission("chatalyst.channel.send")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return;
        }
        if (args.length < 3) {
            p.sendMessage(Lang.get(p, "channel.send-usage"));
            return;
        }
        Channel ch = channels.byAlias(args[1]);
        if (ch == null || !ch.canUse(p)) {
            p.sendMessage(Lang.get(p, "channel.unknown", "name", args[1]));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        deliver(p, p, ch, text);
    }

    /** /channel sendas <jugador> <canal> <mensaje>: envia suplantando a un jugador conectado. */
    private void handleSendAs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chatalyst.channel.sendas")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Lang.get(sender, "channel.sendas-usage"));
            return;
        }
        Player as = dev.chatalyst.nick.NickManager.online(args[1]);
        if (as == null) {
            sender.sendMessage(Lang.get(sender, "common.player-offline"));
            return;
        }
        Channel ch = channels.byAlias(args[2]);
        if (ch == null) {
            sender.sendMessage(Lang.get(sender, "channel.unknown", "name", args[2]));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        deliver(sender, as, ch, text);
    }

    /**
     * Renderiza el mensaje con el formato del canal (como si lo hubiera escrito {@code as})
     * y lo entrega a la audiencia del canal, la consola y el proxy si el canal lo reenvia.
     * Nota: envio administrativo — no pasa por reglas/anti-spam (el emisor real es staff
     * con permiso explicito) y siempre queda auditado.
     */
    private void deliver(CommandSender origin, Player as, Channel ch, String rawText) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        String text = dev.chatalyst.util.Sanitize.stripInvisible(rawText).trim();
        if (text.isEmpty()) return;

        var fmt = plugin.getConfigManager().getFormat(ch.format);
        var msg = net.kyori.adventure.text.Component.text(text);
        var prepared = dev.chatalyst.chat.ChatRenderer.prepare(fmt, as, msg,
                java.util.Map.of("channel", ch.name, "message_uuid", ""));

        // Despacho por entidad (Folia): cada online.hasPermission/sendMessage corre en su
        // propio hilo. prepared es inmutable y assemble no tiene estado.
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                online -> channels.canSee(online, as, ch),
                online -> online.sendMessage(dev.chatalyst.chat.ChatRenderer.assemble(prepared, online)));
        plugin.getServer().getConsoleSender().sendMessage(
                dev.chatalyst.chat.ChatRenderer.assemble(prepared, null));
        if (ch.proxy && plugin.getProxyManager() != null && plugin.getProxyManager().isEnabled()) {
            plugin.getProxyManager().forward(ch.name,
                    dev.chatalyst.chat.ChatRenderer.assemble(prepared, null));
        }
        if (plugin.getStaffSecurityListener() != null) {
            String detail = origin == as ? ch.name + ": " + text
                    : "as " + as.getName() + " -> " + ch.name + ": " + text;
            plugin.getStaffSecurityListener().audit(origin, origin == as ? "channel-send" : "channel-sendas", detail);
        }
        if (history != null && ch.type == ChannelType.GLOBAL) {
            history.record(ch.name, "", as.getUniqueId(),
                    dev.chatalyst.nick.NickManager.suggestedName(as), text);
        }
        if (origin != as) {
            origin.sendMessage(Lang.get(origin, "channel.sendas-done", "player", as.getName(), "name", ch.name));
        }
    }

    private void sendList(Player p) {
        Channel current = channels.getWriteChannel(p);
        p.sendMessage(Lang.get(p, "channel.writing-in", "name", current != null ? current.name : "-"));
        // Cada canal es un chip clicable: click = cambiarse; el color y el hover explican
        // su estado (escribiendo, escuchando o muteado) y muestran los alias.
        var line = net.kyori.adventure.text.Component.text();
        line.append(Lang.get(p, "channel.list", "list", ""));
        for (Channel ch : channels.all()) {
            if (!ch.canUse(p)) continue;
            boolean active = current != null && current.name.equals(ch.name);
            boolean muted = channels.isMuted(p, ch);
            boolean listening = channels.isListening(p, ch);
            String chip = active ? " <#B56CFF><bold>" + ch.name + "</bold>"
                    : muted ? " <#7B68A0>" + ch.name
                    : listening ? " <#3DFFA8>" + ch.name
                    : " <#C0B3D9>" + ch.name;
            String stateKey = active ? "channel.state-active"
                    : muted ? "channel.state-muted"
                    : listening ? "channel.state-listening"
                    : "channel.state-available";
            line.append(dev.chatalyst.util.MM.full(chip)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            dev.chatalyst.util.MM.full(Lang.raw(p, stateKey)
                                    + "\n<#7B68A0>" + String.join(", ", ch.aliases)
                                    + "\n" + Lang.raw(p, "channel.state-click"))))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            "/channel " + ch.name)));
        }
        p.sendMessage(line.build());
        p.sendMessage(Lang.get(p, "channel.list-hint"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p)) return out;
        if (args.length == 1) {
            String pre = args[0].toLowerCase();
            if ("list".startsWith(pre)) out.add("list");
            if ("mute".startsWith(pre)) out.add("mute");
            if ("listen".startsWith(pre)) out.add("listen");
            if ("history".startsWith(pre)) out.add("history");
            if (p.hasPermission("chatalyst.channel.send") && "send".startsWith(pre)) out.add("send");
            if (p.hasPermission("chatalyst.channel.sendas") && "sendas".startsWith(pre)) out.add("sendas");
            for (Channel ch : channels.all()) {
                if (ch.canUse(p) && ch.name.startsWith(pre)) out.add(ch.name);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("send")
                || args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("listen"))) {
            String pre = args[1].toLowerCase();
            for (Channel ch : channels.all()) {
                if (ch.canUse(p) && ch.name.startsWith(pre)) out.add(ch.name);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("sendas")) {
            String pre = args[1].toLowerCase();
            for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(online)
                        && !dev.chatalyst.staff.StaffAccess.canSeeVanished(p)) continue;
                // Un anonimo es incognito: su nombre real no se sugiere.
                if (dev.chatalyst.hook.Placeholders.isAnonymous(online.getUniqueId())) continue;
                if (online.getName().toLowerCase().startsWith(pre)) out.add(online.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sendas")) {
            String pre = args[2].toLowerCase();
            for (Channel ch : channels.all()) {
                if (ch.name.startsWith(pre)) out.add(ch.name);
            }
        }
        return out;
    }
}
