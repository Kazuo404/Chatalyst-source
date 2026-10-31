package dev.chatalyst.tag;

import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import org.bukkit.Material;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/** Vista inmutable de una categoría convertida en colección de logros. */
public record TagCollection(
        String id,
        String category,
        String displayName,
        String description,
        Material icon,
        int bonusPoints,
        List<TagDefinition> titles
) {

    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_DESCRIPTION_LENGTH = 160;
    public static final int MAX_BONUS_POINTS = 1_000_000;

    public TagCollection {
        id = TagDefinition.normalizeId(id);
        if (id.isEmpty()) throw new IllegalArgumentException("A collection id cannot be empty");
        category = plain(category, TagDefinition.MAX_CATEGORY_LENGTH, id);
        displayName = plain(displayName, MAX_NAME_LENGTH, category);
        description = plain(description, MAX_DESCRIPTION_LENGTH, "");
        icon = TagAchievement.validIcon(icon) ? icon : Material.BUNDLE;
        bonusPoints = Math.max(0, Math.min(MAX_BONUS_POINTS, bonusPoints));
        titles = titles == null ? List.of() : List.copyOf(titles);
    }

    public int earned(Predicate<TagDefinition> unlocked) {
        if (unlocked == null) return 0;
        int earned = 0;
        for (TagDefinition title : titles) {
            if (title.enabled() && unlocked.test(title)) earned++;
        }
        return earned;
    }

    public int total() {
        int total = 0;
        for (TagDefinition title : titles) {
            if (title.enabled()) total++;
        }
        return total;
    }

    public boolean completed(Predicate<TagDefinition> unlocked) {
        int total = total();
        return total > 0 && earned(unlocked) == total;
    }

    public long titlePoints() {
        long points = 0L;
        for (TagDefinition title : titles) {
            if (title.enabled()) points += title.points();
        }
        return points;
    }

    public TagCollection withDisplayName(String value) {
        return copy(value, description, icon, bonusPoints);
    }

    public TagCollection withDescription(String value) {
        return copy(displayName, value, icon, bonusPoints);
    }

    public TagCollection withIcon(Material value) {
        return copy(displayName, description, value, bonusPoints);
    }

    public TagCollection withBonusPoints(int value) {
        return copy(displayName, description, icon, value);
    }

    private TagCollection copy(String nextName, String nextDescription,
                               Material nextIcon, int nextBonus) {
        return new TagCollection(id, category, nextName, nextDescription,
                nextIcon, nextBonus, titles);
    }

    /** ID estable y legible, con sufijo de identidad para evitar colisiones de categorías. */
    public static String idFor(String category) {
        String raw = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String slug = TagDefinition.normalizeId(raw);
        if (slug.isEmpty()) slug = "collection";
        if (slug.length() > 39) slug = slug.substring(0, 39);
        String identity = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 8);
        return slug + "-" + identity;
    }

    private static String plain(String raw, int max, String fallback) {
        String safe = MM.strip(Sanitize.stripInvisible(raw == null ? "" : raw)).trim();
        if (safe.isEmpty()) safe = fallback;
        return Sanitize.limit(safe, max);
    }
}
