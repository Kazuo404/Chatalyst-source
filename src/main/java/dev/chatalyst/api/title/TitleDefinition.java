package dev.chatalyst.api.title;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable catalog definition used for both reads and validated create/update operations.
 *
 * <p>Use {@link #builder(String)} when authoring a definition. Chatalyst performs its domain
 * normalization and dependency checks before writing the catalog.
 */
public record TitleDefinition(
        String id,
        String displayName,
        String text,
        String description,
        String icon,
        TitlePosition position,
        int spacing,
        boolean enabled,
        boolean hiddenUntilUnlocked,
        String category,
        TitleRarity rarity,
        int sortOrder,
        int points,
        boolean secret,
        TitleSeason season,
        TitleRequirementGroup requirements,
        List<String> rewardCommands,
        TitleAchievement achievement
) {

    public TitleDefinition {
        id = id == null ? "" : id;
        displayName = displayName == null ? "" : displayName;
        text = text == null ? "" : text;
        description = description == null ? "" : description;
        icon = icon == null ? "NAME_TAG" : icon;
        position = Objects.requireNonNullElse(position, TitlePosition.LEFT);
        category = category == null ? "General" : category;
        rarity = Objects.requireNonNullElse(rarity, TitleRarity.COMMON);
        season = Objects.requireNonNullElseGet(season, TitleSeason::none);
        requirements = Objects.requireNonNullElseGet(
                requirements, TitleRequirementGroup::free);
        rewardCommands = rewardCommands == null ? List.of() : List.copyOf(rewardCommands);
        if (achievement == null) achievement = TitleAchievement.defaults(icon);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Builder keeps source compatibility when new optional presentation fields are added. */
    public static final class Builder {
        private final String id;
        private String displayName;
        private String text = "";
        private String description = "";
        private String icon = "NAME_TAG";
        private TitlePosition position = TitlePosition.LEFT;
        private int spacing = 1;
        private boolean enabled = true;
        private boolean hiddenUntilUnlocked;
        private String category = "General";
        private TitleRarity rarity = TitleRarity.COMMON;
        private int sortOrder;
        private int points = 5;
        private boolean pointsExplicit;
        private boolean secret;
        private TitleSeason season = TitleSeason.none();
        private TitleRequirementGroup requirements = TitleRequirementGroup.free();
        private List<String> rewardCommands = List.of();
        private TitleAchievement achievement = TitleAchievement.defaults("NAME_TAG");
        private boolean achievementExplicit;

        private Builder(String id) {
            this.id = id == null ? "" : id;
            this.displayName = this.id;
        }

        private Builder(TitleDefinition source) {
            this.id = source.id();
            this.displayName = source.displayName();
            this.text = source.text();
            this.description = source.description();
            this.icon = source.icon();
            this.position = source.position();
            this.spacing = source.spacing();
            this.enabled = source.enabled();
            this.hiddenUntilUnlocked = source.hiddenUntilUnlocked();
            this.category = source.category();
            this.rarity = source.rarity();
            this.sortOrder = source.sortOrder();
            this.points = source.points();
            this.pointsExplicit = true;
            this.secret = source.secret();
            this.season = source.season();
            this.requirements = source.requirements();
            this.rewardCommands = source.rewardCommands();
            this.achievement = source.achievement();
            this.achievementExplicit = true;
        }

        public Builder displayName(String value) { displayName = value; return this; }
        public Builder text(String value) { text = value; return this; }
        public Builder description(String value) { description = value; return this; }
        public Builder icon(String value) {
            icon = value == null ? "NAME_TAG" : value;
            if (!achievementExplicit) achievement = TitleAchievement.defaults(icon);
            return this;
        }
        public Builder position(TitlePosition value) { position = value; return this; }
        public Builder spacing(int value) { spacing = value; return this; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder hiddenUntilUnlocked(boolean value) {
            hiddenUntilUnlocked = value;
            return this;
        }
        public Builder category(String value) { category = value; return this; }
        public Builder rarity(TitleRarity value) {
            rarity = value;
            if (!pointsExplicit) points = defaultPoints(value);
            return this;
        }
        public Builder sortOrder(int value) { sortOrder = value; return this; }
        public Builder points(int value) {
            points = value;
            pointsExplicit = true;
            return this;
        }
        public Builder secret(boolean value) { secret = value; return this; }
        public Builder season(TitleSeason value) { season = value; return this; }
        public Builder requirements(TitleRequirementGroup value) {
            requirements = value;
            return this;
        }
        public Builder rewardCommands(List<String> value) {
            rewardCommands = value == null ? List.of() : new ArrayList<>(value);
            return this;
        }
        public Builder achievement(TitleAchievement value) {
            achievement = value;
            achievementExplicit = true;
            return this;
        }

        public TitleDefinition build() {
            return new TitleDefinition(id, displayName, text, description, icon, position,
                    spacing, enabled, hiddenUntilUnlocked, category, rarity, sortOrder,
                    points, secret, season, requirements, rewardCommands, achievement);
        }

        private static int defaultPoints(TitleRarity value) {
            if (value == null) return 5;
            return switch (value) {
                case COMMON -> 5;
                case UNCOMMON -> 10;
                case RARE -> 25;
                case EPIC -> 50;
                case LEGENDARY -> 100;
                case MYTHIC -> 250;
            };
        }
    }
}
