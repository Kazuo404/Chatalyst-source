package dev.chatalyst.api.title;

/** Validated presentation update for an existing derived collection. */
public record TitleCollectionUpdate(
        String id,
        String displayName,
        String description,
        String icon,
        int bonusPoints
) {

    public TitleCollectionUpdate {
        id = id == null ? "" : id;
        displayName = displayName == null ? "" : displayName;
        description = description == null ? "" : description;
        icon = icon == null ? "BUNDLE" : icon;
    }
}
