package dev.chatalyst.staff;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

/** Resolves the player responsible for a direct or projectile-based staff action. */
final class StaffActor {

    private StaffActor() {
    }

    static Player player(Entity source) {
        if (source instanceof Player player) return player;
        if (source instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
