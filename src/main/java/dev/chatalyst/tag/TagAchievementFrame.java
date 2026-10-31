package dev.chatalyst.tag;

import java.util.Locale;

/** Marco nativo del toast de advancement de Minecraft. */
public enum TagAchievementFrame {
    TASK,
    GOAL,
    CHALLENGE;

    public TagAchievementFrame step(int delta) {
        TagAchievementFrame[] values = values();
        return values[Math.floorMod(ordinal() + delta, values.length)];
    }

    public static TagAchievementFrame parse(String raw) {
        if (raw == null) return TASK;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TASK;
        }
    }
}
