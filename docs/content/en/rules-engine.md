---
title: Rules engine
description: Write your own chat rules with patterns, conditions and actions. Block advertising, clean up spam, warn, punish, alert staff or run commands, in chat, signs, books and more.
---

The rules engine lets you decide what happens when someone writes something specific. Each rule looks for a pattern and, when it matches, does whatever you choose: block the message, replace part of it, warn the player, add warning points, tell staff, run a command, open a book, and more.

Rules check more than chat. By default they also check commands, signs, books, item names in anvils and nicknames, so a player cannot dodge the anti-advertising rule by writing a server IP on a sign.

Chatalyst ships with ready-made rules: anti-IP, anti-domain, anti-Discord-invite, a character-repeat cleaner and anti-caps. Everything lives in `rules.yml`.

## Turning it on

It is on by default:

```yaml
Enabled: true

Apply_To:
  Commands: true
  Signs: true
  Books: true
  Anvil: true
  Nicks: true
  Ignore_Commands:
    - "/login"
    - "/register"
    - "/l"
    - "/reg"
    - "/changepassword"
```

`Apply_To` decides where rules run besides chat. `Ignore_Commands` lists commands that are never checked; keep your login and password commands here.

## Anatomy of a rule

This is the anti-IP rule from the real file:

```yaml
Rules:

  anti-ip:
    Match: '\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?::\d{1,5})?\b'
    Priority: 100
    Use_Groups: [audited-filter]
    Contexts: [CHAT, COMMAND, SIGN, BOOK, ANVIL, NICK]
    Ignore_Permission: chatalyst.bypass.ads
    Ignore_Contains:
      - "version"
      - "coord"
      - "pos"
      - "warp"
      - " tp "
      - "x:"
      - "y:"
      - "z:"
    Deny: true
    Points: 2
    Warn: "<#FF4D6D>Advertising other servers' IPs is not allowed."
    Warn_es: "<#FF4D6D>No esta permitido anunciar IPs de otros servidores."
    Notify:
      Message: "<#7B68A0>[<#D8B4FE>ADVERT<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
      Message_es: "<#7B68A0>[<#D8B4FE>ANUNCIO<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
    Actions:
      Commands:
        - "warnings {player} clear"
```

### Matching

| Key | What it does |
| --- | --- |
| `Match` | The regular expression to look for. Case-insensitive unless `Case_Sensitive: true` |
| `Priority` | Higher runs first. Default `0` |
| `Contexts` | Where the rule applies: `CHAT`, `COMMAND`, `SIGN`, `BOOK`, `ANVIL`, `NICK`. Empty = everywhere |
| `Ignore_Permission` | Players with this permission skip the rule |
| `Ignore_Contains` | If the message contains any of these texts, the rule is skipped |
| `Strip_Accents` | `true` ignores accents while searching, so `pütä` matches `puta`. The rest of the message is not changed |
| `Stop_After` | `true` stops checking more rules after this one matches |
| `Begins` / `Expires` | Dates `yyyy-MM-dd HH:mm` between which the rule is active (seasonal rules) |
| `Delay_Seconds` / `Player_Delay_Seconds` | Cooldown between two runs of the rule, for everyone / per player |

### What the rule does

| Key | What it does |
| --- | --- |
| `Deny` | `true` cancels the message |
| `Deny_Silently` | Cancels it without the sender noticing |
| `Replace` | Replaces the matched text (`""` removes it) |
| `Replace_Raw` | `true` allows `$1`-style groups in `Replace` |
| `Replace_Prolong` | One character that masks the match, keeping its length (`puta` → `****`) |
| `Warn` / `Warn_es` | A private warning to the sender |
| `Notify` | `Permission` and `Message` (`Message_es`) sent to staff |
| `Points` | [Warning points](punishments#warning-points) added to the player |
| `Kick` | Kicks the player with this message |
| `Fine` | Takes this amount of money (needs Vault) |
| `Sound`, `Title`, `Actionbar`, `Bossbar`, `Toast` | Show something to the player |
| `Actions.Commands` | Console commands |
| `Actions.Player_Commands` | Commands run as the player, with their own permissions |
| `Actions.Proxy_Commands` | `"<server> <command>"`: a console command on another server (see [network](network#rule-commands-on-other-servers)) |
| `Actions.Rewrite_In_World` | Replaces the whole message when the sender is in a given world |
| `Actions.Set_Data` / `Remove_Data` | Saves or deletes a value for the player (see below) |
| `Actions.Open_Book` | Opens a [book](books) |
| `Actions.Audit` | Writes a `RULE` entry in the audit log |
| `Actions.Discord_Staff` | Sends the match to the private staff Discord channel |

Placeholders in texts and commands: `{player}`, `{message}`, `{original_message}`, `{rule}`, `{context}`, plus every Chatalyst placeholder, your custom variables and PlaceholderAPI.

## Reusable groups

A group is a template that does nothing on its own. Rules include it with `Use_Groups`, so you write shared actions and conditions once:

```yaml
Groups:
  audited-filter:
    Notify:
      Permission: chatalyst.notify.rules
      Message: "<#7B68A0>[<#D8B4FE>RULE<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
      Message_es: "<#7B68A0>[<#D8B4FE>REGLA<#7B68A0>] <#C0B3D9>{player}: <#FBF2FF>{message}"
    Actions:
      Audit: true

  survival-only:
    Require:
      Worlds: [world, world_nether, world_the_end]
```

When a rule uses several groups, later groups override earlier ones, and whatever you write in the rule itself wins over all of them.

## Conditions

`Require` (everything must match) and `Ignore` (anything matching skips the rule) let a rule depend on who wrote it and where:

```yaml
Require:
  Permission: rank.member
  Worlds: [world]
  Gamemodes: [SURVIVAL, ADVENTURE]
  Channels: [global]
  Regions: [spawn]
  Variables:
    "%vault_eco_balance%": ">=100"
  Data:
    onboarding.step: "equals:pending"
  Scripts:
    - "player.getPing() < 250"
Ignore:
  Worlds: [jail]
  Muted: true
  Uuid: true
```

| Condition | What it checks |
| --- | --- |
| `Permission` | The player has this permission |
| `Worlds`, `Gamemodes`, `Channels`, `Regions` | Where the player is and what channel they write in (regions need WorldGuard) |
| `Variables` | The value of a placeholder |
| `Data` | A value saved with `Actions.Set_Data` |
| `Scripts` | A JavaScript expression that must return true |
| `Muted` (Ignore) | Skips muted players |
| `Uuid` (Ignore) | Hides UUIDs from the pattern, so `/whitelist add <uuid>` does not trip a number rule |
| `Discord` | Whether the message came from Discord (only for players who linked their account) |

Values in `Variables` and `Data` can be compared with: plain text (equal), `equals:`, `!=`, `contains:`, `starts:`, `ends:`, `in:a,b,c`, `regex:`, `>`, `>=`, `<` and `<=`.

## Saved player data

`Actions.Set_Data` stores a small value per player, and `Require.Data` / `Ignore.Data` read it back. This lets rules remember things, for example "this player already got the first warning":

```yaml
Actions:
  Set_Data:
    onboarding.step: complete
  Remove_Data: [temporary.flag]
```

The values are saved in your normal [storage](storage) (YAML, SQLite or MySQL).

## Anti-caps

Limits messages written mostly in capital letters. It is also in `rules.yml`:

```yaml
Anti_Caps:
  Enabled: true
  Min_Length: 8
  Percentage: 0.7
  To_Lowercase: true
  Bypass_Permission: chatalyst.bypass.caps
  Warn: "<#C0B3D9>Avoid typing in all caps."
  Warn_es: "<#C0B3D9>Evita escribir todo en mayusculas."
```

| Option | Default | What it does |
| --- | --- | --- |
| `Min_Length` | `8` | Shorter messages are never checked |
| `Percentage` | `0.7` | Share of capital letters that triggers it (0.7 = 70%) |
| `To_Lowercase` | `true` | Converts the message to lowercase. Add `Deny: true` to block it instead |

## Sharing rules between servers

Keep common rules in separate files next to `rules.yml` and import them:

```yaml
Import:
  - shared-rules.yml
```

Only file names are accepted (no folders), imports are not recursive, and if a rule name appears twice the first one wins.

## Managing rules

| Command | What it does |
| --- | --- |
| `/chatalyst rule` | Lists the loaded rules |
| `/chatalyst rule toggle <name>` | Turns a rule on or off without editing the file |
| `/chatalyst reload` | Reloads `rules.yml` and imported files |

These need `chatalyst.admin`. Staff with `chatalyst.notify.rules` (op by default) receive the `Notify` alerts.

> [!WARNING]
> If `rules.yml` has a syntax error, the reload keeps your previous rules instead of loading an empty set. Check the console after reloading.

## Limits

- Up to 512 rules are loaded; extra ones are skipped with a console warning.
- Every pattern has a time budget per message. A badly written pattern that takes too long is treated as "no match" instead of freezing the chat, and `/chatalyst perf` shows how often that happened (see [performance](performance)).
- Login and password commands are never written to logs or alerts in clear text, even if a rule matches them. Still, keep them in `Ignore_Commands`.
- Test broad patterns on a test server first: a pattern that matches too much blocks normal conversation.
