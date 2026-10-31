# Titles and achievements

`/tag` opens the player catalog. It shows each visible title, its rules, current progress,
category, rarity, achievement points and a preview. The bottom profile summarizes earned
achievements, completion and score. Clicking it opens collections and the global ranking.
Players can filter by category and status, sort by priority,
rarity, points or name, and equip any unlocked title with one click. `Unequip title`
preserves all unlocks.

## Commands and permissions

| Action | Permission | Behavior |
|---|---|---|
| `/tag` | `chatalyst.tag` | Opens the catalog and evaluates current progress |
| `/tag <id>` | `chatalyst.tag` | Evaluates progress and equips an unlocked title |
| `/tag off` or `/tag reset` | `chatalyst.tag` | Unequips the catalog title without deleting achievements |
| `/tag admin` | `chatalyst.tag.admin` | Opens full administration; includes `chatalyst.tag` |
| `/tag prefix\|suffix <text\|off>` | `chatalyst.tag` | Compatibility with legacy personal tags |
| `/tag prefix\|suffix <player> <text\|off>` | `chatalyst.tag.others` | Changes another online player's legacy tag |

`chatalyst.tag`, `chatalyst.tag.color`, `chatalyst.tag.others` and
`chatalyst.tag.admin` default to operators. `chatalyst.tag.color` enables MiniMessage in
the legacy commands; catalog text is authored by administrators and is always sanitized
before it is stored.

## Player experience

The catalog displays up to 45 titles per page. It shows only enabled definitions. **Hidden
until unlocked** removes a title completely until it is earned, while a **Secret**
achievement keeps a `???` card without revealing its name, description, icon, rarity,
category or requirements.

- Left and right click on **Category**, **Status** and **Sort** cycles options in both
  directions.
- Status choices are all, unlocked and locked.
- Sort choices are priority, rarity, points and name.
- Each card shows a name preview, `LEFT`/`RIGHT` side, spacing, rarity, category, points,
  season, requirements and numeric progress.
- The profile shows earned titles, enabled total, percentage, points and access to
  collections.
- Collections lead to the global ranking and allow an earned title to be equipped from its
  detail view.

Opening the catalog or attempting to equip forces a current evaluation before deciding that
a title is locked. Tab completion does not disclose secret or hidden IDs the player has not
earned.

## GUI administration

Open `/tag admin` or `/chatalyst gui` → **Content studio** →
**Titles and requirements**. The interface can:

- create, duplicate, edit, enable, hide and delete titles;
- change the name, MiniMessage text, description and icon;
- place each title on `LEFT` or `RIGHT`;
- configure 0–8 spaces from the player name (the default is 1);
- organize titles by category, rarity and sort priority;
- assign points, mark secret achievements and schedule complete seasons;
- customize collections, their icons and completion bonuses;
- combine up to 8 rules using `ALL` or `ANY`;
- create progression chains that require another title first;
- detect mined blocks, crafted/found/owned items, fishing, breeding and Minecraft
  advancements;
- design the native achievement text, description, icon, frame, chat and sound;
- configure up to 8 console-command rewards;
- grant or revoke a title from an online-player list or by finding any known player;
- filter the administrative catalog by category and status;
- toggle unlock notifications and first-title auto-equip.

New titles and copies are created **disabled**, so they can be completed before publication.
Left and right clicks cycle position, spacing, rarity, frame and filters in opposite
directions; `Shift + click` opens collections from the category filter. Deletion and
sensitive reset actions require two clicks. A title that is still required by another
title cannot be deleted.

Text fields are captured through chat and never broadcast. A blank entry, `cancel` or
`cancelar` returns without changes; optional fields also accept the clear word shown by the
GUI. Every catalog mutation is saved atomically to `tags.yml` before it appears as applied.

## Requirements

| Type | Meaning |
|---|---|
| `NONE` | Available to everyone |
| `MOB_KILLS` | `Target: ANY` or an entity (`ZOMBIE`, `SKELETON`, etc.) plus an amount |
| `PLAYER_KILLS` | Number of defeated players |
| `PLAYTIME_MINUTES` | Minutes played |
| `BLOCKS_MINED` | Amount mined of the configured Material |
| `ITEMS_CRAFTED` | Amount crafted of the configured Material |
| `ITEMS_PICKED_UP` | Historical amount picked up/found of the Material |
| `ITEMS_OWNED` | Amount currently present in the inventory |
| `FISH_CAUGHT` | Fish caught |
| `ANIMALS_BRED` | Animals bred |
| `ADVANCEMENT` | Vanilla or custom advancement (`namespace:path`) |
| `PERMISSION` | Permission stored in `Target` |
| `TITLE_UNLOCKED` | ID of another title that must be earned first |
| `MANUAL` | Administrator grant only |

A title with no rules is free. With multiple rules, `ALL` requires every rule and `ANY`
accepts the first satisfied rule. Statistic rules use Minecraft's vanilla counters. Once
met, the unlock is stored in the configured backend and remains earned if the requirement
is changed later.

Chains settle in the same evaluation: earning a prerequisite can immediately award later
achievements whose other rules are already met. The editor rejects missing IDs and direct or
transitive cycles.

### When evaluation runs

Chatalyst listens for statistic increments, item pickup, inventory click and drag events,
and completed advancements. It defers the check by one tick to read Paper's committed value
and coalesces repeated events for the same player. Every online player also receives a
safety sweep every 1,200 ticks (about 60 seconds at 20 TPS), recovering changes without a
direct event such as permissions, playtime, inventories mutated by another plugin or a hot
enable.

`ITEMS_PICKED_UP` is historical because it uses the vanilla statistic; `ITEMS_OWNED` is a
snapshot of the current inventory. Numeric amounts accept `1` through `1,000,000,000`. A
`NONE` rule makes an `ANY` group free; a `MANUAL` rule never satisfies automatically.

## Points, secrets and seasons

Each rarity supplies an initial point value (`5`, `10`, `25`, `50`, `100`, `250`), while the
field remains customizable from `0` to `1,000,000`. A profile total is derived from the current
catalog and unlocks, so point edits and revocations cannot create counter drift.

A **secret** achievement appears as `???` and discloses no name, description, icon, rarity,
category or rules before it is earned. **Hidden until unlocked** is stricter and removes the
entry from the catalog.

The **Season** panel configures:

- a season ID;
- inclusive start and exclusive end;
- server-local `YYYY-MM-DD HH:MM` dates or ISO-8601 instants;
- open bounds by entering `off`.

Changing the ID creates a new unlock key and resets that season without deleting prior
history. Locked achievements are only obtainable during the active window. An already-earned
non-free seasonal title remains equipable after the season ends.
Free titles do not create an unlock row: they are available while their season is active.

## Collections and ranking

Every catalog category automatically becomes a collection. This avoids maintaining two
membership lists: move a title by changing its category. From **Manage titles**, use
`Shift + click` on the category filter to open collection management. Staff can customize:

- visible name;
- description;
- icon;
- extra points for completing every title.

Disabled titles do not count toward the total and cannot block completion. Secret and hidden
titles do count while preserving their disclosure rules. Collection cards show progress, base
points and bonus; earned titles can also be equipped from the detail view.
Completing a collection shows a native challenge toast, chat notice and sound. The transition
is compared before and after the unlock so ordinary evaluations do not repeat it.

The ranking compares total points, titles, completion percentage and completed collections.
It is derived from the catalog and durable unlocks, including online players, without storing
a duplicate counter that could become stale after point or season edits. It sorts by points,
then earned title count, then UUID for a deterministic result.

## Achievement presentation

Every title can display Minecraft's real advancement toast. The **Achievement presentation**
panel configures:

- whether the toast is enabled;
- MiniMessage title and description;
- any Material as its icon;
- a `TASK`, `GOAL` or `CHALLENGE` frame;
- translated default chat text or a fully custom message;
- sound key, volume and pitch;
- a private test that grants nothing and executes no rewards.

Text supports `{player}`, `{uuid}`, `{title}`, `{title_id}`, `{category}`, `{rarity}`,
`{points}` and `{season}`.
Colors and decorations are serialized into the advancement JSON component; click and hover
actions are removed. The global unlock-notification setting acts as the master switch for
toast, chat and sound.

With PacketEvents installed, the temporary advancement is sent only to the client earning the
achievement: it does not mutate vanilla progress or the server's global registry. Without
PacketEvents, Chatalyst uses bounded, reusable hidden advancements and clears their progress
after displaying the toast. The fallback reuses at most 2,048 distinct presentations during
the process lifetime. Both paths preserve the GUI-authored text, icon and frame.

## Rewards

An automatic unlock can run up to 8 console commands in order. Each command supports up to
256 characters, is stored without an initial `/`, and accepts `{player}`, `{uuid}`,
`{title_id}`, `{title_name}`, `{points}` and `{season}`. The unlock is persisted first and
rewards are then scheduled on the Folia-safe global scheduler. One command failure is logged
without preventing later commands from being attempted.

Manual grants intentionally do not run the granted title's rewards, preventing revoke/grant
farming. They may still complete a `TITLE_UNLOCKED` chain; titles automatically earned
through that chain keep their own rewards.

## Scale and migration

The catalog supports up to 500 titles. Each title is bounded to 8 rules and 8 rewards
to keep the GUI and periodic evaluation predictable. Old files containing a singular
`Requirement` section are read and migrated on their next save.

| Field | Enforced limit |
|---|---:|
| Title ID | 48 normalized characters |
| Display name | 48 characters |
| Visible text | 64 plain-text characters |
| Description | 256 characters |
| Category | 32 characters |
| Sort priority | `-10,000` to `10,000` |
| Spacing | `0` to `8`, default `1` |
| Season ID | 32 normalized characters |
| Toast title / description | 64 / 128 characters |
| Achievement chat message | 256 characters |
| Volume / pitch | `0.0–10.0` / `0.5–2.0` |
| Collection name / description | 48 / 160 characters |

## Persistence, network and reload

Definitions, collections and global settings live in `plugins/Chatalyst/tags.yml`. The
equipped selection and unlocks use the configured backend's `tag-selections` and
`tag-unlocks` stores; the legacy prefix/suffix remains in `tags`. YAML, SQLite and MySQL
therefore use the same data model, and shared-storage invalidations can update local caches.

The GUI writes `tags.yml` immediately and publishes the new snapshot only after a successful
write. In the current implementation, `/cht reload` **does not reload the `tags.yml`
catalog**: manage it through the GUI or restart after manually editing that file. Do not edit
the file while an administration GUI is active, because the next GUI save represents the
in-memory snapshot.

## In-server verification

To validate a new installation:

1. Create a disabled title with a small rule, for example `ITEMS_OWNED: DIAMOND × 1`.
2. Customize toast, chat and sound, then use **Test presentation**.
3. Enable the title, obtain the item and confirm toast, chat, sound and reward exactly once.
4. Restart the server, open `/tag`, and confirm that unlock and selection remain present.
5. Repeat with and without PacketEvents; inspect the console if the client does not show the toast.

PacketEvents is recommended for unlimited dynamic, client-isolated presentations, but it is
not required. The GUI preview does not unlock, equip or run rewards.

## Current behaviors to know

- Disabled titles stop counting as earned and cannot block collections. However, this build
  still adds a disabled definition's points when its persistent unlock exists; set its
  points to `0` before disabling it if it must not affect profiles or ranking.
- Reward commands do not yet have a durable retry queue. A shutdown after the unlock is
  stored but before command execution will not replay the command at startup.

## Compatibility

Catalog titles render through `{tag_prefix}` and `{tag_suffix}`. Legacy
`/tag prefix|suffix` remains available; an equipped catalog title takes priority, and the
legacy tag reappears intact when the title is unequipped.

The [floating nametag](nametags.md) system remains separate and can include the same
placeholders in its format when the title should also appear above the player.
During `/anonymous`, `{tag_prefix}` and `{tag_suffix}` are blanked to avoid leaking the
player’s real identity.

Third-party plugins should use the versioned [titles and achievements API](titles-api.md)
instead of the internal managers. It provides durable lifecycle events, typed mutations,
Folia-safe progress futures and player-safe catalog views.
