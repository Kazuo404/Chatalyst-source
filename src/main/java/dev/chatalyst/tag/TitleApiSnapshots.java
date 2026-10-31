package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleCatalogSettings;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleProfile;
import dev.chatalyst.api.title.TitleRankingEntry;
import dev.chatalyst.api.title.TitleView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe read projections for the public title API. */
final class TitleApiSnapshots {

    private final TagManager tags;
    private final TagCatalog catalog;

    TitleApiSnapshots(TagManager tags, TagCatalog catalog) {
        this.tags = tags;
        this.catalog = catalog;
    }

    List<TitleDefinition> catalog() {
        return catalog.all().stream().map(TitleApiMapper::external).toList();
    }

    List<TitleView> catalogFor(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        String equipped = tags.activeId(playerId);
        List<TitleView> result = new ArrayList<>();
        for (TagDefinition definition : catalog.all()) {
            if (!definition.enabled()) continue;
            boolean unlocked = tags.isUnlocked(playerId, definition);
            if (definition.hiddenUntilUnlocked() && !unlocked) continue;
            boolean concealed = definition.secret() && !unlocked;
            result.add(new TitleView(definition.id(), unlocked,
                    definition.id().equals(equipped), concealed,
                    concealed ? Optional.empty()
                            : Optional.of(TitleApiMapper.external(definition))));
        }
        return List.copyOf(result);
    }

    Optional<TitleDefinition> find(String titleId) {
        TagDefinition definition = catalog.byId(titleId);
        return definition == null ? Optional.empty()
                : Optional.of(TitleApiMapper.external(definition));
    }

    Optional<TitleDefinition> equipped(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        TagDefinition definition = tags.activeTitle(playerId);
        return definition == null ? Optional.empty()
                : Optional.of(TitleApiMapper.external(definition));
    }

    boolean isUnlocked(UUID playerId, String titleId) {
        if (playerId == null) return false;
        TagDefinition definition = catalog.byId(titleId);
        return definition != null && tags.isUnlocked(playerId, definition);
    }

    TitleProfile profile(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return new TitleProfile(playerId, tags.activeId(playerId),
                tags.achievementPoints(playerId), tags.earnedCount(playerId),
                tags.availableCount(), tags.completedCollectionCount(playerId),
                catalog.collections().size());
    }

    List<dev.chatalyst.api.title.TitleCollection> collections(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return catalog.collections().stream()
                .map(collection -> playerCollection(playerId, collection))
                .flatMap(Optional::stream)
                .toList();
    }

    List<TitleRankingEntry> ranking(Collection<UUID> additionalPlayers) {
        return tags.ranking(additionalPlayers).stream()
                .map(row -> new TitleRankingEntry(row.playerId(), row.points(),
                        row.earnedTitles(), row.totalTitles(),
                        row.completedCollections()))
                .toList();
    }

    TitleCatalogSettings settings() {
        return new TitleCatalogSettings(
                catalog.notifyUnlock(), catalog.autoEquipFirst());
    }

    dev.chatalyst.api.title.TitleCollection fullCollection(
            UUID playerId, TagCollection collection) {
        return new dev.chatalyst.api.title.TitleCollection(
                collection.id(), collection.category(), collection.displayName(),
                collection.description(), collection.icon().name(),
                collection.bonusPoints(), tags.collectionEarned(playerId, collection),
                collection.total(), tags.collectionCompleted(playerId, collection),
                collection.titles().stream().map(TagDefinition::id).toList());
    }

    private Optional<dev.chatalyst.api.title.TitleCollection> playerCollection(
            UUID playerId, TagCollection collection) {
        List<TagDefinition> visible = collection.titles().stream()
                .filter(TagDefinition::enabled)
                .filter(definition -> tags.isUnlocked(playerId, definition)
                        || (!definition.secret()
                        && !definition.hiddenUntilUnlocked()))
                .toList();
        if (visible.isEmpty()) return Optional.empty();
        int earned = (int) visible.stream()
                .filter(definition -> tags.isUnlocked(playerId, definition)).count();
        return Optional.of(new dev.chatalyst.api.title.TitleCollection(
                collection.id(), collection.category(), collection.displayName(),
                collection.description(), collection.icon().name(),
                collection.bonusPoints(), earned, visible.size(),
                tags.collectionCompleted(playerId, collection),
                visible.stream().map(TagDefinition::id).toList()));
    }
}
