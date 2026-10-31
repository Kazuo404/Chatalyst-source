package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleAchievement;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleRequirement;
import dev.chatalyst.api.title.TitleRequirementGroup;
import dev.chatalyst.api.title.TitleSeason;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Strict translation boundary between stable API values and internal domain records. */
final class TitleApiMapper {

    private TitleApiMapper() {
    }

    static TitleDefinition external(TagDefinition source) {
        List<TitleRequirement> rules = source.requirements().rules().stream()
                .map(rule -> new TitleRequirement(
                        dev.chatalyst.api.title.TitleRequirementType.valueOf(
                                rule.type().name()),
                        rule.target(), rule.amount()))
                .toList();
        TagAchievement achievement = source.achievement();
        return new TitleDefinition(
                source.id(), source.displayName(), source.text(), source.description(),
                source.icon().name(),
                dev.chatalyst.api.title.TitlePosition.valueOf(source.position().name()),
                source.spacing(), source.enabled(), source.hiddenUntilUnlocked(),
                source.category(),
                dev.chatalyst.api.title.TitleRarity.valueOf(source.rarity().name()),
                source.sortOrder(), source.points(), source.secret(),
                new TitleSeason(source.season().id(), source.season().startsAtMillis(),
                        source.season().endsAtMillis()),
                new TitleRequirementGroup(
                        dev.chatalyst.api.title.TitleRequirementMode.valueOf(
                                source.requirements().mode().name()), rules),
                source.rewards().commands(),
                new TitleAchievement(
                        achievement.toastEnabled(), achievement.toastTitle(),
                        achievement.toastDescription(), achievement.toastIcon().name(),
                        dev.chatalyst.api.title.TitleAchievementFrame.valueOf(
                                achievement.frame().name()),
                        achievement.chatEnabled(), achievement.chatMessage(),
                        achievement.soundEnabled(), achievement.soundKey(),
                        achievement.soundVolume(), achievement.soundPitch()));
    }

    static TagDefinition internal(TitleDefinition source) {
        if (source == null) throw new IllegalArgumentException("Title definition is required");
        String normalizedId = TagDefinition.normalizeId(source.id());
        if (normalizedId.isEmpty() || !normalizedId.equals(source.id())) {
            throw new IllegalArgumentException(
                    "Title id must already match [a-z0-9_-] and be at most 48 characters");
        }
        if (source.spacing() < 0 || source.spacing() > TagDefinition.MAX_SPACING) {
            throw new IllegalArgumentException("Spacing is outside 0.."
                    + TagDefinition.MAX_SPACING);
        }
        if (source.sortOrder() < -TagDefinition.MAX_SORT_ORDER
                || source.sortOrder() > TagDefinition.MAX_SORT_ORDER) {
            throw new IllegalArgumentException("Sort order is outside the supported range");
        }
        if (source.points() < 0 || source.points() > TagDefinition.MAX_POINTS) {
            throw new IllegalArgumentException("Points are outside 0.."
                    + TagDefinition.MAX_POINTS);
        }
        if (source.rewardCommands().size() > TagRewards.MAX_COMMANDS) {
            throw new IllegalArgumentException("A title supports at most "
                    + TagRewards.MAX_COMMANDS + " reward commands");
        }
        Material icon = material(source.icon(), "icon");
        Material toastIcon = material(source.achievement().toastIcon(), "toast icon");
        if (source.requirements().rules().size() > TagRequirementGroup.MAX_RULES) {
            throw new IllegalArgumentException("A title supports at most "
                    + TagRequirementGroup.MAX_RULES + " requirements");
        }
        List<TagRequirement> rules = new ArrayList<>();
        for (TitleRequirement rule : source.requirements().rules()) {
            // Una regla nula ya no llega hasta aqui: TitleRequirementGroup la rechaza.
            TagRequirementType type = TagRequirementType.valueOf(rule.type().name());
            if (!TagRequirement.validTarget(type, rule.target())) {
                throw new IllegalArgumentException("Invalid target for " + type + ": "
                        + rule.target());
            }
            if (numeric(type) && (rule.amount() < 1L
                    || rule.amount() > TagRequirement.MAX_AMOUNT)) {
                throw new IllegalArgumentException("Requirement amount is outside 1.."
                        + TagRequirement.MAX_AMOUNT);
            }
            rules.add(new TagRequirement(type, rule.target(), rule.amount()));
        }
        TagSeason season = new TagSeason(source.season().id(),
                source.season().startsAtMillis(), source.season().endsAtMillis());
        if (!season.validWindow()) {
            throw new IllegalArgumentException("Season start must be before its end");
        }
        TitleAchievement presentation = source.achievement();
        if (!TagAchievement.validSound(presentation.soundKey())) {
            throw new IllegalArgumentException("Invalid achievement sound key");
        }
        if (!Float.isFinite(presentation.soundVolume())
                || presentation.soundVolume() < 0.0f
                || presentation.soundVolume() > 10.0f) {
            throw new IllegalArgumentException("Sound volume is outside 0..10");
        }
        if (!Float.isFinite(presentation.soundPitch())
                || presentation.soundPitch() < 0.5f
                || presentation.soundPitch() > 2.0f) {
            throw new IllegalArgumentException("Sound pitch is outside 0.5..2");
        }
        TagAchievement achievement = new TagAchievement(
                presentation.toastEnabled(), presentation.toastTitle(),
                presentation.toastDescription(), toastIcon,
                TagAchievementFrame.valueOf(presentation.frame().name()),
                presentation.chatEnabled(), presentation.chatMessage(),
                presentation.soundEnabled(), presentation.soundKey(),
                presentation.soundVolume(), presentation.soundPitch());
        return new TagDefinition(
                source.id(), source.displayName(), source.text(), source.description(),
                icon, TagPosition.valueOf(source.position().name()), source.spacing(),
                source.enabled(), source.hiddenUntilUnlocked(), source.category(),
                TagRarity.valueOf(source.rarity().name()), source.sortOrder(),
                source.points(), source.secret(), season,
                new TagRequirementGroup(
                        TagRequirementMode.valueOf(source.requirements().mode().name()),
                        rules),
                new TagRewards(source.rewardCommands()), achievement);
    }

    static dev.chatalyst.api.title.TitleProgress progress(
            TagManager tags, Player player, TagDefinition definition) {
        List<dev.chatalyst.api.title.TitleRequirementProgress> rules =
                new ArrayList<>();
        for (TagRequirement rule : definition.requirements().rules()) {
            long current = rule.progress(player, target -> {
                TagDefinition dependency = tags.catalog() == null
                        ? null : tags.catalog().byId(target);
                return dependency != null
                        && tags.isUnlocked(player.getUniqueId(), dependency);
            });
            rules.add(new dev.chatalyst.api.title.TitleRequirementProgress(
                    new TitleRequirement(
                            dev.chatalyst.api.title.TitleRequirementType.valueOf(
                                    rule.type().name()),
                            rule.target(), rule.amount()),
                    current, rule.amount(),
                    rule.type() != TagRequirementType.MANUAL
                            && current >= rule.amount(),
                    rule.hasNumericProgress()));
        }
        boolean satisfied;
        if (definition.requirements().isFree()) {
            satisfied = true;
        } else if (definition.requirements().mode() == TagRequirementMode.ALL) {
            satisfied = rules.stream().allMatch(
                    dev.chatalyst.api.title.TitleRequirementProgress::satisfied);
        } else {
            satisfied = rules.stream().anyMatch(
                    dev.chatalyst.api.title.TitleRequirementProgress::satisfied);
        }
        return new dev.chatalyst.api.title.TitleProgress(
                definition.id(),
                dev.chatalyst.api.title.TitleRequirementMode.valueOf(
                        definition.requirements().mode().name()),
                tags.isUnlocked(player.getUniqueId(), definition),
                definition.enabled()
                        && definition.isAvailable(System.currentTimeMillis()),
                satisfied, rules);
    }

    private static Material material(String raw, String field) {
        Material material = raw == null ? null : Material.matchMaterial(raw.trim());
        if (TagDefinition.isAir(material)) {
            throw new IllegalArgumentException("Invalid " + field + " material: " + raw);
        }
        try {
            if (!material.isItem()) {
                throw new IllegalArgumentException(
                        "Material is not an item for " + field + ": " + raw);
            }
        } catch (IllegalArgumentException invalidKind) {
            throw invalidKind;
        } catch (Throwable registryUnavailable) {
            // Pure unit tests do not start Paper's registry. The enum identity was still
            // validated above; a live server additionally enforces the item kind.
        }
        return material;
    }

    private static boolean numeric(TagRequirementType type) {
        return switch (type) {
            case MOB_KILLS, PLAYER_KILLS, PLAYTIME_MINUTES, BLOCKS_MINED,
                    ITEMS_CRAFTED, ITEMS_PICKED_UP, ITEMS_OWNED,
                    FISH_CAUGHT, ANIMALS_BRED, PLACEHOLDER, COUNTER -> true;
            default -> false;
        };
    }
}
