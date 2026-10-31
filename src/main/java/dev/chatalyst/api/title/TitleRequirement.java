package dev.chatalyst.api.title;

import java.util.Objects;

/** Immutable acquisition rule. Runtime validation occurs before catalog persistence. */
public record TitleRequirement(TitleRequirementType type, String target, long amount) {

    public TitleRequirement {
        type = Objects.requireNonNullElse(type, TitleRequirementType.NONE);
        target = target == null ? "" : target;
    }
}
