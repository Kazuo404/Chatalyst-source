package dev.chatalyst.api.title;

import java.util.Objects;

/** Typed mutation result. Messages are diagnostic and never localized player output. */
public record TitleMutationResult(
        TitleMutationStatus status,
        String titleId,
        String previousTitleId,
        String message
) {

    public TitleMutationResult {
        status = Objects.requireNonNullElse(
                status, TitleMutationStatus.INVALID_ARGUMENT);
        titleId = titleId == null ? "" : titleId;
        previousTitleId = previousTitleId == null ? "" : previousTitleId;
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status.successful();
    }
}
