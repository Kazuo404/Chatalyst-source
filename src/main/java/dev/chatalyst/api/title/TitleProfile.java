package dev.chatalyst.api.title;

import java.util.UUID;

/** Derived player progression totals; no duplicated point ledger is stored. */
public record TitleProfile(
        UUID playerId,
        String equippedTitleId,
        long achievementPoints,
        int earnedTitles,
        int availableTitles,
        int completedCollections,
        int availableCollections
) {

    public double completionPercent() {
        return availableTitles <= 0
                ? 0.0D : Math.max(0.0D,
                        Math.min(100.0D, earnedTitles * 100.0D / availableTitles));
    }
}
