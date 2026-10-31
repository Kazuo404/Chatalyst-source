package dev.chatalyst.api.title;

/** Machine-readable result for every title mutation. */
public enum TitleMutationStatus {
    SUCCESS(true),
    NO_CHANGE(true),
    INVALID_ARGUMENT(false),
    NOT_FOUND(false),
    DISABLED(false),
    LOCKED(false),
    ALREADY_UNLOCKED(false),
    NOT_UNLOCKED(false),
    DEPENDENCY_CONFLICT(false),
    CAPACITY_REACHED(false),
    VALIDATION_FAILED(false),
    STORAGE_FAILED(false),
    PLAYER_OFFLINE(false);

    private final boolean successful;

    TitleMutationStatus(boolean successful) {
        this.successful = successful;
    }

    public boolean successful() {
        return successful;
    }
}
