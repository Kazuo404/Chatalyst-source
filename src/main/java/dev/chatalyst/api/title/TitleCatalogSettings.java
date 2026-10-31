package dev.chatalyst.api.title;

/** Mutable catalog switches represented as an immutable API value. */
public record TitleCatalogSettings(boolean notifyUnlock, boolean autoEquipFirst) {
}
