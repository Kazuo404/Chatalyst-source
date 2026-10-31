package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * A Discord role that simply means "this account is linked to Minecraft".
 *
 * <p>Unlike the rank roles, this one is not derived from anything paid or earned: it is a fact
 * about the account, so it is granted the moment the link completes and taken away the moment
 * it breaks. That makes it usable for what a linked-only channel or a member-list section
 * needs, without the owner maintaining it by hand.
 *
 * <p>Deliberately independent of {@code Rank_Sync}: a server can want its members marked as
 * linked without syncing a single rank, and tying the two would make the simple thing require
 * the complicated one.
 */
public final class DiscordLinkedRole implements Listener {

    /** Accounts already repaired since this start-up, so a reconnect costs no request. */
    private final java.util.Set<String> repaired =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ChatalystPlugin plugin;
    private final DiscordBot bot;
    private final DiscordLinkManager links;

    public DiscordLinkedRole(ChatalystPlugin plugin, DiscordBot bot, DiscordLinkManager links) {
        this.plugin = plugin;
        this.bot = bot;
        this.links = links;
    }

    /** Starts listening for links and unlinks. */
    public void start() {
        links.observe((minecraftId, discordId) -> {
            if (discordId != null) apply(discordId, true);
        });
        links.observeUnlink((minecraftId, discordId) -> {
            // Forgotten here so relinking later repairs again instead of trusting a memo
            // about an account that no longer has anything to do with this player.
            repaired.remove(discordId);
            apply(discordId, false);
        });
    }

    /**
     * Repairs the role on join for someone who linked while the bot was down, or who was given
     * the role by hand and then unlinked.
     *
     * <p>On join and not on a timer: a sweep over every link would be a burst of Discord calls
     * that says nothing new most of the time, while a join is naturally spread out and is
     * exactly the moment the answer starts to matter again.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (roleId().isEmpty()) return;
        UUID minecraftId = event.getPlayer().getUniqueId();
        String discordId = links.discordId(minecraftId);
        if (discordId == null) return;
        // Only the FIRST join of this session asks Discord. The repair exists for the rare
        // case of a link made while the bot was down or a role removed by hand; repeating the
        // call on every reconnect would spend a request per join to be told nothing changed.
        if (!repaired.add(discordId)) return;
        apply(discordId, true);
    }

    private void apply(String discordId, boolean linked) {
        String role = roleId();
        if (role.isEmpty() || discordId == null) return;
        bot.setRole(discordId, role, linked);
    }

    private String roleId() {
        if (!plugin.getConfig().getBoolean("Discord.Enabled", false)) return "";
        return plugin.getConfig().getString("Discord.Linking.Linked_Role_Id", "").trim();
    }
}
