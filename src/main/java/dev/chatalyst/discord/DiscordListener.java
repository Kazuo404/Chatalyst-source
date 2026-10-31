package dev.chatalyst.discord;

import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Observes accepted Minecraft chat and hands it to Chatalyst's embedded Discord bot.
 * Priority MONITOR keeps the bridge downstream of mutes, rules, routing and sanitization.
 */
public final class DiscordListener implements Listener {

    private final JavaPlugin plugin;
    private final DiscordBot bot;

    public DiscordListener(JavaPlugin plugin, DiscordBot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    /** Displayed identity, including /nick and the safe alias used by /anonymous. */
    private static String shown(Player player) {
        return dev.chatalyst.nick.NickManager.plainName(player);
    }

    // Without ignoreCancelled: live translation deliberately cancels the original event after
    // delivering localized copies. Discord receives the original accepted text exactly once.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()
                && !dev.chatalyst.chat.ChatListener.deliveredByTranslation(event)) {
            return;
        }

        var active = dev.chatalyst.ChatalystPlugin.get();
        if (active != null && active.getMuteManager() != null
                && active.getMuteManager().isSoftMuted(event.getPlayer().getUniqueId())) {
            return; // a shadow-muted line is visible only to its sender
        }

        String gameChannel = active != null && active.getChannelManager() != null
                ? active.getChannelManager().lastDeliveredChannel(event.getPlayer()) : "";
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        String group = dev.chatalyst.hook.LuckPermsHook.group(event.getPlayer().getUniqueId());
        bot.sendMinecraftMessage(gameChannel, shown(event.getPlayer()), text, group,
                event.getPlayer().hasPermission("chatalyst.chat.color"),
                event.getPlayer().hasPermission("chatalyst.discord.mention"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("Discord.Announce_Join_Quit", false)
                || dev.chatalyst.util.Vanish.isVanished(event.getPlayer())) {
            return;
        }
        String message = plugin.getConfig()
                .getString("Discord.Join_Message", "{player} joined")
                .replace("{player}", shown(event.getPlayer()));
        bot.sendSystemMessage(MM.strip(message));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("Discord.Announce_Join_Quit", false)
                || dev.chatalyst.util.Vanish.isVanished(event.getPlayer())) {
            return;
        }
        // Misma regla que en el juego: si el server ya le despidio (hueco de una transicion
        // de /anonymous), su marcha ya se conto y repetirla la cuenta dos veces.
        var active = dev.chatalyst.ChatalystPlugin.get();
        if (active != null && active.getAnonymousManager() != null
                && active.getAnonymousManager()
                        .departureAnnounced(event.getPlayer().getUniqueId())) {
            return;
        }
        String message = plugin.getConfig()
                .getString("Discord.Quit_Message", "{player} left")
                .replace("{player}", shown(event.getPlayer()));
        bot.sendSystemMessage(MM.strip(message));
    }
}
