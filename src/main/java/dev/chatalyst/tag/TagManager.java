package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleChangeCause;
import dev.chatalyst.api.title.TitleMutationContext;
import dev.chatalyst.api.title.TitleMutationStatus;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado durable de los titulos. Conserva el prefijo/sufijo historico en {@code tags} y
 * administra por separado la seleccion y los desbloqueos del catalogo. Un titulo equipado
 * tiene prioridad, pero quitarlo restaura el valor historico sin destruirlo.
 *
 * <p>Las lecturas de {@link #prefix(UUID)} y {@link #suffix(UUID)} son seguras desde el chat
 * async: catalogo y caches se publican como snapshots inmutables/concurrentes.
 */
public final class TagManager {

    private static final char US = ''; // separador prefix/suffix (nunca llega del chat)

    /** Tope de jugadores sin datos guardados que un llamante puede sumar al ranking. */
    public static final int MAX_EXTRA_RANKING_PLAYERS = 1_000;

    private final Store legacyStore;
    private final Store selectionStore;
    private final Store unlockStore;
    private final TagCatalog catalog;
    private final Map<UUID, String[]> cache = new ConcurrentHashMap<>();
    private final Map<UUID, String> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> unlocks = new ConcurrentHashMap<>();
    private volatile TagLifecycleListener lifecycle = TagLifecycleListener.NOOP;

    public TagManager(StorageManager storage) {
        this(storage, null);
    }

    public TagManager(StorageManager storage, TagCatalog catalog) {
        this(storage.store("tags"), storage.store("tag-selections"),
                storage.store("tag-unlocks"), catalog);
    }

    /** Para tests y stores a medida: mismo comportamiento con un Store directo. */
    public TagManager(Store store) {
        this(store, null, null, null);
    }

    TagManager(Store legacyStore, Store selectionStore, Store unlockStore, TagCatalog catalog) {
        this.legacyStore = legacyStore;
        this.selectionStore = selectionStore;
        this.unlockStore = unlockStore;
        this.catalog = catalog;
        if (selectionStore != null) {
            selectionStore.addInvalidationListener(this::reloadSelections);
        }
        if (unlockStore != null) {
            unlockStore.addInvalidationListener(this::reloadUnlocks);
        }
        // El prefix/suffix heredado tambien llega cambiado desde otro servidor de la red; sin
        // esto se quedaba el viejo en este hasta un reload.
        legacyStore.addInvalidationListener(this::reloadLegacy);
    }

    public void load() {
        cache.clear();
        for (Map.Entry<String, String> e : legacyStore.all().entrySet()) {
            try {
                cache.put(UUID.fromString(e.getKey()), split(e.getValue()));
            } catch (IllegalArgumentException ignore) {
                // clave corrupta: se ignora
            }
        }
        selections.clear();
        unlocks.clear();
        if (selectionStore != null) reloadSelections(selectionStore.all().keySet());
        if (unlockStore != null) reloadUnlocks(unlockStore.all().keySet());
    }

    void setLifecycleListener(TagLifecycleListener listener) {
        lifecycle = listener == null ? TagLifecycleListener.NOOP : listener;
    }

    public String prefix(UUID id) {
        if (hiddenHere()) return "";
        TagDefinition selected = activeTitle(id);
        if (selected != null) return selected.prefix();
        String[] t = cache.get(id);
        return t != null ? t[0] : "";
    }

    public String suffix(UUID id) {
        if (hiddenHere()) return "";
        TagDefinition selected = activeTitle(id);
        if (selected != null) return selected.suffix();
        String[] t = cache.get(id);
        return t != null ? t[1] : "";
    }

    /** Titles still exist here (shared storage); this server just does not display them. */
    private boolean hiddenHere() {
        return catalog != null && !catalog.showOnThisServer();
    }

    public TagDefinition activeTitle(UUID id) {
        if (catalog == null) return null;
        String selected = selections.get(id);
        if (selected == null) return null;
        TagDefinition definition = catalog.byId(selected);
        return definition != null && definition.enabled() && isUnlocked(id, definition)
                ? definition : null;
    }

    public String activeId(UUID id) {
        TagDefinition active = activeTitle(id);
        return active == null ? "" : active.id();
    }

    public boolean equip(UUID id, String titleId) {
        return equipDetailed(id, titleId,
                TitleMutationContext.system(TitleChangeCause.SYSTEM)).successful();
    }

    public boolean equip(UUID id, String titleId, TitleMutationContext context) {
        return equipDetailed(id, titleId, context).successful();
    }

    TagMutationOutcome equipDetailed(UUID id, String titleId,
                                     TitleMutationContext context) {
        if (selectionStore == null) {
            return outcome(TitleMutationStatus.STORAGE_FAILED, titleId, "",
                    "Title selection storage is unavailable");
        }
        if (id == null) {
            return outcome(TitleMutationStatus.INVALID_ARGUMENT, titleId, "",
                    "Player id is required");
        }
        String normalized = TagDefinition.normalizeId(titleId);
        String previousId = selections.getOrDefault(id, "");
        if (normalized.equals(previousId)) {
            return outcome(TitleMutationStatus.NO_CHANGE, normalized, previousId, "");
        }
        TagDefinition next = null;
        if (!normalized.isEmpty()) {
            next = catalog == null ? null : catalog.byId(normalized);
            if (next == null) {
                return outcome(TitleMutationStatus.NOT_FOUND, normalized, previousId,
                        "Unknown title");
            }
            if (!next.enabled()) {
                return outcome(TitleMutationStatus.DISABLED, normalized, previousId,
                        "Title is disabled");
            }
            if (!isUnlocked(id, next)) {
                return outcome(TitleMutationStatus.LOCKED, normalized, previousId,
                        "Title is locked");
            }
        }
        String key = id.toString();
        boolean persisted = normalized.isEmpty()
                ? selectionStore.removeDurably(key)
                : selectionStore.putDurably(key, normalized);
        if (!persisted) {
            reloadSelection(id);
            return outcome(TitleMutationStatus.STORAGE_FAILED, normalized, previousId,
                    "Could not persist title selection");
        }
        TagDefinition previous = catalog == null ? null : catalog.byId(previousId);
        reloadSelection(id);
        TitleMutationContext safe = safe(context, TitleChangeCause.SYSTEM);
        if (normalized.isEmpty()) {
            lifecycle.unequipped(id, previousId, previous, safe);
        } else {
            lifecycle.equipped(id, previousId, previous, next, safe);
        }
        return outcome(TitleMutationStatus.SUCCESS, normalized, previousId, "");
    }

    public boolean isUnlocked(UUID id, TagDefinition definition) {
        if (id == null || definition == null) return false;
        if (definition.requirements().isFree()) {
            return definition.isAvailable(System.currentTimeMillis());
        }
        return isPersistentlyUnlocked(id, definition);
    }

    public boolean isPersistentlyUnlocked(UUID id, String titleId) {
        if (catalog != null) {
            TagDefinition definition = catalog.byId(titleId);
            if (definition != null) return isPersistentlyUnlocked(id, definition);
        }
        return unlocks.getOrDefault(id, Set.of()).contains(normalizeUnlockToken(titleId));
    }

    public boolean isPersistentlyUnlocked(UUID id, TagDefinition definition) {
        return id != null && definition != null
                && unlocks.getOrDefault(id, Set.of()).contains(definition.unlockToken());
    }

    /**
     * Evalua las reglas del catalogo y persiste las nuevas obtenciones. Solo debe llamarse
     * desde el hilo de entidad del jugador porque consulta sus estadisticas Bukkit.
     */
    public List<TagDefinition> refreshUnlocks(Player player) {
        if (catalog == null || unlockStore == null || player == null) return List.of();
        List<TagDefinition> obtained = new ArrayList<>();
        List<TagDefinition> definitions = catalog.all();
        boolean changed;
        do {
            changed = false;
            for (TagDefinition definition : definitions) {
                if (!definition.enabled()
                        || !definition.isAvailable(System.currentTimeMillis())
                        || definition.requirements().isFree()
                        || isUnlocked(player.getUniqueId(), definition)) {
                    continue;
                }
                if (definition.requirements().satisfied(player, target -> {
                    TagDefinition dependency = catalog.byId(target);
                    return dependency != null
                            && isUnlocked(player.getUniqueId(), dependency);
                }) && grantDetailed(player.getUniqueId(), definition.id(),
                        TitleMutationContext.system(TitleChangeCause.REQUIREMENT)).status()
                        == TitleMutationStatus.SUCCESS) {
                    obtained.add(definition);
                    changed = true;
                }
            }
        } while (changed);
        if (!obtained.isEmpty() && catalog.autoEquipFirst()
                && activeTitle(player.getUniqueId()) == null) {
            equipDetailed(player.getUniqueId(), obtained.getFirst().id(),
                    TitleMutationContext.system(TitleChangeCause.AUTO_EQUIP));
        }
        return List.copyOf(obtained);
    }

    public boolean grant(UUID id, String titleId) {
        TitleMutationStatus status = grantDetailed(id, titleId,
                TitleMutationContext.system(TitleChangeCause.SYSTEM)).status();
        return status == TitleMutationStatus.SUCCESS
                || status == TitleMutationStatus.ALREADY_UNLOCKED;
    }

    public boolean grant(UUID id, String titleId, TitleMutationContext context) {
        TitleMutationStatus status = grantDetailed(id, titleId, context).status();
        return status == TitleMutationStatus.SUCCESS
                || status == TitleMutationStatus.ALREADY_UNLOCKED;
    }

    TagMutationOutcome grantDetailed(UUID id, String titleId,
                                     TitleMutationContext context) {
        if (unlockStore == null || id == null || catalog == null) {
            return outcome(id == null ? TitleMutationStatus.INVALID_ARGUMENT
                            : TitleMutationStatus.STORAGE_FAILED,
                    titleId, "", id == null
                            ? "Player id is required" : "Unlock storage is unavailable");
        }
        String normalized = TagDefinition.normalizeId(titleId);
        TagDefinition definition = catalog.byId(normalized);
        if (normalized.isEmpty() || definition == null) {
            return outcome(TitleMutationStatus.NOT_FOUND, normalized, "",
                    "Unknown title");
        }
        Set<String> completedBefore = completedCollectionIds(id);
        String unlockToken = definition.unlockToken();
        String key = id.toString();
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = unlockStore.get(key);
            Set<String> current = decodeUnlocks(observed);
            if (current.contains(unlockToken)) {
                reloadUnlock(id);
                return outcome(TitleMutationStatus.ALREADY_UNLOCKED,
                        normalized, "", "Title is already unlocked");
            }
            current.add(unlockToken);
            if (!unlockStore.compareAndPutDurably(key, observed, encodeUnlocks(current))) continue;
            reloadUnlock(id);
            TitleMutationContext safe = safe(context, TitleChangeCause.SYSTEM);
            lifecycle.unlocked(id, definition, safe);
            for (TagCollection collection : catalog.collections()) {
                if (!completedBefore.contains(collection.id())
                        && collectionCompleted(id, collection)) {
                    lifecycle.collectionCompleted(id, collection, safe);
                }
            }
            return outcome(TitleMutationStatus.SUCCESS, normalized, "", "");
        }
        reloadUnlock(id);
        return outcome(TitleMutationStatus.STORAGE_FAILED, normalized, "",
                "Could not persist title unlock");
    }

    public boolean revoke(UUID id, String titleId) {
        TitleMutationStatus status = revokeDetailed(id, titleId,
                TitleMutationContext.system(TitleChangeCause.SYSTEM)).status();
        return status == TitleMutationStatus.SUCCESS
                || status == TitleMutationStatus.NOT_UNLOCKED;
    }

    public boolean revoke(UUID id, String titleId, TitleMutationContext context) {
        TitleMutationStatus status = revokeDetailed(id, titleId, context).status();
        return status == TitleMutationStatus.SUCCESS
                || status == TitleMutationStatus.NOT_UNLOCKED;
    }

    TagMutationOutcome revokeDetailed(UUID id, String titleId,
                                      TitleMutationContext context) {
        if (unlockStore == null) {
            return outcome(TitleMutationStatus.STORAGE_FAILED, titleId, "",
                    "Unlock storage is unavailable");
        }
        if (id == null) {
            return outcome(TitleMutationStatus.INVALID_ARGUMENT, titleId, "",
                    "Player id is required");
        }
        String normalized = TagDefinition.normalizeId(titleId);
        TagDefinition definition = catalog == null ? null : catalog.byId(normalized);
        if (definition == null) {
            return outcome(TitleMutationStatus.NOT_FOUND, normalized, "",
                    "Unknown title");
        }
        String unlockToken = definition.unlockToken();
        String key = id.toString();
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = unlockStore.get(key);
            Set<String> current = decodeUnlocks(observed);
            if (!current.remove(unlockToken)) {
                reloadUnlock(id);
                return outcome(TitleMutationStatus.NOT_UNLOCKED, normalized, "",
                        "Title is not unlocked");
            }
            boolean persisted = current.isEmpty()
                    ? unlockStore.compareAndRemoveDurably(key, observed)
                    : unlockStore.compareAndPutDurably(key, observed, encodeUnlocks(current));
            if (!persisted) continue;
            reloadUnlock(id);
            TitleMutationContext safe = safe(context, TitleChangeCause.SYSTEM);
            lifecycle.revoked(id, definition, safe);
            if (normalized.equals(selections.get(id))) {
                equipDetailed(id, "", safe);
            }
            return outcome(TitleMutationStatus.SUCCESS, normalized, "", "");
        }
        reloadUnlock(id);
        return outcome(TitleMutationStatus.STORAGE_FAILED, normalized, "",
                "Could not persist title revocation");
    }

    /** Guarda prefix o suffix ("" = quitar) con CAS durable y actualiza el cache al final. */
    public boolean set(UUID id, boolean isPrefix, String value) {
        String key = id.toString();
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = legacyStore.get(key);
            String[] current = observed == null ? new String[]{"", ""} : split(observed);
            String[] next = {isPrefix ? value : current[0], isPrefix ? current[1] : value};
            boolean empty = next[0].isEmpty() && next[1].isEmpty();
            boolean persisted;
            if (empty) {
                persisted = observed == null || legacyStore.compareAndRemoveDurably(key, observed);
            } else {
                persisted = legacyStore.compareAndPutDurably(key, observed, next[0] + US + next[1]);
            }
            if (!persisted) continue;
            reloadLegacy(id);
            return true;
        }
        reloadLegacy(id);
        return false;
    }

    public TagCatalog catalog() {
        return catalog;
    }

    public int equippedCount(String titleId) {
        String normalized = TagDefinition.normalizeId(titleId);
        int count = 0;
        for (String selected : selections.values()) {
            if (normalized.equals(selected)) count++;
        }
        return count;
    }

    public int unlockedCount(String titleId) {
        String normalized = TagDefinition.normalizeId(titleId);
        TagDefinition definition = catalog == null ? null : catalog.byId(normalized);
        String token = definition == null ? normalized : definition.unlockToken();
        int count = 0;
        for (Set<String> values : unlocks.values()) {
            if (values.contains(token)) count++;
        }
        return count;
    }

    public long achievementPoints(UUID id) {
        if (catalog == null || id == null) return 0L;
        long total = 0L;
        for (TagDefinition definition : catalog.all()) {
            if (isUnlocked(id, definition)) total += definition.points();
        }
        for (TagCollection collection : catalog.collections()) {
            if (collection.completed(definition -> isUnlocked(id, definition))) {
                total += collection.bonusPoints();
            }
        }
        return total;
    }

    public int collectionEarned(UUID id, TagCollection collection) {
        if (id == null || collection == null) return 0;
        return collection.earned(definition -> isUnlocked(id, definition));
    }

    public boolean collectionCompleted(UUID id, TagCollection collection) {
        return id != null && collection != null
                && collection.completed(definition -> isUnlocked(id, definition));
    }

    public int completedCollectionCount(UUID id) {
        if (catalog == null || id == null) return 0;
        int completed = 0;
        for (TagCollection collection : catalog.collections()) {
            if (collectionCompleted(id, collection)) completed++;
        }
        return completed;
    }

    public int earnedCount(UUID id) {
        if (catalog == null || id == null) return 0;
        int count = 0;
        for (TagDefinition definition : catalog.all()) {
            if (definition.enabled() && isUnlocked(id, definition)) count++;
        }
        return count;
    }

    public int availableCount() {
        if (catalog == null) return 0;
        int count = 0;
        for (TagDefinition definition : catalog.all()) {
            if (definition.enabled()) count++;
        }
        return count;
    }

    public Set<UUID> knownPlayers() {
        LinkedHashSet<UUID> known = new LinkedHashSet<>();
        known.addAll(unlocks.keySet());
        known.addAll(selections.keySet());
        known.addAll(cache.keySet());
        return Set.copyOf(known);
    }

    public List<TagRank> ranking(Collection<UUID> additionalPlayers) {
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(knownPlayers());
        if (additionalPlayers != null) {
            // Los que ya tienen datos guardados entran siempre; el resto es una lista que
            // llega de fuera, asi que se acota antes de multiplicarla por el catalogo.
            int room = MAX_EXTRA_RANKING_PLAYERS;
            for (UUID id : additionalPlayers) {
                if (id == null) continue;
                if (candidates.add(id) && --room <= 0) break;
            }
        }
        int totalTitles = availableCount();
        List<TagRank> ranking = new ArrayList<>(candidates.size());
        for (UUID id : candidates) {
            ranking.add(rank(id, totalTitles));
        }
        ranking.sort(java.util.Comparator.comparingLong(TagRank::points).reversed()
                .thenComparing(java.util.Comparator.comparingInt(
                        TagRank::earnedTitles).reversed())
                .thenComparing(rank -> rank.playerId().toString()));
        return List.copyOf(ranking);
    }

    private TagRank rank(UUID id, int totalTitles) {
        if (catalog == null) {
            return new TagRank(id, 0L, 0, totalTitles, 0);
        }
        long points = 0L;
        int earned = 0;
        for (TagDefinition definition : catalog.all()) {
            if (!isUnlocked(id, definition)) continue;
            points += definition.points();
            if (definition.enabled()) earned++;
        }
        int completed = 0;
        for (TagCollection collection : catalog.collections()) {
            if (!collectionCompleted(id, collection)) continue;
            completed++;
            points += collection.bonusPoints();
        }
        return new TagRank(id, points, earned, totalTitles, completed);
    }

    private void reloadSelections(Collection<String> keys) {
        if (selectionStore == null) return;
        for (String key : keys) {
            try {
                reloadSelection(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // fila corrupta
            }
        }
    }

    /**
     * Las caches de aqui se rellenan SIEMPRE leyendo el Store, dentro de un compute atomico
     * por jugador. Antes cada mutacion publicaba su propia copia tras el CAS: dos concesiones
     * a la vez ({A} y luego {A,B}) podian publicarse al reves y dejar B bloqueado en memoria
     * aunque estuviera guardado. Leyendo dentro del compute, la ultima publicacion siempre ve
     * el Store al menos tan nuevo como cualquier otra.
     */
    private void reloadSelection(UUID id) {
        selections.compute(id, (key, ignored) -> {
            String normalized = TagDefinition.normalizeId(selectionStore.get(key.toString()));
            return normalized.isEmpty() ? null : normalized;
        });
    }

    private void reloadLegacy(Collection<String> keys) {
        for (String key : keys) {
            try {
                reloadLegacy(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // fila corrupta
            }
        }
    }

    private void reloadLegacy(UUID id) {
        cache.compute(id, (key, ignored) -> {
            String stored = legacyStore.get(key.toString());
            return stored == null ? null : split(stored);
        });
    }

    private void reloadUnlocks(Collection<String> keys) {
        if (unlockStore == null) return;
        for (String key : keys) {
            try {
                reloadUnlock(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // fila corrupta
            }
        }
    }

    private void reloadUnlock(UUID id) {
        unlocks.compute(id, (key, ignored) -> {
            Set<String> decoded = decodeUnlocks(unlockStore.get(key.toString()));
            return decoded.isEmpty() ? null : immutable(decoded);
        });
    }

    static Set<String> decodeUnlocks(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashSet<>();
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = normalizeUnlockToken(token);
            if (!normalized.isEmpty()) values.add(normalized);
        }
        return values;
    }

    static String encodeUnlocks(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    static String normalizeUnlockToken(String raw) {
        if (raw == null) return "";
        String[] parts = raw.trim().split("~", 2);
        String title = TagDefinition.normalizeId(parts[0]);
        if (title.isEmpty()) return "";
        if (parts.length == 1) return title;
        String season = TagSeason.normalizeId(parts[1]);
        return season.isEmpty() ? title : title + "~" + season;
    }

    private static Set<String> immutable(Set<String> values) {
        return Collections.unmodifiableSet(new HashSet<>(values));
    }

    static String[] split(String raw) {
        int sep = raw.indexOf(US);
        if (sep < 0) return new String[]{raw, ""};
        return new String[]{raw.substring(0, sep), raw.substring(sep + 1)};
    }

    private Set<String> completedCollectionIds(UUID playerId) {
        if (catalog == null || playerId == null) return Set.of();
        Set<String> completed = new LinkedHashSet<>();
        for (TagCollection collection : catalog.collections()) {
            if (collectionCompleted(playerId, collection)) {
                completed.add(collection.id());
            }
        }
        return Set.copyOf(completed);
    }

    private static TitleMutationContext safe(TitleMutationContext context,
                                             TitleChangeCause fallback) {
        return context == null ? TitleMutationContext.system(fallback) : context;
    }

    private static TagMutationOutcome outcome(
            TitleMutationStatus status, String titleId,
            String previousTitleId, String message) {
        return new TagMutationOutcome(status,
                TagDefinition.normalizeId(titleId),
                TagDefinition.normalizeId(previousTitleId), message);
    }

    public record TagRank(UUID playerId, long points, int earnedTitles,
                          int totalTitles, int completedCollections) {
    }
}
