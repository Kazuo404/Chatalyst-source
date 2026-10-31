# Titles and achievements API

## Contract and installation

Chatalyst API v1 is a stable boundary over the internal `TagManager`, `TagCatalog`, YAML files
and GUIs. The regular build produces:

- `Chatalyst-1.0.0.jar`: complete runtime plugin.
- `Chatalyst-API-1.0.0.jar`: small compile-time contract under `dev.chatalyst.api`.

Add the API jar as `compileOnly`/`provided`; never place it in the server's plugins directory.
Declare Chatalyst under `depend` when it is required, or `softdepend` when the integration can
disable itself.

```java
ChatalystApi api = ChatalystApi.get()
        .orElseThrow(() -> new IllegalStateException("Chatalyst is unavailable"));
TitlesApi titles = api.titles();
```

The service is registered after the catalog and player stores load and is removed before
shutdown. `ChatalystApi.API_VERSION` versions this public contract independently from the plugin
release.

## Immutable reads and privacy

The title service exposes immutable values:

| Method | Result |
|---|---|
| `catalog()` | Complete administrative catalog, including disabled, hidden and secret definitions |
| `catalogFor(uuid)` | Player-safe entries: hidden locked titles are omitted and secret locked titles carry no definition |
| `find(id)` / `equipped(uuid)` | Optional immutable definition |
| `isUnlocked(uuid, id)` | Current permanent, seasonal or free-title access |
| `profile(uuid)` | Equipped ID, derived points, earned/available titles and collection totals |
| `collections(uuid)` | Collection presentation, membership and player completion |
| `ranking(additionalPlayers)` | Deterministic points/earned/UUID ranking snapshot |
| `settings()` | Unlock-notification and first-title auto-equip switches |

`catalog()` is intentionally administrative and therefore contains secret configuration. Use
`catalogFor()` for any player-facing integration. PlaceholderAPI exposes only an already-unlocked
equipped title and hides all title/progression values during anonymous mode.

## Progress and evaluation

Minecraft statistics, advancements, inventory and permissions must be read from the player's
owning entity thread. For that reason these methods return futures:

```java
titles.progress(player, "hunter").thenAccept(progress ->
        progress.ifPresent(value ->
                getLogger().info(value.titleId() + ": " + value.satisfied())));

titles.evaluate(player).thenAccept(result -> {
    if (result.successful()) {
        getLogger().info("New titles: " + result.unlocked().size());
    }
});
```

Chatalyst schedules both operations through the entity scheduler on Paper, Purpur and Folia.
`evaluate` uses the normal unlock processor, so newly earned titles receive their configured
Minecraft toast, chat message, sound and command rewards. An offline or retired player produces
`PLAYER_OFFLINE`; it never falls back to an unsafe thread.

## Typed state mutations

`equip`, `unequip`, `grant` and `revoke` return `TitleMutationResult`. The status distinguishes:

- `SUCCESS` and idempotent `NO_CHANGE`.
- `NOT_FOUND`, `DISABLED`, `LOCKED`, `ALREADY_UNLOCKED` and `NOT_UNLOCKED`.
- `INVALID_ARGUMENT`, `VALIDATION_FAILED`, `DEPENDENCY_CONFLICT` and `CAPACITY_REACHED`.
- `STORAGE_FAILED` and `PLAYER_OFFLINE`.

Optional `TitleMutationContext` records `PLAYER`, `ADMIN`, `REQUIREMENT`, `AUTO_EQUIP`, `API` or
`SYSTEM`, plus an actor and bounded reason. The context is copied into lifecycle events.

```java
TitleMutationContext context = TitleMutationContext.admin(
        moderator.getUniqueId(), moderator.getName(), "Season prize");
TitleMutationResult result = titles.grant(player.getUniqueId(), "champion", context);
if (!result.successful()) {
    getLogger().warning(result.status() + ": " + result.message());
}
```

Unlock and selection state is persisted before an operation returns `SUCCESS`. Granting through
the state API follows administrative semantics and does not execute arbitrary reward commands;
use `evaluate(player)` for normal requirement acquisition and its complete presentation/reward
pipeline.

## Validated catalog administration

Use `TitleDefinition.builder(id)` and pass the immutable result to `create` or `update`.
Definitions cover:

- Text, description, material, `LEFT`/`RIGHT` position and spacing.
- Enabled, hidden and secret state, category, rarity, sort order and points.
- Season ID/start/end.
- `ALL`/`ANY` requirement groups and every supported acquisition type.
- Ordered console reward commands.
- Toast text/icon/frame, chat message and sound presentation.

The service also supports `duplicate`, dependency-safe `delete`, `updateCollection` and an atomic
two-switch `updateSettings`. Chatalyst validates IDs, materials, item kinds, rule targets,
amount bounds, season windows, missing dependencies, dependency cycles and the 500-title limit
before writing. `tags.yml` is atomically replaced before the new snapshot becomes visible.

## Durable lifecycle events

All title events are in `dev.chatalyst.api`:

| Event | Guaranteed timing |
|---|---|
| `TitleUnlockEvent` | A new unlock is durable |
| `TitleEquipEvent` | A valid unlocked selection is durable |
| `TitleUnequipEvent` | A selection removal is durable |
| `TitleRevokeEvent` | An existing unlock removal is durable |
| `TitleCollectionCompleteEvent` | A new unlock completes a derived collection |
| `TitleCatalogChangeEvent` | GUI or API create/update/duplicate/delete is atomically written |

No lifecycle event is fired for a no-op or failed storage write. Revoking an equipped title emits
the revoke event and then the automatic unequip event. Events are synchronous with the completed
operation, but their Bukkit asynchronous flag follows the calling context; handlers must obey
the event thread and schedule entity/world access correctly.

Existing `ChannelMessageEvent`, `ChannelSwitchEvent`, `RuleMatchEvent` and `RuleReplaceEvent`
remain source-compatible.

## PlaceholderAPI values

The `%chatalyst_...%` expansion now includes:

| Group | Placeholders |
|---|---|
| Equipped identity | `title_id`, `title_name`, `title_text` |
| Presentation | `title_prefix`, `title_suffix`, `title_position` |
| Metadata | `title_rarity`, `title_category`, `title_season`, `title_points` |
| Achievement profile | `achievement_points`, `achievements_earned`, `achievements_total`, `achievements_percent` |
| Collections | `collections_completed`, `collections_total` |

Prefix/suffix output includes the configured side, `<reset>` and spacing. An unequipped player
returns empty title metadata and zero title points. Every value in this table becomes empty while
the player is anonymous.

The existing channel, nickname, moderation, AFK, rank, anonymous and marriage placeholders remain
compatible.

