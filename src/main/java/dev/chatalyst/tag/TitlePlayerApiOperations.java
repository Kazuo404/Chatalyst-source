package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.api.title.TitleEvaluationResult;
import dev.chatalyst.api.title.TitleMutationStatus;
import dev.chatalyst.api.title.TitleProgress;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Entity-scheduled progress reads and unlock evaluation. */
final class TitlePlayerApiOperations {

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final TagCatalog catalog;
    private final TitleApiSnapshots snapshots;

    TitlePlayerApiOperations(ChatalystPlugin plugin, TagManager tags,
                             TagCatalog catalog, TitleApiSnapshots snapshots) {
        this.plugin = plugin;
        this.tags = tags;
        this.catalog = catalog;
        this.snapshots = snapshots;
    }

    CompletableFuture<Optional<TitleProgress>> progress(
            Player player, String titleId) {
        CompletableFuture<Optional<TitleProgress>> future = new CompletableFuture<>();
        if (player == null || !player.isOnline()) {
            future.complete(Optional.empty());
            return future;
        }
        boolean accepted = FoliaTasks.entity(plugin, player, current -> {
            TagDefinition definition = catalog.byId(titleId);
            boolean protectedEntry = definition != null
                    && (definition.secret() || definition.hiddenUntilUnlocked())
                    && !tags.isUnlocked(current.getUniqueId(), definition);
            future.complete(definition == null || protectedEntry
                    ? Optional.empty()
                    : Optional.of(TitleApiMapper.progress(tags, current, definition)));
        }, () -> future.complete(Optional.empty()));
        if (!accepted) future.complete(Optional.empty());
        return future;
    }

    CompletableFuture<TitleEvaluationResult> evaluate(Player player) {
        CompletableFuture<TitleEvaluationResult> future = new CompletableFuture<>();
        if (player == null || !player.isOnline()) {
            future.complete(new TitleEvaluationResult(
                    TitleMutationStatus.PLAYER_OFFLINE, List.of(),
                    player == null ? Optional.empty()
                            : Optional.of(snapshots.profile(player.getUniqueId())),
                    "Player is offline"));
            return future;
        }
        boolean accepted = FoliaTasks.entity(plugin, player, current -> {
            var unlocked = TagUnlockProcessor.refresh(plugin, tags, current).stream()
                    .map(TitleApiMapper::external).toList();
            future.complete(new TitleEvaluationResult(
                    unlocked.isEmpty() ? TitleMutationStatus.NO_CHANGE
                            : TitleMutationStatus.SUCCESS,
                    unlocked, Optional.of(snapshots.profile(current.getUniqueId())), ""));
        }, () -> future.complete(new TitleEvaluationResult(
                TitleMutationStatus.PLAYER_OFFLINE, List.of(),
                Optional.of(snapshots.profile(player.getUniqueId())),
                "Player retired before evaluation")));
        if (!accepted) {
            future.complete(new TitleEvaluationResult(
                    TitleMutationStatus.PLAYER_OFFLINE, List.of(),
                    Optional.of(snapshots.profile(player.getUniqueId())),
                    "Entity scheduler rejected evaluation"));
        }
        return future;
    }
}
