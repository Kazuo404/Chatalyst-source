package dev.chatalyst.api.title;

import java.util.Objects;

/** Fully configurable native achievement presentation. */
public record TitleAchievement(
        boolean toastEnabled,
        String toastTitle,
        String toastDescription,
        String toastIcon,
        TitleAchievementFrame frame,
        boolean chatEnabled,
        String chatMessage,
        boolean soundEnabled,
        String soundKey,
        float soundVolume,
        float soundPitch
) {

    public TitleAchievement {
        toastTitle = toastTitle == null ? "" : toastTitle;
        toastDescription = toastDescription == null ? "" : toastDescription;
        toastIcon = toastIcon == null ? "PAPER" : toastIcon;
        frame = Objects.requireNonNullElse(frame, TitleAchievementFrame.TASK);
        chatMessage = chatMessage == null ? "" : chatMessage;
        soundKey = soundKey == null
                ? "minecraft:ui.toast.challenge_complete" : soundKey;
    }

    public static TitleAchievement defaults(String icon) {
        return new TitleAchievement(true, "", "", icon, TitleAchievementFrame.TASK,
                true, "", true, "minecraft:ui.toast.challenge_complete", 0.8f, 1.1f);
    }
}
