package dev.chatalyst.api.title;

import java.util.List;

/** Entity-thread snapshot of an achievement's acquisition progress. */
public record TitleProgress(
        String titleId,
        TitleRequirementMode mode,
        boolean unlocked,
        boolean available,
        boolean satisfied,
        List<TitleRequirementProgress> requirements
) {

    public TitleProgress {
        titleId = titleId == null ? "" : titleId;
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
