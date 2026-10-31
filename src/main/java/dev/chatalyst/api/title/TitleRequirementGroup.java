package dev.chatalyst.api.title;

import java.util.List;
import java.util.Objects;

/** Immutable group of acquisition rules. */
public record TitleRequirementGroup(
        TitleRequirementMode mode,
        List<TitleRequirement> rules
) {

    public TitleRequirementGroup {
        mode = Objects.requireNonNullElse(mode, TitleRequirementMode.ALL);
        if (rules == null) {
            rules = List.of();
        } else {
            for (TitleRequirement rule : rules) {
                if (rule == null) {
                    throw new IllegalArgumentException("A requirement cannot be null");
                }
            }
            rules = List.copyOf(rules);
        }
    }

    public static TitleRequirementGroup free() {
        return new TitleRequirementGroup(TitleRequirementMode.ALL, List.of());
    }
}
