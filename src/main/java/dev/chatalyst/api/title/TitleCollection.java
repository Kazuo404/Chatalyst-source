package dev.chatalyst.api.title;

import java.util.List;

/** Player-scoped collection snapshot. */
public record TitleCollection(
        String id,
        String category,
        String displayName,
        String description,
        String icon,
        int bonusPoints,
        int earnedTitles,
        int totalTitles,
        boolean completed,
        List<String> titleIds
) {

    public TitleCollection {
        id = id == null ? "" : id;
        category = category == null ? "" : category;
        displayName = displayName == null ? "" : displayName;
        description = description == null ? "" : description;
        icon = icon == null ? "BUNDLE" : icon;
        titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
    }
}
