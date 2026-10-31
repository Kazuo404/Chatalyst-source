package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleCatalogSettings;
import dev.chatalyst.api.title.TitleCollection;
import dev.chatalyst.api.title.TitleCollectionUpdate;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleEvaluationResult;
import dev.chatalyst.api.title.TitleMutationContext;
import dev.chatalyst.api.title.TitleMutationResult;
import dev.chatalyst.api.title.TitleProfile;
import dev.chatalyst.api.title.TitleProgress;
import dev.chatalyst.api.title.TitleRankingEntry;
import dev.chatalyst.api.title.TitleView;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public title and achievement contract.
 *
 * <p>Snapshot queries are thread-safe. Methods accepting a {@link Player} schedule their
 * work on that entity's owning thread and therefore return a future. Catalog mutations
 * synchronously persist {@code tags.yml} before returning success, and dispatch their
 * lifecycle event once the write is committed and the catalog lock is released.
 */
public interface TitlesApi {

    /** Administrative snapshot, including disabled, hidden and secret definitions. */
    List<TitleDefinition> catalog();

    /**
     * Player-safe catalog. Hidden locked entries are omitted and secret locked entries are
     * returned without their protected definition.
     */
    List<TitleView> catalogFor(UUID playerId);

    Optional<TitleDefinition> find(String titleId);

    Optional<TitleDefinition> equipped(UUID playerId);

    boolean isUnlocked(UUID playerId, String titleId);

    TitleProfile profile(UUID playerId);

    List<TitleCollection> collections(UUID playerId);

    /**
     * Leaderboard over every player with stored title data, plus the ids given here.
     *
     * <p>The cost is proportional to stored players times catalog size and runs on the
     * calling thread, so treat it as a report and not as something to call each tick.
     * Extra ids beyond the first thousand without stored data are ignored.
     */
    List<TitleRankingEntry> ranking(Collection<UUID> additionalPlayers);

    TitleCatalogSettings settings();

    CompletableFuture<Optional<TitleProgress>> progress(Player player, String titleId);

    CompletableFuture<TitleEvaluationResult> evaluate(Player player);

    /**
     * Muestra {@code titleId} unos segundos en el renglon del titulo sobre la cabeza del jugador
     * (con sus efectos animados), tenga o no el titulo. No lo equipa ni cambia nada guardado.
     *
     * @return false si el titulo no existe o el nametag flotante esta apagado
     */
    default boolean preview(Player player, String titleId, int seconds) {
        return false;
    }

    default TitleMutationResult equip(UUID playerId, String titleId) {
        return equip(playerId, titleId, TitleMutationContext.api());
    }

    TitleMutationResult equip(UUID playerId, String titleId, TitleMutationContext context);

    default TitleMutationResult unequip(UUID playerId) {
        return unequip(playerId, TitleMutationContext.api());
    }

    TitleMutationResult unequip(UUID playerId, TitleMutationContext context);

    default TitleMutationResult grant(UUID playerId, String titleId) {
        return grant(playerId, titleId, TitleMutationContext.api());
    }

    TitleMutationResult grant(UUID playerId, String titleId, TitleMutationContext context);

    default TitleMutationResult revoke(UUID playerId, String titleId) {
        return revoke(playerId, titleId, TitleMutationContext.api());
    }

    TitleMutationResult revoke(UUID playerId, String titleId, TitleMutationContext context);

    TitleMutationResult create(TitleDefinition definition);

    TitleMutationResult update(TitleDefinition definition);

    TitleMutationResult duplicate(String titleId);

    TitleMutationResult delete(String titleId);

    TitleMutationResult updateCollection(TitleCollectionUpdate update);

    TitleMutationResult updateSettings(TitleCatalogSettings settings);
}
