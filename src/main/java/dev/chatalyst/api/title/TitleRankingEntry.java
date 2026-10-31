package dev.chatalyst.api.title;

import java.util.UUID;

/** Deterministically ordered ranking row. */
public record TitleRankingEntry(
        UUID playerId,
        long points,
        int earnedTitles,
        int totalTitles,
        int completedCollections
) {
}
