package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.entity.Player;

/** Refresca todas las superficies que pueden contener {tag_prefix}/{tag_suffix}. */
public final class TagDisplay {

    private TagDisplay() {
    }

    public static void refresh(ChatalystPlugin plugin, Player player) {
        if (plugin == null || player == null) return;
        if (plugin.getTabListManager() != null) plugin.getTabListManager().update(player);
        if (plugin.getNametagManager() != null) plugin.getNametagManager().update(player);
        if (plugin.getFancyNametags() != null) plugin.getFancyNametags().refresh(player);
    }
}
