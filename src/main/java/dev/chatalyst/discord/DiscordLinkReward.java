package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.store.StoreClaims;
import dev.chatalyst.store.StoreService;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One-time reward for linking a Minecraft account to Discord.
 *
 * <p>Paid ONCE per Minecraft account AND once per Discord account. Either key alone could be
 * farmed: unlinking and relinking the same player to a second Discord, or one Discord moving
 * across a pile of alts. Both markers are claimed with a compare-and-set before anything is
 * owed, so two links racing on different servers cannot both win.
 *
 * <p>The goods themselves go through the store ledger ({@code Store_Catalog} product named in
 * {@code Discord.Linking.Reward.Product}): the same at-most-once delivery as a purchase, and it
 * waits for the player if they linked while offline or with a full inventory.
 */
public final class DiscordLinkReward {

    private static final String PATH = "Discord.Linking.Reward.";

    private final ChatalystPlugin plugin;
    private final DiscordLinkManager links;
    private final Store markers;

    public DiscordLinkReward(ChatalystPlugin plugin, StorageManager storage,
                             DiscordLinkManager links) {
        this.plugin = plugin;
        this.links = links;
        this.markers = storage.store("discord_link_rewards");
    }

    public void start() {
        // Link observers already run off the server thread (where the link was persisted),
        // which is exactly where the storage calls below must happen.
        links.observe((minecraftId, discordId) -> {
            if (discordId != null) reward(minecraftId, discordId);
        });
    }

    void reward(UUID minecraftId, String discordId) {
        var config = plugin.getConfig();
        if (!config.getBoolean(PATH + "Enabled", false)) return;
        String product = config.getString(PATH + "Product", "").trim();
        StoreService store = plugin.getStoreService();
        if (product.isEmpty() || store == null) {
            plugin.getLogger().warning("Discord link reward is enabled but "
                    + (store == null ? "the item store is not running" : "no Product is set")
                    + "; nothing was given to " + minecraftId + ".");
            return;
        }
        long now = System.currentTimeMillis();
        String byDiscord = "dc:" + discordId;
        String byMinecraft = "mc:" + minecraftId;
        if (markers.get(byMinecraft) != null || markers.get(byDiscord) != null) {
            plugin.getLogger().info("Discord link reward already claimed by this Minecraft or"
                    + " Discord account (" + minecraftId + " / " + discordId + "); not repeated.");
            return;
        }
        if (!markers.compareAndPutDurably(byDiscord, null, minecraftId + "@" + now)) return;
        if (!markers.compareAndPutDurably(byMinecraft, null, discordId + "@" + now)) {
            // The other half lost a race: give the Discord marker back so neither is burned.
            markers.compareAndRemoveDurably(byDiscord, minecraftId + "@" + now);
            return;
        }
        StoreClaims.Reservation result = store.grant(minecraftId, product,
                "vinculo-" + minecraftId.toString().toLowerCase(java.util.Locale.ROOT));
        switch (result) {
            case CREATED, ALREADY_PENDING, ALREADY_DELIVERED -> notifyPlayer(minecraftId);
            default -> {
                // Not owed after all (store off, unknown product, storage down): release the
                // markers so the reward is still there once the problem is fixed.
                markers.compareAndRemoveDurably(byMinecraft, discordId + "@" + now);
                markers.compareAndRemoveDurably(byDiscord, minecraftId + "@" + now);
                plugin.getLogger().warning("Discord link reward for " + minecraftId
                        + " could not be queued (" + result + "); it will be offered again"
                        + " the next time this account links.");
            }
        }
    }

    private void notifyPlayer(UUID minecraftId) {
        String message = plugin.getConfig().getString(PATH + "Message", "");
        if (message == null || message.isBlank()) return;
        Player player = plugin.getServer().getPlayer(minecraftId);
        if (player == null) return;
        FoliaTasks.entity(plugin, player, target -> target.sendMessage(MM.full(message)));
    }
}
