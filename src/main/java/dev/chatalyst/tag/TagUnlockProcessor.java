package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SoundUtil;
import dev.chatalyst.util.ToastUtil;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Unico punto de entrada para evaluar obtenciones y sus efectos. Evita perder recompensas
 * cuando la evaluacion nace desde la GUI o un comando en vez del listener periodico.
 */
public final class TagUnlockProcessor {

    private TagUnlockProcessor() {
    }

    public static List<TagDefinition> refresh(ChatalystPlugin plugin,
                                               TagManager tags, Player player) {
        Set<String> completedBefore = completedCollectionIds(
                tags, player.getUniqueId());
        List<TagDefinition> obtained = tags.refreshUnlocks(player);
        if (obtained.isEmpty()) return obtained;
        if (tags.catalog() != null && tags.catalog().notifyUnlock()) {
            for (TagDefinition definition : obtained) {
                notify(plugin, player, definition);
            }
        }
        for (TagDefinition definition : obtained) {
            TagRewardExecutor.execute(plugin, player, definition);
        }
        notifyNewCollections(plugin, tags, player, completedBefore);
        TagDisplay.refresh(plugin, player);
        return obtained;
    }

    /** Muestra la presentacion configurada sin conceder el titulo ni ejecutar recompensas. */
    public static void preview(ChatalystPlugin plugin, Player player,
                               TagDefinition definition) {
        notify(plugin, player, definition);
    }

    /** Presenta una concesion administrativa sin repetir comandos de recompensa. */
    public static void manualGrant(ChatalystPlugin plugin, TagManager tags,
                                   Player player, TagDefinition definition) {
        manualGrant(plugin, tags, player, definition,
                completedCollectionIds(tags, player.getUniqueId()));
    }

    public static void manualGrant(ChatalystPlugin plugin, TagManager tags,
                                   Player player, TagDefinition definition,
                                   Set<String> completedBefore) {
        if (tags.catalog() != null && tags.catalog().notifyUnlock()) {
            notify(plugin, player, definition);
        }
        List<TagDefinition> chained = tags.refreshUnlocks(player);
        if (tags.catalog() != null && tags.catalog().notifyUnlock()) {
            for (TagDefinition unlocked : chained) {
                notify(plugin, player, unlocked);
            }
        }
        for (TagDefinition unlocked : chained) {
            TagRewardExecutor.execute(plugin, player, unlocked);
        }
        notifyNewCollections(plugin, tags, player, completedBefore);
        TagDisplay.refresh(plugin, player);
    }

    public static Set<String> completedCollectionIds(TagManager tags, UUID playerId) {
        if (tags == null || tags.catalog() == null || playerId == null) return Set.of();
        Set<String> completed = new LinkedHashSet<>();
        for (TagCollection collection : tags.catalog().collections()) {
            if (tags.collectionCompleted(playerId, collection)) {
                completed.add(collection.id());
            }
        }
        return Set.copyOf(completed);
    }

    private static void notifyNewCollections(ChatalystPlugin plugin, TagManager tags,
                                             Player player, Set<String> completedBefore) {
        if (tags.catalog() == null || !tags.catalog().notifyUnlock()) return;
        Set<String> previous = completedBefore == null ? Set.of() : completedBefore;
        for (TagCollection collection : tags.catalog().collections()) {
            if (previous.contains(collection.id())
                    || !tags.collectionCompleted(player.getUniqueId(), collection)) {
                continue;
            }
            player.sendMessage(Lang.get(player, "tag.collection-unlocked",
                    "collection", collection.displayName(),
                    "bonus", collection.bonusPoints()));
            ToastUtil.showStyled(plugin, player,
                    Lang.raw(player, "tag.collection-toast-title",
                            "collection", MM.escape(collection.displayName())),
                    Lang.raw(player, "tag.collection-toast-description",
                            "bonus", collection.bonusPoints()),
                    collection.icon(), TagAchievementFrame.CHALLENGE.name());
            SoundUtil.play(plugin, player, SoundUtil.sound(
                    TagAchievement.DEFAULT_SOUND, TagAchievement.DEFAULT_SOUND,
                    0.8f, 1.1f));
        }
    }

    private static void notify(ChatalystPlugin plugin, Player player,
                               TagDefinition definition) {
        TagAchievement achievement = definition.achievement();
        if (achievement.chatEnabled()) {
            if (achievement.chatMessage().isBlank()) {
                player.sendMessage(Lang.get(player, "tag.unlocked",
                        "title", definition.displayName()));
            } else {
                player.sendMessage(MM.full(achievement.render(
                        achievement.chatMessage(), player, definition)));
            }
        }
        if (achievement.toastEnabled()) {
            String title = achievement.toastTitle().isBlank()
                    ? Lang.raw(player, "tag.achievement-toast-title",
                            "title", MM.escape(definition.displayName()))
                    : achievement.render(achievement.toastTitle(), player, definition);
            String description = achievement.toastDescription().isBlank()
                    ? Lang.raw(player, "tag.achievement-toast-description",
                            "rarity", Lang.raw(player, "tag.rarity-"
                                    + definition.rarity().name().toLowerCase(
                                    java.util.Locale.ROOT)),
                            "category", MM.escape(definition.category()))
                    : achievement.render(
                            achievement.toastDescription(), player, definition);
            ToastUtil.showStyled(plugin, player, title, description,
                    achievement.toastIcon(), achievement.frame().name());
        }
        if (achievement.soundEnabled()) {
            SoundUtil.play(plugin, player, SoundUtil.sound(
                    achievement.soundKey(), TagAchievement.DEFAULT_SOUND,
                    achievement.soundVolume(), achievement.soundPitch()));
        }
    }
}
