package dev.chatalyst.api.title;

/** Supported acquisition-rule types. */
public enum TitleRequirementType {
    NONE,
    MOB_KILLS,
    PLAYER_KILLS,
    PLAYTIME_MINUTES,
    BLOCKS_MINED,
    ITEMS_CRAFTED,
    ITEMS_PICKED_UP,
    ITEMS_OWNED,
    FISH_CAUGHT,
    ANIMALS_BRED,
    ADVANCEMENT,
    PERMISSION,
    TITLE_UNLOCKED,
    /** First number of a PlaceholderAPI placeholder (target = {@code %placeholder%}). */
    PLACEHOLDER,
    /** Per-player Chatalyst counter (target = counter key {@code [a-z0-9_]{1,32}}). */
    COUNTER,
    MANUAL
}
