package dev.chatalyst.giveaway;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

import java.util.Set;
import java.util.UUID;

/**
 * Nobody can be hurt while the wheel is turning.
 *
 * <p>Not a nicety: opening a menu for the whole server takes away everyone's ability to run,
 * block, eat or log out of a fight at once. Without this, the correct strategy for a giveaway
 * would be to skip it and go kill the people watching it, and a creeper walking past would
 * decide who is still around for the reveal.
 *
 * <p>It protects the WHOLE audience, not just the entrants: a player who cannot win still had
 * their screen taken over. Protection is dropped a few seconds after the reveal so the menu
 * closing does not drop everyone back into the fight they were pulled out of.
 */
public final class GiveawayImmunity implements Listener {

    /**
     * Swapped whole, never edited in place.
     *
     * <p>The obvious version — clear the set, then add the new members — leaves a window in
     * which NOBODY is protected, and the set is rewritten every time someone disconnects
     * mid-draw. A damage event landing inside that window would go through on a player who is
     * supposed to be untouchable, which is exactly the bug this class exists to prevent, and it
     * would happen once in a hundred draws in a way nobody could ever reproduce.
     */
    private volatile Set<UUID> protectedPlayers = Set.of();
    private volatile boolean cancelVoid = true;

    void cancelVoid(boolean cancel) {
        this.cancelVoid = cancel;
    }

    /** Replaces the protected set; an empty collection switches immunity off entirely. */
    void protect(Iterable<UUID> ids) {
        Set<UUID> next = new java.util.HashSet<>();
        for (UUID id : ids) {
            if (id != null) next.add(id);
        }
        protectedPlayers = Set.copyOf(next);
    }

    void release() {
        protectedPlayers = Set.of();
    }

    public boolean isProtected(UUID id) {
        return id != null && protectedPlayers.contains(id);
    }

    /**
     * HIGHEST rather than MONITOR: the decision has to be visible to plugins that read the
     * outcome (combat taggers, arena plugins), and it must not run so late that they have
     * already tagged the victim. {@code ignoreCancelled} is deliberately absent — another
     * plugin cancelling first changes nothing about what we want.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isProtected(player.getUniqueId())) return;
        if (!cancelVoid && event.getCause() == EntityDamageEvent.DamageCause.VOID) return;
        event.setCancelled(true);
        // Cancelling the damage stops the hurt, not the fire: without this the flames keep
        // burning for the whole draw and finish the job the second immunity ends.
        if (player.getFireTicks() > 0) player.setFireTicks(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player player
                && isProtected(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Only hunger going DOWN is blocked; a prize that feeds you should still feed you. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isProtected(player.getUniqueId())) return;
        if (event.getFoodLevel() < player.getFoodLevel()) event.setCancelled(true);
    }
}
