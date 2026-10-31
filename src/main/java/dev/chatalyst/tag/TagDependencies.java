package dev.chatalyst.tag;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Validacion pura del grafo de requisitos entre titulos. */
final class TagDependencies {

    private TagDependencies() {
    }

    static boolean hasCycle(Map<String, TagDefinition> definitions) {
        Map<String, Visit> visits = new HashMap<>();
        for (String id : definitions.keySet()) {
            if (visit(id, definitions, visits)) return true;
        }
        return false;
    }

    static Set<String> missingTargets(Map<String, TagDefinition> definitions) {
        Set<String> missing = new LinkedHashSet<>();
        for (TagDefinition definition : definitions.values()) {
            for (TagRequirement requirement : definition.requirements().rules()) {
                if (requirement.type() != TagRequirementType.TITLE_UNLOCKED) continue;
                String target = TagDefinition.normalizeId(requirement.target());
                if (!definitions.containsKey(target)) {
                    missing.add(definition.id() + " -> " + target);
                }
            }
        }
        return Set.copyOf(missing);
    }

    static boolean hasMissingTarget(TagDefinition definition,
                                    Map<String, TagDefinition> definitions) {
        if (definition == null) return true;
        return definition.requirements().rules().stream().anyMatch(
                requirement -> requirement.type() == TagRequirementType.TITLE_UNLOCKED
                        && !definitions.containsKey(requirement.target()));
    }

    static int dependentCount(Map<String, TagDefinition> definitions, String targetId) {
        String normalized = TagDefinition.normalizeId(targetId);
        int count = 0;
        for (TagDefinition definition : definitions.values()) {
            boolean depends = definition.requirements().rules().stream().anyMatch(
                    requirement -> requirement.type() == TagRequirementType.TITLE_UNLOCKED
                            && requirement.target().equals(normalized));
            if (depends) count++;
        }
        return count;
    }

    private static boolean visit(String id, Map<String, TagDefinition> definitions,
                                 Map<String, Visit> visits) {
        Visit current = visits.get(id);
        if (current == Visit.ACTIVE) return true;
        if (current == Visit.COMPLETE) return false;
        visits.put(id, Visit.ACTIVE);
        TagDefinition definition = definitions.get(id);
        if (definition != null) {
            for (TagRequirement requirement : definition.requirements().rules()) {
                if (requirement.type() != TagRequirementType.TITLE_UNLOCKED) continue;
                String target = TagDefinition.normalizeId(requirement.target());
                if (definitions.containsKey(target) && visit(target, definitions, visits)) {
                    return true;
                }
            }
        }
        visits.put(id, Visit.COMPLETE);
        return false;
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }
}
