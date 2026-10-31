package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.api.TitlesApi;
import dev.chatalyst.api.title.TitleCatalogSettings;
import dev.chatalyst.api.title.TitleCollectionUpdate;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleEvaluationResult;
import dev.chatalyst.api.title.TitleMutationContext;
import dev.chatalyst.api.title.TitleMutationResult;
import dev.chatalyst.api.title.TitleMutationStatus;
import dev.chatalyst.api.title.TitleProfile;
import dev.chatalyst.api.title.TitleRankingEntry;
import dev.chatalyst.api.title.TitleView;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Adapter from the stable API contract to the YAML-backed title domain. */
final class DefaultTitlesApi implements TitlesApi {

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final TagCatalog catalog;
    private final TitleApiSnapshots snapshots;
    private final TitleCatalogApiMutations catalogMutations;
    private final TitlePlayerApiOperations playerOperations;

    DefaultTitlesApi(ChatalystPlugin plugin, TagManager tags, TagCatalog catalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.snapshots = new TitleApiSnapshots(tags, catalog);
        this.catalogMutations = new TitleCatalogApiMutations(catalog);
        this.playerOperations = new TitlePlayerApiOperations(
                plugin, tags, catalog, snapshots);
        new TitleApiEventBridge(plugin, tags, catalog, snapshots);
    }

    @Override
    public List<TitleDefinition> catalog() {
        return snapshots.catalog();
    }

    @Override
    public List<TitleView> catalogFor(UUID playerId) {
        return snapshots.catalogFor(playerId);
    }

    @Override
    public boolean preview(Player player, String titleId, int seconds) {
        var nametags = plugin.getFancyNametags();
        Optional<TitleDefinition> title = snapshots.find(titleId);
        if (player == null || nametags == null || title.isEmpty()) return false;
        return nametags.preview(player, title.get().text(), Math.max(1, Math.min(30, seconds)) * 1000L);
    }

    @Override
    public Optional<TitleDefinition> find(String titleId) {
        return snapshots.find(titleId);
    }

    @Override
    public Optional<TitleDefinition> equipped(UUID playerId) {
        return snapshots.equipped(playerId);
    }

    @Override
    public boolean isUnlocked(UUID playerId, String titleId) {
        return snapshots.isUnlocked(playerId, titleId);
    }

    @Override
    public TitleProfile profile(UUID playerId) {
        return snapshots.profile(playerId);
    }

    @Override
    public List<dev.chatalyst.api.title.TitleCollection> collections(UUID playerId) {
        return snapshots.collections(playerId);
    }

    @Override
    public List<TitleRankingEntry> ranking(Collection<UUID> additionalPlayers) {
        return snapshots.ranking(additionalPlayers);
    }

    @Override
    public TitleCatalogSettings settings() {
        return snapshots.settings();
    }

    @Override
    public CompletableFuture<Optional<dev.chatalyst.api.title.TitleProgress>> progress(
            Player player, String titleId) {
        return playerOperations.progress(player, titleId);
    }

    @Override
    public CompletableFuture<TitleEvaluationResult> evaluate(Player player) {
        return playerOperations.evaluate(player);
    }

    @Override
    public TitleMutationResult equip(UUID playerId, String titleId,
                                     TitleMutationContext context) {
        return external(tags.equipDetailed(playerId, titleId, safe(context)));
    }

    @Override
    public TitleMutationResult unequip(UUID playerId, TitleMutationContext context) {
        return external(tags.equipDetailed(playerId, "", safe(context)));
    }

    @Override
    public TitleMutationResult grant(UUID playerId, String titleId,
                                     TitleMutationContext context) {
        return external(tags.grantDetailed(playerId, titleId, safe(context)));
    }

    @Override
    public TitleMutationResult revoke(UUID playerId, String titleId,
                                      TitleMutationContext context) {
        if (catalog.byId(titleId) == null) {
            return result(TitleMutationStatus.NOT_FOUND, titleId, "",
                    "Unknown title");
        }
        return external(tags.revokeDetailed(playerId, titleId, safe(context)));
    }

    @Override
    public TitleMutationResult create(TitleDefinition definition) {
        return catalogMutations.create(definition);
    }

    @Override
    public TitleMutationResult update(TitleDefinition definition) {
        return catalogMutations.update(definition);
    }

    @Override
    public TitleMutationResult duplicate(String titleId) {
        return catalogMutations.duplicate(titleId);
    }

    @Override
    public TitleMutationResult delete(String titleId) {
        return catalogMutations.delete(titleId);
    }

    @Override
    public TitleMutationResult updateCollection(TitleCollectionUpdate update) {
        return catalogMutations.updateCollection(update);
    }

    @Override
    public TitleMutationResult updateSettings(TitleCatalogSettings settings) {
        return catalogMutations.updateSettings(settings);
    }

    private static TitleMutationContext safe(TitleMutationContext context) {
        return context == null ? TitleMutationContext.api() : context;
    }

    private static TitleMutationResult external(TagMutationOutcome outcome) {
        return result(outcome.status(), outcome.titleId(),
                outcome.previousTitleId(), outcome.message());
    }

    private static TitleMutationResult result(
            TitleMutationStatus status, String titleId,
            String previousTitleId, String message) {
        return new TitleMutationResult(
                status, TagDefinition.normalizeId(titleId),
                TagDefinition.normalizeId(previousTitleId), message);
    }
}
