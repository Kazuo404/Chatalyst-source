package dev.chatalyst.command;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.Vanish;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Immutable player data captured while running on that player's Folia entity scheduler. */
record PlayerUiSnapshot(UUID id, String name, String shownName, String rankNick,
                        String prefix, String world, String ping, boolean vanished) {

    static PlayerUiSnapshot capture(Player player) {
        String shown = NickManager.suggestedName(player);
        String rank = Placeholders.apply(player, "{rank_nick}");
        if (rank.isBlank()) rank = "<#FBF2FF>" + shown;
        return new PlayerUiSnapshot(player.getUniqueId(), player.getName(), shown, rank,
                Placeholders.apply(player, "{player_prefix}"),
                Placeholders.apply(player, "{world}"),
                Placeholders.apply(player, "{ping_ms} <#7B68A0>· {ping_quality}"),
                Vanish.isVanished(player));
    }
}
