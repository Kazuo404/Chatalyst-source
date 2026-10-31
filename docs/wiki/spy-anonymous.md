# Container Log, Inventory Sharing and Anonymous Mode

Three systems built on top of the spy and staff suite: container and mechanism
surveillance with exact coordinates and teleport buttons, chat-shareable inventories with
an "Open" button, and a full incognito mode for streamers. Everything is bilingual (each
player reads it in their own language) and Folia-safe.

## Container and mechanism log

Records who **opens** chests, trapped chests, barrels, shulkers, ender chests, furnaces,
hoppers, droppers, dispensers, lecterns, chest minecarts and chest boats; who **places**
or **breaks** containers; and who **uses** mechanisms (levers, buttons, doors, trapdoors,
fence gates, repeaters, comparators); and their workbench activity: **crafting**,
**enchanting** and **completed villager trades** — the latter with the full exchange
("3x emerald for 20x wheat"), which is what makes broken prices or emerald farms
visible. Every entry stores player, action, target and exact coordinates.

### Repeat anti-spam

Repeating the same action on the same target within `Repeat_Seconds` (60 by default)
produces **a single line**; the next one reports how many were hidden ("+19 more"), so
nothing is lost but the noise. Opening a chest twenty times while sorting is one line,
not twenty.

Targets that **move** (villagers, chest minecarts, horses) group by entity rather than by
coordinates: otherwise every step the villager took counted as a new target and the spy
filled up with identical lines.

Entries live in two places:

- **In memory** (last 1000) for instant review with `/containerlog`.
- **In the daily logs** (`logs/chat-YYYY-MM-DD.log`, type `CONTAINER`) for historical
  audits; they also show up in `/chatlog container`.

### The command

```
/containerlog [player...] [count|date]     (aliases: /contlog, /cofres)
```

Without a date it reads the recent buffer; with `today`, `yesterday` or `YYYY-MM-DD` it
re-reads that day's file. Each line carries two buttons for staff with the teleport
permission:

- **[View]** — opens the container's contents in a read-only view without travelling
  there. It shows what is inside **right now** (not a snapshot of the moment it was
  opened), and shulkers inside open with a click. For an ender chest it shows the
  player's, not the block's. Permission `chatalyst.containerlog.peek`.
- **[Go]** — teleports you next to the exact block.
- **[TP]** — teleports you to the player.

The same buttons appear in the **live spy**: with `/spy` on and the granular `container`
type (the chest in the spy menu), every interaction arrives in real time. The `/staff`
dashboard has a "Container log" button in the tools row.

### Global or per-player spy

The spy is **global** by default (you see the whole server), but it can be narrowed:

```
/spy                  spy menu (toggle, types and who you watch)
/spy toggle           turn on or off
/spy <player>         watch ONLY that player (repeat to remove them)
/spy all              back to global scope
```

It is also driven **from the GUIs**: in `/staff` the spy button shows its state (on/off
and scope) and **right-click** toggles it without leaving the dashboard (left-click opens
the settings). And in `/players` → manage there is a **Watch with spy** button that adds
or removes that player with one click.

Watching accumulates: `/spy Pepe` then `/spy Ana` puts both under the lens and silences
everyone else. The filter applies to **every** type (PMs, commands, signs, books, anvils
and containers), and for private messages watching either end is enough to see them. You
can watch an offline player: their lines arrive as soon as they join.

The first target **turns the spy on** if it was off, and removing the last one **turns it
back off** — so dropping someone from the list never leaves you watching the whole server
unasked. If the spy was already on globally before you targeted anyone, removing the last
target returns you to global (where you came from). The message tells you which of the two
happened, and the menu lists the targets with a click to go back global.

### Configuration (`players.yml` and `moderation.yml`)

```yaml
Spy:
  Containers:
    Enabled: true      # turns the WHOLE system off
    Mechanisms: true   # levers, buttons, doors, trapdoors, fence gates...
    Crafting: true     # crafting and enchanting
    Trading: true      # completed villager trades
    Repeat_Seconds: 60 # group repeats (0 = one line per repetition)
```

```yaml
Log:
  Containers: true     # persistence in the daily log files
```

### Permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `chatalyst.spy.container` | false | See the live container spy |
| `chatalyst.command.containerlog` | op | Use `/containerlog` and its dashboard button |
| `chatalyst.containerlog.teleport` | op | See and use the [Go] and [TP] buttons |
| `chatalyst.containerlog.peek` | op | See and use the [View] button (open contents) |
| `chatalyst.containerlog.exempt` | false | This player never leaves a trace |

The first three are included in `chatalyst.staff.*`.

## Shulkers inside reviews

In the read-only `/invsee` and `/echest` views (and in chat-shared inventories),
**clicking a shulker box opens its contents** in another read-only view, with a back
arrow.

In **edit** sessions (`invsee.edit`) the inventory is real and left-click keeps picking
up and moving items, so there you **right-click with an empty cursor** to look inside a
shulker. Opening it applies whatever you edited so far, and the back arrow reopens the
session with fresh state. With items on the cursor, right-click keeps its normal vanilla
behaviour (dropping one at a time).

The inventory editor already supported editing **equipped armor** (bottom row, boots →
helmet) and the **off hand**; the blocked filler panes of that row now show a slot guide
so it is obvious. Changes apply on close.

## /clearinventory

```
/clearinventory <player> [all|ender]     (aliases: /clearinv, /ci, /limpiarinv)
```

Without an argument it clears **everything**: storage, hotbar, equipped armor, off hand,
the cursor-held item and the 2×2 crafting grid. `ender` clears only the ender chest;
`all` clears both. An unknown mode clears nothing (shows usage). It runs on the target's
region (Folia), notifies the affected player in their language and is audited.
Permission: `chatalyst.command.clearinventory` (op, included in `chatalyst.staff.*`).
Part of the dynamic suite: it only exists with `Staff_Moderation.Enabled: true`.

## Sharing inventories in chat: [inv] and [ec]

Any player can type `[inv]` (or `[inventory]`/`[inventario]`) or `[ec]` (or
`[ender]`/`[enderchest]`) in chat. The token becomes a tag with an **▶ Open** button
anyone can click for **5 minutes**:

- The view is **live and read-only** (read on the owner's region when opened).
- Shulkers inside open with a click.
- The button carries an **opaque token**, never the UUID or real name: nobody can forge
  the command to peek at someone who did not share, and an anonymous player is not
  unmasked by sharing.

Config: `Chat.Inventory_Share: true` in `chat.yml`. Sender permission:
`chatalyst.chat.shareinv` (default true).

## Restyled vanilla messages

Minecraft sends its own command feedback (`/tp`, `/xp`, `/gamemode`, `/give`, `/kill`,
`/effect`, `/time`, `/weather`...) as plain grey text:

```
Teleported BurnInSilence to Nec1x
Nec1x has 4,575 experience points (level 47)
```

Chatalyst detects and rewrites them with the plugin's look, **in each client's own
language**:

```
» Teleported BurnInSilence to Nec1x.
» Nec1x has 4575 experience points.
```

It works because the server sends those messages as *translatable* components (a key plus
its arguments). The plugin recognises the key, pulls the arguments out as `{0}`, `{1}`,
`{2}`... and feeds them into the template in `lang/<locale>.yml` under `vanilla:`. 28 keys
are covered out of the box; editing them is just editing the lang file, and an **empty**
template (`""`) keeps the vanilla message for that key.

```yaml
# chat.yml
Vanilla_Messages:
  Enabled: true
  Ignore: []   # vanilla keys you want left alone
```

**Requires PacketEvents** installed with `PacketEvents.Enabled: true` (integrations.yml):
these messages never reach Bukkit's API, only the network layer. Without PacketEvents the
rest of the plugin works the same and you get an informational console notice on startup.

## Anonymous mode: /anonymous

```
/anonymous [on|off]     (aliases: /anon, /anonimo)
```

Built for streamers. When enabled:

- **Name**: a generated alias that looks like a real player — `ShadowWolf42`,
  `Nether_Nomad`, `ItzPanda`, `xXBlazeXx`, `frostgolem`... (`Name_Style: realistic`,
  hundreds of thousands of combinations; `confusing` brings back the unreadable
  `illiliolil` style). It never matches another alias, an online player, or **anyone who
  has ever played on this server** (the disguise never impersonates a member). It
  propagates to chat, tab, the TAB plugin, fancy nametags and the tab-complete
  suggestions of *every* command.
- **Rank and tags**: your real LuckPerms/Vault prefix/suffix/group, `/tag`, party, rank
  color and your **personal chat color** (`/chatcolor`) vanish from every format —
  including PlaceholderAPI-resolved ones (`%luckperms_prefix%` and friends are rewritten
  for the anonymous subject).
- **Disguise rank**: instead of a bare name, the anonymous player wears the tag of a
  regular member — the prefix of the `Anonymous.Disguise_Group` group (`default` out of
  the box), or the literal `Anonymous.Tag` if you set one. It shows in chat, tab, the
  nametag and hovers, so the disguise blends in: a name with *nothing* in front of it in
  a list where everyone has a tag points at the anonymous player as clearly as their real
  rank would. Set both to `""` for the old bare-name behaviour.
- **Skin**: a **pool of real skins** (`Anonymous.Skins`, accounts whose skins are
  downloaded signed from Mojang at startup): each session wears a different random one,
  like being any random NameMC player. GUI heads show the same session skin.
  Non-existent accounts are skipped with a console notice.
- **Outside the game**: Chatalyst's Discord bot posts the alias in its formatted message,
  never the real name. Market announcements and death messages use the alias as well.
- **Stable identity per session**: within the same connection you are **always the same
  person**. If you `/anonymous off` and turn it back on, you get the same alias and the
  same skin back — changing face mid-session would reveal that both disguises are the
  same player.
- **Persistence**: disconnecting anonymous means rejoining anonymous — with a **fresh
  alias AND skin on every connection** (`Rotate_Name_On_Join`, on by default): only a
  reconnect makes you "someone else", and nobody can track you across days. The fresh alias
  is never the **same as the previous session's** either: with a finite name pool chance
  would otherwise repeat it now and then, and "someone else" who happens to share yesterday's
  name fools no one. `/nick` is locked while disguised (a habitual `/nick off` would unmask
  you by accident).
- **Join and leave messages** announce the **alias**, never the real name — including the
  goodbye, which is composed a couple of ticks *after* you disconnect: the alias is held
  long enough (and past a same-second reconnect) so the farewell can never fall back to your
  real name.
- **Switching identity while online** intentionally looks like a reconnect when
  `Simulate_Reconnect` is enabled (the default): enabling anonymous mode shows the real identity
  leaving and the alias joining; disabling it shows the alias leaving and the restored identity
  joining. These are simulated per-viewer messages—the player never disconnects. Set the option
  to `false` for a silent in-place change.
- **The alias does not arrive in the same second you left.** Nobody reconnects in zero seconds,
  and that detail gave the disguise away however good the alias was. The disguise applies
  **instantly** — whoever asks for anonymity needs it now — but the join announcement waits
  between 1 and 5 minutes, drawn fresh every time (`Realistic_Transition`). You keep playing
  disguised while it waits; only the message is late.
- **A face chosen with `/anon set <n>` stays with you** across disconnects, until you change it
  or switch anonymous mode off. Rotating is right for a drawn alias — every session is somebody
  else — but a specific slot is a character. A pinned slot that no longer exists, or whose face
  another player happens to be wearing, falls back to a fresh alias: two identical disguises
  would give away both players.

`/anonymous off` restores the exact nickname, tags, visible rank and original skin.

### Configuration (`players.yml`)

```yaml
Anonymous:
  Enabled: true
  Name_Style: "realistic"   # realistic = ShadowWolf42...; confusing = "illiliolil"
  Name_Chars: "ilo"         # confusing style only
  Name_Length: 10           # confusing style only (max 16)
  Rotate_Name_On_Join: true # NEW alias AND skin on every connection
  Simulate_Reconnect: true  # fake leave/join only when toggling; false = silent change
  Realistic_Transition:     # the alias arrives minutes later, not on the spot
    Enabled: true
    Min_Seconds: 60
    Max_Seconds: 300
  Disguise_Group: "default" # rank the disguise wears ("" = no tag at all)
  Tag: ""                   # literal tag overriding the group prefix, e.g. "<gray>[Member] "
  Skins:                    # disguise pool: one random real skin per session
    - "Daniel"
    - "Emma"
    # ...add any existing account name (e.g. picked from NameMC)
  Skin_Name: "windexonv"    # extra/fallback pool entry (the black "?" one)
```

The disguise also covers the **indirect group**: an anonymous player joins with the
generic fancy entrance (not their rank's) and chats with the default format even if their
group has its own format in `Chat_Format_Groups`.

Permission: `chatalyst.command.anonymous` (op by default — grant it to the streamer rank).

### What staff still sees (on purpose)

`/realname`, `/seen`, the logs, the audit trail and the container log use the real name:
anonymity faces players, not moderation.

### Audit trail (`Log.Anonymous`)

Putting the disguise on — both `/anonymous` and the rotation on every join — writes the
**real name → alias** mapping to the plugin's daily log, and there only:

```
[ANONYMOUS] BurnInSilence: -> Ninja60 (adc749c2-0db9-4b2d-a438-c4250eb9b93b)
```

Neither the live console nor chat ever show it, so nothing unmasks the streamer in the
moment; but if `Ninja60` does something bannable, staff can find out later who it was. It
shows up in `/chatlog` (staff-only) like any other entry. Set `Log.Anonymous: false` in
`moderation.yml` for **zero trace** — at the cost of not being able to tie an alias back to
a player once the session ends.

### Honest limits

- The real **profile name** still travels at protocol level (F3, server ping sample,
  third-party scoreboard plugins). Hiding it would require packet rewriting.
- The vanilla name above the head is hidden by **Fancy_Nametags** (or TAB with
  `invisible-nametags: true`): keep one of them enabled.
- If **TAB** (or another scoreboard/tablist plugin) renders the prefix with
  `%luckperms_prefix%`, that plugin will reveal the rank on its own. Switch its config to
  Chatalyst's anonymity-aware placeholders: `%chatalyst_prefix%`, `%chatalyst_suffix%`,
  `%chatalyst_group%` (blank while disguised) and `%chatalyst_anonymous%` (yes/no).
- A separate third-party Discord bridge can still post real names on its own. Remove it
  when Chatalyst's built-in bot is enabled.
