package dev.chatalyst.api.title;

import java.util.Objects;
import java.util.UUID;

/** Actor and reason propagated to lifecycle events. */
public record TitleMutationContext(
        TitleChangeCause cause,
        UUID actorId,
        String actorName,
        String reason
) {

    public TitleMutationContext {
        cause = Objects.requireNonNullElse(cause, TitleChangeCause.API);
        actorName = bounded(actorName, 64);
        reason = bounded(reason, 256);
    }

    public static TitleMutationContext api() {
        return new TitleMutationContext(TitleChangeCause.API, null, "", "");
    }

    public static TitleMutationContext system(TitleChangeCause cause) {
        return new TitleMutationContext(cause, null, "", "");
    }

    public static TitleMutationContext player(UUID id, String name) {
        return new TitleMutationContext(TitleChangeCause.PLAYER, id, name, "");
    }

    public static TitleMutationContext admin(UUID id, String name, String reason) {
        return new TitleMutationContext(TitleChangeCause.ADMIN, id, name, reason);
    }

    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
