package dev.chatalyst.announce;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.PlayerText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /me <accion>       -> accion en tercera persona.
 * /say <mensaje>     -> mensaje del servidor (staff / consola).
 * /broadcast <msg>   -> anuncio manual con el prefijo del announcer.
 */
public final class AnnounceCommands implements CommandExecutor, org.bukkit.command.TabCompleter {

    private final JavaPlugin plugin;

    public AnnounceCommands(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        // Solo /broadcast sugiere el display como primera palabra.
        if (command.getName().equalsIgnoreCase("broadcast") && args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("chat", "title", "actionbar", "bossbar", "toast")
                    .filter(d -> d.startsWith(pre)
                            && (sender.hasPermission("chatalyst.command.broadcast")
                                || sender.hasPermission("chatalyst.command.broadcast." + d)))
                    .toList();
        }
        return java.util.List.of();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 0) {
            sender.sendMessage(Lang.get(sender, "announce.usage", "label", label));
            return true;
        }
        String text = String.join(" ", args);

        switch (name) {
            case "me" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return true;
                }
                if (!p.hasPermission("chatalyst.command.me")) {
                    p.sendMessage(Lang.get(p, "common.no-permission"));
                    return true;
                }
                // /me es chat publico por otra puerta: pasa por el mismo chatlock y modo lento
                // que el chat. Antes, con el chat bloqueado, cualquiera seguia hablando asi.
                var chatalyst = dev.chatalyst.ChatalystPlugin.get();
                var chatMod = chatalyst == null ? null : chatalyst.getChatModeration();
                if (chatMod != null) {
                    if (chatMod.isLocked() && !p.isOp()
                            && !p.hasPermission(dev.chatalyst.moderation.ChatModeration.BYPASS)) {
                        p.sendMessage(Lang.get(p, "chatmod.chat-blocked"));
                        return true;
                    }
                    long remaining = chatMod.slowmodeRemaining(p);
                    if (remaining > 0) {
                        p.sendMessage(Lang.get(p, "chatmod.slowmode-wait", "seconds", remaining));
                        return true;
                    }
                    chatMod.markSpoke(p);
                }
                String fmt = plugin.getConfig().getString("Me.Format", "<#C0B3D9>* <#FBF2FF>{player_name} <#C0B3D9>{message}");
                String tpl = Placeholders.apply(p, fmt).replace("{message}", "<chatalyst_msg>");
                Component c = MM.full(tpl, Placeholder.component("chatalyst_msg", PlayerText.styled(p, text)));
                // Shadow mute: como en el chat, el muteado ve su /me y nadie mas. El mute normal
                // ya lo corta antes (Mute.Blocked_Commands); el soft no se delata.
                var mutes = chatalyst == null ? null : chatalyst.getMuteManager();
                var mute = mutes == null ? null : mutes.getMute(p.getUniqueId());
                if (mute != null && mute.soft) {
                    p.sendMessage(c);
                    return true;
                }
                // Y respeta /ignore igual que el chat: sin esto, a quien te tiene ignorado
                // se le seguia hablando con solo escribir /me delante.
                var pms = chatalyst == null ? null : chatalyst.getPrivateMessages();
                boolean hideIgnored = pms != null
                        && plugin.getConfig().getBoolean("Chat.Ignore.Hide_In_Chat", true);
                java.util.UUID senderId = p.getUniqueId();
                FoliaTasks.onlinePlayers(plugin,
                        viewer -> !hideIgnored || viewer.getUniqueId().equals(senderId)
                                || !pms.isIgnoring(viewer.getUniqueId(), senderId),
                        viewer -> viewer.sendMessage(c));
                FoliaTasks.global(plugin,
                        () -> plugin.getServer().getConsoleSender().sendMessage(c));
            }
            case "say" -> {
                if (!sender.hasPermission("chatalyst.command.say")) {
                    sender.sendMessage(Lang.get(sender, "common.no-permission"));
                    return true;
                }
                // El staff/consola confia: MiniMessage completo + codigos legacy (&6).
                String styled = dev.chatalyst.util.Legacy.toMini(text);
                FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(MM.full(
                        sayFormat(viewer).replace("{message}", styled))));
                FoliaTasks.global(plugin, () -> {
                    CommandSender console = plugin.getServer().getConsoleSender();
                    console.sendMessage(MM.full(sayFormat(console).replace("{message}", styled)));
                });
            }
            case "broadcast" -> {
                // Optional first word picks the display: chat (default), title, actionbar,
                // bossbar or toast.
                String type = "chat";
                String body = text;
                String first = args[0].toLowerCase(Locale.ROOT);
                if (first.equals("chat") || first.equals("title")
                        || first.equals("actionbar") || first.equals("bossbar")
                        || first.equals("toast")) {
                    type = first;
                    body = args.length > 1
                            ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
                }
                if (!sender.hasPermission("chatalyst.command.broadcast")
                        && !sender.hasPermission("chatalyst.command.broadcast." + type)) {
                    sender.sendMessage(Lang.get(sender, "common.no-permission"));
                    return true;
                }
                if (body.isEmpty()) {
                    sender.sendMessage(Lang.get(sender, "announce.usage", "label", label));
                    return true;
                }
                broadcastAs(type, MM.full(dev.chatalyst.util.Legacy.toMini(body)));
            }
            default -> {
            }
        }
        return true;
    }

    private String sayFormat(CommandSender viewer) {
        String configured = dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig(), "Say.Format", viewer, "");
        return configured.isBlank() ? Lang.raw(viewer, "announce.say-format") : configured;
    }

    /** Broadcasts a component to every player in the chosen display. */
    private void broadcastAs(String type, Component msg) {
        // La lista global se captura en el scheduler global; despues cada operacion de
        // jugador vuelve a su entity scheduler. Tambien invalida frames de /announce
        // anteriores para que un broadcast estatico no sea sobrescrito 100 ms despues.
        FoliaTasks.global(plugin, () -> {
            java.util.List<? extends Player> targets = java.util.List.copyOf(
                    plugin.getServer().getOnlinePlayers());
            AnnounceAnimator.cancel(plugin, targets, type);
            switch (type) {
                case "title" -> {
                    var title = net.kyori.adventure.title.Title.title(msg, Component.empty());
                    targets.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.showTitle(title)));
                }
                case "actionbar" ->
                        targets.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.sendActionBar(msg)));
                case "bossbar" -> dev.chatalyst.util.Displays.bossbarTimed(plugin, targets, msg,
                        net.kyori.adventure.bossbar.BossBar.Color.PURPLE);
                case "toast" -> {
                    // El toast es texto plano (JSON del advancement): sin tags de color.
                    String plainMessage = net.kyori.adventure.text.serializer.plain
                            .PlainTextComponentSerializer.plainText().serialize(msg);
                    java.util.List<Player> english = targets.stream()
                            .filter(player -> !Lang.isSpanish(player)).map(Player.class::cast).toList();
                    java.util.List<Player> spanish = targets.stream()
                            .filter(Lang::isSpanish).map(Player.class::cast).toList();
                    if (!english.isEmpty()) {
                        dev.chatalyst.util.ToastUtil.show(plugin, english,
                                toastTitle(english.getFirst()), plainMessage);
                    }
                    if (!spanish.isEmpty()) {
                        dev.chatalyst.util.ToastUtil.show(plugin, spanish,
                                toastTitle(spanish.getFirst()), plainMessage);
                    }
                }
                default -> {
                    Component line = MM.full(dev.chatalyst.config.ConfigFiles.string(
                            plugin.getConfig(), "Announcer.Prefix", ""))
                            .append(msg);
                    targets.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.sendMessage(line)));
                    plugin.getServer().getConsoleSender().sendMessage(line);
                }
            }
        });
    }

    private String toastTitle(CommandSender viewer) {
        String configured = dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig(), "Announcer.Toast_Title", viewer, "");
        return MM.strip(configured.isBlank()
                ? Lang.raw(viewer, "announce.toast-title") : configured);
    }
}
