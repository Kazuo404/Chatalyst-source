package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleMutationContext;

import java.util.UUID;

/** Internal port that keeps Bukkit events out of the durable domain manager. */
interface TagLifecycleListener {

    TagLifecycleListener NOOP = new TagLifecycleListener() {
    };

    default void equipped(UUID playerId, String previousTitleId,
                          TagDefinition previousTitle, TagDefinition title,
                          TitleMutationContext context) {
    }

    default void unequipped(UUID playerId, String previousTitleId,
                            TagDefinition previousTitle,
                            TitleMutationContext context) {
    }

    default void unlocked(UUID playerId, TagDefinition title,
                          TitleMutationContext context) {
    }

    default void revoked(UUID playerId, TagDefinition title,
                         TitleMutationContext context) {
    }

    default void collectionCompleted(UUID playerId, TagCollection collection,
                                     TitleMutationContext context) {
    }
}
