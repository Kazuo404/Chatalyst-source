package dev.chatalyst.util;

import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/**
 * Detects whether a player is in vanish using the de-facto standard "vanished" metadata,
 * set by Essentials, SuperVanish/PremiumVanish, CMI and others. No hard dependency needed.
 */
public final class Vanish {

    private Vanish() {
    }

    public static boolean isVanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }
}
