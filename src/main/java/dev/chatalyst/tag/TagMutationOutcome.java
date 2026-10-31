package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleMutationStatus;

/** Atomic domain result adapted to the public API by {@link DefaultTitlesApi}. */
record TagMutationOutcome(
        TitleMutationStatus status,
        String titleId,
        String previousTitleId,
        String message
) {

    TagMutationOutcome {
        titleId = titleId == null ? "" : titleId;
        previousTitleId = previousTitleId == null ? "" : previousTitleId;
        message = message == null ? "" : message;
    }

    boolean successful() {
        return status.successful();
    }
}
