package dev.chatalyst.tag;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Grupo inmutable de hasta ocho reglas de obtencion. */
public record TagRequirementGroup(TagRequirementMode mode, List<TagRequirement> rules) {

    public static final int MAX_RULES = 8;

    public TagRequirementGroup {
        mode = mode == null ? TagRequirementMode.ALL : mode;
        List<TagRequirement> safe = new ArrayList<>();
        if (rules != null) {
            for (TagRequirement rule : rules) {
                if (rule != null && safe.size() < MAX_RULES) safe.add(rule);
            }
        }
        rules = List.copyOf(safe);
    }

    public static TagRequirementGroup free() {
        return new TagRequirementGroup(TagRequirementMode.ALL, List.of());
    }

    public static TagRequirementGroup single(TagRequirement rule) {
        if (rule == null || rule.type() == TagRequirementType.NONE) return free();
        return new TagRequirementGroup(TagRequirementMode.ALL, List.of(rule));
    }

    public boolean isFree() {
        if (rules.isEmpty()) return true;
        return mode == TagRequirementMode.ALL
                ? rules.stream().allMatch(rule -> rule.type() == TagRequirementType.NONE)
                : rules.stream().anyMatch(rule -> rule.type() == TagRequirementType.NONE);
    }

    public boolean satisfied(Player player) {
        return satisfied(player, ignored -> false);
    }

    public boolean satisfied(Player player, Predicate<String> titleUnlocked) {
        if (isFree()) return true;
        return mode == TagRequirementMode.ALL
                ? rules.stream().allMatch(rule -> rule.satisfied(player, titleUnlocked))
                : rules.stream().anyMatch(rule -> rule.satisfied(player, titleUnlocked));
    }

    public TagRequirementGroup withMode(TagRequirementMode next) {
        return new TagRequirementGroup(next, rules);
    }

    public TagRequirementGroup add(TagRequirement rule) {
        if (rule == null || rules.size() >= MAX_RULES) return this;
        List<TagRequirement> next = new ArrayList<>(rules);
        next.add(rule);
        return new TagRequirementGroup(mode, next);
    }

    public TagRequirementGroup update(int index, TagRequirement rule) {
        if (index < 0 || index >= rules.size() || rule == null) return this;
        List<TagRequirement> next = new ArrayList<>(rules);
        next.set(index, rule);
        return new TagRequirementGroup(mode, next);
    }

    public TagRequirementGroup remove(int index) {
        if (index < 0 || index >= rules.size()) return this;
        List<TagRequirement> next = new ArrayList<>(rules);
        next.remove(index);
        return new TagRequirementGroup(mode, next);
    }
}
