# Rules Engine v2

Rules live in `rules.yml`. The engine evaluates ordered regular-expression rules in chat,
commands, signs, book pages, anvil names, nicknames and linked Discord bot messages. A rule can
combine reusable groups, contextual gates, placeholders, scripts, persistent player state and
independent actions.

## Evaluation order

For each enabled rule, Chatalyst checks the context, bypasses, date window, cooldowns and `Require`/`Ignore` conditions before executing any action. Higher `Priority` values run first. `Stop_After: true` stops the chain after that match.

Input is sanitized before filtering. Signs are inspected line by line and books page by page, which keeps evaluation bounded and prevents a large book from hiding content beyond an arbitrary truncation point. Invalid or empty regular expressions are rejected when the file loads.

## Reusable groups

Groups are templates; they never execute on their own. A rule may compose several with `Use_Groups`. Later groups override earlier groups, and fields written directly in the rule have final priority.

```yaml
Groups:
  audited:
    Notify:
      Permission: chatalyst.notify.rules
      Message: "<gold>{player} matched {rule}: <white>{message}"
    Actions:
      Audit: true

  survival-only:
    Require:
      Worlds: [world, world_nether, world_the_end]

Rules:
  suspicious-link:
    Use_Groups: [audited, survival-only]
    Match: '(?i)\\b(?:example\\.com|badsite\\.net)\\b'
    Priority: 50
    Contexts: [CHAT, COMMAND, SIGN, BOOK, ANVIL, NICK]
    Deny: true
    Warn: "<red>That link is not allowed."
    Points: 1
    Stop_After: true
```

## Imports

Extra YAML files beside `rules.yml` can contribute shared groups and rules:

```yaml
Import: [shared-rules.yml, seasonal.yml]
```

Imports are deliberately non-recursive. Chatalyst accepts file names only (never paths), loads
each file with strict YAML parsing, caps the import/rule counts and skips duplicate files. Base
rules load first; if a name is repeated, the first valid definition wins instead of executing the
same automation twice. Imported groups extend the base groups and may override fields within a
same-named group for that imported file.

## Conditions

Legacy contextual conditions and v2 conditions can be mixed:

```yaml
Require:
  Permission: rank.member
  Worlds: [world]
  Gamemodes: [SURVIVAL, ADVENTURE]
  Channels: [global, local]
  Regions: [spawn]
  Discord: true
  Variables:
    "{player_group}": "in:default,member"
    "%vault_eco_balance%": ">=100"
  Data:
    onboarding.step: "equals:pending"
  Scripts:
    - "player.getPing() < 250"
Ignore:
  Worlds: [jail]
  Muted: true
  Uuid: true
  Variables:
    "{player_name}": "regex:^Test[A-Z].*"
  Data:
    moderation.exempt: "equals:true"
  Scripts:
    - "player.isOp()"
```

All entries under `Require.Variables` and `Require.Data` must match. Any matching entry under `Ignore` skips the rule. Required scripts must all return true; any true ignore script skips it. If scripting is disabled or unavailable, required scripts fail closed.

Supported value expressions are plain equality, `equals:`, `!=`, `contains:`, `starts:`, `ends:`, `in:a,b,c`, `regex:...`, `>`, `>=`, `<` and `<=`. Regex conditions are length-limited and kept in a bounded compiled-pattern cache.

`Require.Regions`/`Ignore.Regions` use the async-safe WorldGuard/Chatalyst region snapshot.
`Ignore.Muted` recognizes Chatalyst's active mute state. `Require.Discord` and `Ignore.Discord`
select inbound Discord context; because actions, permissions and state are player-scoped, the
generic engine runs when the author used `/discord link` (or is mapped in the manual
`Discord.Linked_Accounts` fallback) and that
Minecraft player is online. Unlinked or offline Discord authors still pass through the dedicated
Discord swear filter.

## Search transformations

`Strip_Accents: true` folds accents only in an internal search view, so a `puta` expression also
matches `pútà`. `Ignore.Uuid: true` masks canonical dashed and bare 32-hex UUIDs in that same view,
which prevents broad digit/dash patterns from catching `/whitelist add <uuid>`. Match boundaries
are projected back to the original Unicode text: replacements never remove a UUID or strip accents
from content outside the actual match.

## Actions

```yaml
Actions:
  Commands:
    - "warnings {player} clear"
  Player_Commands:
    - "warp spawn"
  Proxy_Commands:
    - "survival broadcast {player} matched {rule}"
  Rewrite_In_World:
    world_nether: "This message is unavailable here."
  Set_Data:
    onboarding.step: complete
    last.rule: "{rule}"
  Remove_Data: [temporary.flag]
  Open_Book: rules-help
  Audit: true
  Discord_Staff: true
```

- `Deny` cancels the content. `Deny_Silently` also hides that fact from the sender.
- `Replace` replaces matches; `Replace_Raw` enables capture references such as `$1`.
- `Replace_Prolong: "*"` replaces each match with one Unicode code point per original code point;
  it overrides `Replace` and accepts exactly one printable code point.
- `Warn`, `Notify`, `Points`, `Kick`, `Fine`, `Sound`, `Title`, `Actionbar`, `Bossbar` and `Toast` are independent actions.
- `Actions.Commands` runs console commands.
- `Actions.Player_Commands` runs commands as the sender, with that player's permissions.
- `Actions.Proxy_Commands` uses `"<target-server> <command>"` and executes only on that named
  remote backend. It requires the authenticated proxy transport plus the local opt-in and command
  allowlist documented in [Proxy and Redis Sync](proxy-redis.md#targeted-rule-console-commands).
- `Actions.Rewrite_In_World` rewrites the entire message only in a matching world and fires the
  same public replace event as a normal replacement.
- `Set_Data` and `Remove_Data` update player-scoped state through the selected YAML, SQLite or MySQL storage backend.
- `Open_Book` opens an entry from `books.yml`.
- `Audit` writes a `RULE` event to the daily log; `Discord_Staff` sends the same bounded event to the configured private bot channel.

Actions accept Chatalyst placeholders, custom JavaScript variables and PlaceholderAPI values. Rule-specific values are `{message}`, `{original_message}`, `{rule}` and `{context}`.

## Timing and displays

Use `Delay_Seconds` for a global action cooldown and `Player_Delay_Seconds` for a per-player cooldown. `Begins` and `Expires` accept `yyyy-MM-dd HH:mm` and allow seasonal rules. Display actions use MiniMessage.

## Audit coverage

When `Log.Enabled` is true, separate switches can record public chat, commands, private messages, mail, signs, books and completed anvil renames. Authentication commands remain excluded. Rule actions with `Audit: true` add explicit `RULE` events, making it possible to distinguish ordinary content from content that triggered automation.

The exclusion also applies when a broad rule is deliberately evaluated against commands. If
`/login`, `/register`, `/reg`, `/otp` or an owner-configured sensitive command matches a rule,
every argument is replaced with `<redacted>` before the `RULE` log, staff Discord alert or
`Notify` template resolves `{message}` / `{original_message}`. The private warning shown back to
the sender is unchanged. This is a final safety boundary; ignoring authentication commands is
still recommended so an unrelated rule does not deny or punish them.

## Operations

Run `/cht reload` after changing `rules.yml` or an imported file. Invalid YAML keeps the previous
immutable snapshot instead of silently replacing it with an empty ruleset. Use `/cht rule` to list
or toggle loaded rules without editing the file. Keep password commands in
`Apply_To.Ignore_Commands`, test broad expressions on a staging server and grant bypass permissions
narrowly. The engine accepts at most 512 rules per snapshot, bounds rule names and conditional
regexes, and clears delay state atomically on reload.
