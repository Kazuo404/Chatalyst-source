package dev.chatalyst.api.title;

/** Current progress for one acquisition rule. */
public record TitleRequirementProgress(
        TitleRequirement requirement,
        long current,
        long required,
        boolean satisfied,
        boolean numeric
) {
}
