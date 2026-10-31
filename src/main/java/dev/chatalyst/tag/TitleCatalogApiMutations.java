package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleCatalogSettings;
import dev.chatalyst.api.title.TitleCollectionUpdate;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleMutationResult;
import dev.chatalyst.api.title.TitleMutationStatus;
import org.bukkit.Material;

/** Validated administrative writes for the public API. */
final class TitleCatalogApiMutations {

    private final TagCatalog catalog;

    TitleCatalogApiMutations(TagCatalog catalog) {
        this.catalog = catalog;
    }

    TitleMutationResult create(TitleDefinition definition) {
        TagDefinition internal;
        try {
            internal = TitleApiMapper.internal(definition);
        } catch (IllegalArgumentException invalid) {
            return result(TitleMutationStatus.VALIDATION_FAILED,
                    definition == null ? "" : definition.id(), "", invalid.getMessage());
        }
        if (catalog.contains(internal.id())) {
            return result(TitleMutationStatus.NO_CHANGE, internal.id(), "",
                    "A title with this id already exists");
        }
        if (catalog.isFull()) {
            return result(TitleMutationStatus.CAPACITY_REACHED, internal.id(), "",
                    "The catalog reached its title limit");
        }
        if (!catalog.canInsert(internal)) {
            return result(TitleMutationStatus.DEPENDENCY_CONFLICT, internal.id(), "",
                    "The title has a missing or cyclic dependency");
        }
        return catalog.insert(internal)
                ? result(TitleMutationStatus.SUCCESS, internal.id(), "", "")
                : result(TitleMutationStatus.STORAGE_FAILED, internal.id(), "",
                        "Could not persist tags.yml");
    }

    TitleMutationResult update(TitleDefinition definition) {
        TagDefinition internal;
        try {
            internal = TitleApiMapper.internal(definition);
        } catch (IllegalArgumentException invalid) {
            return result(TitleMutationStatus.VALIDATION_FAILED,
                    definition == null ? "" : definition.id(), "", invalid.getMessage());
        }
        TagDefinition previous = catalog.byId(internal.id());
        if (previous == null) {
            return result(TitleMutationStatus.NOT_FOUND, internal.id(), "",
                    "Unknown title");
        }
        if (!catalog.canUpdate(internal)) {
            return result(TitleMutationStatus.DEPENDENCY_CONFLICT, internal.id(), "",
                    "The update has a missing or cyclic dependency");
        }
        if (previous.equals(internal)) {
            return result(TitleMutationStatus.NO_CHANGE, internal.id(), "", "");
        }
        return catalog.update(internal)
                ? result(TitleMutationStatus.SUCCESS, internal.id(), "", "")
                : result(TitleMutationStatus.STORAGE_FAILED, internal.id(), "",
                        "Could not persist tags.yml");
    }

    TitleMutationResult duplicate(String titleId) {
        TagDefinition source = catalog.byId(titleId);
        if (source == null) {
            return result(TitleMutationStatus.NOT_FOUND, titleId, "", "Unknown title");
        }
        if (catalog.isFull()) {
            return result(TitleMutationStatus.CAPACITY_REACHED, source.id(), "",
                    "The catalog reached its title limit");
        }
        TagDefinition copy = catalog.duplicate(source.id());
        return copy == null
                ? result(TitleMutationStatus.STORAGE_FAILED, source.id(), "",
                        "Could not persist tags.yml")
                : result(TitleMutationStatus.SUCCESS, copy.id(), source.id(), "");
    }

    TitleMutationResult delete(String titleId) {
        TagDefinition existing = catalog.byId(titleId);
        if (existing == null) {
            return result(TitleMutationStatus.NOT_FOUND, titleId, "", "Unknown title");
        }
        if (catalog.dependentCount(existing.id()) > 0) {
            return result(TitleMutationStatus.DEPENDENCY_CONFLICT, existing.id(), "",
                    "Other titles depend on this title");
        }
        return catalog.remove(existing.id())
                ? result(TitleMutationStatus.SUCCESS, existing.id(), "", "")
                : result(TitleMutationStatus.STORAGE_FAILED, existing.id(), "",
                        "Could not persist tags.yml");
    }

    TitleMutationResult updateCollection(TitleCollectionUpdate update) {
        if (update == null || update.id().isBlank()) {
            return result(TitleMutationStatus.INVALID_ARGUMENT, "", "",
                    "Collection update is required");
        }
        TagCollection existing = catalog.collectionById(update.id());
        if (existing == null) {
            return result(TitleMutationStatus.NOT_FOUND, update.id(), "",
                    "Unknown collection");
        }
        Material icon = Material.matchMaterial(update.icon());
        if (TagDefinition.isAir(icon)) {
            return result(TitleMutationStatus.VALIDATION_FAILED, update.id(), "",
                    "Invalid collection icon");
        }
        TagCollection changed = existing.withDisplayName(update.displayName())
                .withDescription(update.description())
                .withIcon(icon)
                .withBonusPoints(update.bonusPoints());
        if (existing.equals(changed)) {
            return result(TitleMutationStatus.NO_CHANGE, update.id(), "", "");
        }
        return catalog.updateCollection(changed)
                ? result(TitleMutationStatus.SUCCESS, update.id(), "", "")
                : result(TitleMutationStatus.STORAGE_FAILED, update.id(), "",
                        "Could not persist tags.yml");
    }

    TitleMutationResult updateSettings(TitleCatalogSettings settings) {
        if (settings == null) {
            return result(TitleMutationStatus.INVALID_ARGUMENT, "", "",
                    "Settings are required");
        }
        TitleCatalogSettings previous = new TitleCatalogSettings(
                catalog.notifyUnlock(), catalog.autoEquipFirst());
        if (previous.equals(settings)) {
            return result(TitleMutationStatus.NO_CHANGE, "", "", "");
        }
        return catalog.setSettings(settings.notifyUnlock(), settings.autoEquipFirst())
                ? result(TitleMutationStatus.SUCCESS, "", "", "")
                : result(TitleMutationStatus.STORAGE_FAILED, "", "",
                        "Could not persist tags.yml");
    }

    private static TitleMutationResult result(
            TitleMutationStatus status, String titleId,
            String previousTitleId, String message) {
        return new TitleMutationResult(status,
                TagDefinition.normalizeId(titleId),
                TagDefinition.normalizeId(previousTitleId), message);
    }
}
