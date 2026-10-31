package dev.chatalyst.api.title;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of an entity-scheduled achievement evaluation. */
public record TitleEvaluationResult(
        TitleMutationStatus status,
        List<TitleDefinition> unlocked,
        Optional<TitleProfile> profile,
        String message
) {

    public TitleEvaluationResult {
        status = Objects.requireNonNullElse(
                status, TitleMutationStatus.INVALID_ARGUMENT);
        unlocked = unlocked == null ? List.of() : List.copyOf(unlocked);
        profile = profile == null ? Optional.empty() : profile;
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status.successful();
    }
}
