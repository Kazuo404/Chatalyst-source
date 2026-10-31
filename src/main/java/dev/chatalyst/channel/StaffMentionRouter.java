package dev.chatalyst.channel;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.support.SupportConversationManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Detects natural requests for configured staff ranks and opens a private support request. */
public final class StaffMentionRouter implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final JavaPlugin plugin;
    private final ChannelManager channels;
    private final SupportConversationManager support;
    private final Map<UUID, Long> lastRoute = new ConcurrentHashMap<>();
    private volatile StaffMentionMatcher matcher = new StaffMentionMatcher(List.of(), false, List.of());
    private volatile boolean enabled;
    private volatile BiConsumer<Player, Component> notify = (player, message) -> { };
    private volatile long cooldownMs;

    public StaffMentionRouter(JavaPlugin plugin, ChannelManager channels,
            SupportConversationManager support) {
        this.plugin = plugin;
        this.channels = channels;
        this.support = support;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Staff_Mentions.Enabled", true);
        notify = plugin.getConfig().getBoolean("Staff_Mentions.Notify_Sender", true)
                ? (player, message) -> player.sendMessage(message) : (player, message) -> { };
        cooldownMs = Math.max(0L, Math.min(300L,
                plugin.getConfig().getLong("Staff_Mentions.Cooldown_Seconds", 15L))) * 1_000L;
        List<String> groups = new java.util.ArrayList<>(
                plugin.getConfig().getStringList("Staff_Mentions.Groups"));
        String permission = plugin.getConfig().getString(
                "Staff_Mentions.LuckPerms_Group_Permission", "chatalyst.staff.mentionable");
        groups.addAll(dev.chatalyst.hook.LuckPermsHook.groupsWithPermission(permission));
        matcher = new StaffMentionMatcher(groups,
                plugin.getConfig().getBoolean("Staff_Mentions.Require_At", false),
                plugin.getConfig().getStringList("Staff_Mentions.Help_Intent_Words"));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled || !channels.isEnabled()) return;
        Player sender = event.getPlayer();
        if (sender.hasPermission("chatalyst.support.staff")
                || sender.hasPermission("chatalyst.channel.staff")) return;
        if (channels.hasPendingRoute(sender.getUniqueId())) return;
        Channel writing = channels.getWriteChannel(sender);
        Channel defaultChannel = channels.getDefault();
        if (writing == null || defaultChannel == null || !writing.name.equals(defaultChannel.name)) return;

        String group = matcher.find(PLAIN.serialize(event.message()));
        if (group.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long previous = lastRoute.get(sender.getUniqueId());
        if (previous != null && now - previous < cooldownMs) {
            event.setCancelled(true);
            long seconds = Math.max(1L, (cooldownMs - now + previous + 999L) / 1_000L);
            notify.accept(sender, Lang.get(sender, "staff-mentions.cooldown", "seconds", seconds));
            return;
        }
        SupportConversationManager.RequestResult result = support.request(sender, group,
                PLAIN.serialize(event.message()));
        if (result == SupportConversationManager.RequestResult.IN_SESSION
                || result == SupportConversationManager.RequestResult.DISABLED) return;
        event.setCancelled(true);
        if (result == SupportConversationManager.RequestResult.ALREADY_WAITING) {
            notify.accept(sender, Lang.get(sender, "support.already-waiting"));
            return;
        }
        lastRoute.put(sender.getUniqueId(), now);
        notify.accept(sender, Lang.get(sender, "staff-mentions.routed", "group", group));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastRoute.remove(event.getPlayer().getUniqueId());
    }
}
