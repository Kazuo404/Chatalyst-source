---
title: Filter, anti-spam & anti-bot
description: Keep chat clean automatically. A swear filter that sees through tricks, anti-spam with escalating mutes, anti-bot join delay, hidden-character protection, a command blocker and cooldowns.
---

These tools protect your chat without staff having to watch it all day. The swear filter catches insults even when players disguise them. The anti-spam stops floods and repeated messages and mutes repeat offenders for longer each time. The anti-bot stops accounts that join and spam in the same second. And staff get quick controls for when things get out of hand: lock the chat, slow it down or clear it.

Most of these are on by default and live in `moderation.yml`; the swear filter has its own file, `swear.yml`. For custom patterns (advertising, links…) see the [rules engine](rules-engine).

## Swear filter

The filter looks for whole words, so it does not flag normal words that happen to contain a bad one ("disputa", "class", "cultura"). And it sees through the usual tricks:

| Trick | Example it still catches |
| --- | --- |
| Repeated letters | `putooooo` |
| Separators | `p.u.t.o`, `p u t o`, `p-u-t-o` |
| Leet speak | `put0`, `@`, `4`, `3`, `1`, `$` |
| Accents | `pútà` |
| Look-alike letters | Cyrillic letters that look Latin |
| Sound-alike swaps | `kabron`, `pvto` |
| CamelCase | `LaPutaDeTuMadre` |

The word list covers Spanish (Latin America and Spain), English and Portuguese. Settings in `swear.yml`:

```yaml
Enabled: true
Action: CENSOR
Censor_Char: "*"
Points: 1
Use_Player_Language: true
Warn: "<#FF4D6D>Watch your language."
Warn_es: "<#FF4D6D>Cuida tu lenguaje."
Bypass_Permission: chatalyst.bypass.swear

Name_Alert:
  Enabled: true

Normalize:
  Collapse_Repeats: true
  Strip_Separators: true
  Leet: true
  Camel_Boundaries: true
```

| Option | Default | What it does |
| --- | --- | --- |
| `Action` | `CENSOR` | `CENSOR` masks the letters and sends the message; `DENY` cancels it |
| `Censor_Char` | `*` | Character used to mask |
| `Points` | `1` | [Warning points](punishments#warning-points) added per infraction |
| `Warn` / `Warn_es` | see above | Private warning to the player |
| `Bypass_Permission` | `chatalyst.bypass.swear` | Players with it are not filtered (op by default) |
| `Name_Alert.Enabled` | `true` | Alerts staff (`chatalyst.notify.security`) when someone joins with an offensive account name. It never kicks on its own |
| `Normalize.*` | `true` | Each anti-evasion technique can be turned off separately |

The file has three lists:

- `Words`: banned words written normally. The filter works out the disguised variants itself. Plurals and common forms are listed separately.
- `Contains`: phrases matched anywhere inside a word, for insults glued together (`laputadetumadre`, `fuckyou`). Only add long, unambiguous fragments: `puta` here would flag `computadora`.
- `Whitelist`: real words that should never be touched in your community.

Messages from Discord also pass through the filter before reaching the game.

## Anti-spam

Stops players from sending messages too fast or repeating the same message.

```yaml
Anti_Spam:
  Enabled: true
  Delay_Seconds: 1.5
  Similarity: 0.9
  Repeat_Seconds: 30
  Min_Repeat_Length: 5
  Bypass_Permission: chatalyst.bypass.spam
  Auto_Mute:
    Enabled: true
    Warnings_Before_Mute: 4
    Escalation:
      - "15m"
      - "30m"
      - "1h"
      - "5h"
      - "12h"
      - "1d"
    Warning_Reset: 60
    Escalation_Reset: "6h"
    Delete_Messages: true
    Delete_Count: 10
    Clear_Chat: false
```

| Option | Default | What it does |
| --- | --- | --- |
| `Delay_Seconds` | `1.5` | Minimum time between two messages from the same player |
| `Similarity` | `0.9` | How similar a message must be to the previous one to count as a repeat (1 = identical only) |
| `Repeat_Seconds` | `30` | How long the previous message is remembered for the repeat check. `0` = the whole session |
| `Min_Repeat_Length` | `5` | Shorter messages ("ok", "gg", "hola") never count as repeats |
| `Auto_Mute.Warnings_Before_Mute` | `4` | Spam warnings before an automatic mute |
| `Auto_Mute.Escalation` | 15m → 1d | Mute length for the 1st, 2nd, 3rd… offense. The last value repeats |
| `Auto_Mute.Warning_Reset` | `60` | Seconds without spam before the warning count resets |
| `Auto_Mute.Escalation_Reset` | `6h` | Time without auto-mutes before the escalation level drops |
| `Auto_Mute.Delete_Messages` | `true` | On auto-mute, removes the spammer's recent messages from everyone's chat |
| `Auto_Mute.Delete_Count` | `10` | How many recent messages to remove |
| `Auto_Mute.Clear_Chat` | `false` | Also pushes everyone's chat up with blank lines (except staff). A blunt tool, off by default |

Per-rank delays are set in `config.yml` with `Groups.List.<group>.Chat_Delay_Seconds` (see [configuration](configuration#per-group-overrides)).

> [!NOTE]
> Removing a message from other players' screens only works for lines that Minecraft signed. Almost all normal chat is signed; lines sent as system messages (for example translated chat) cannot be pulled back.

## Anti-bot

Makes new arrivals wait a few seconds before they can chat or use commands, which stops bots that join and spam instantly. Off by default.

```yaml
Anti_Bot:
  Enabled: false
  Chat_Delay_After_Join: 3
  Command_Delay_After_Join: 2
  Bypass_Permission: chatalyst.bypass.antibot
  Allowed_Commands:
    - "/login"
    - "/register"
    - "/l"
    - "/reg"
```

Login commands in `Allowed_Commands` always work, so players can log in during the wait.

## Chat security

Cleans every message before any other filter runs. It removes invisible and control characters (used to sneak insults past filters by hiding zero-width characters between the letters, or to flip the text direction) and caps the message length. Leave it on.

```yaml
Chat_Security:
  Enabled: true
  Max_Length: 256
  Strip_Control_Chars: true
  Strip_Invisible: true
  Notify_Staff: true
  Bypass_Permission: chatalyst.bypass.security
```

With `Notify_Staff: true`, staff with `chatalyst.notify.security` are alerted when someone uses hidden characters to dodge the filter. The bypass permission is not given to anyone by default, not even operators.

## Command blocker

Blocks commands completely, whichever plugin registered them. Each entry is a literal command (`/op`) or a regular expression (`regex:...`). By default it hides server information: `/plugins`, `/version`, `/tps`, `/timings`, `/seed` and their variants, including namespaced forms like `/bukkit:plugins`.

```yaml
Command_Blocker:
  Enabled: true
  Bypass_Permission: chatalyst.bypass.blocker
  Blocked:
    - "regex:(?i)/(\\w+:)?(plugins|pl|version|ver|about|icanhasbukkit|help|\\?)(\\s.*)?"
    - "regex:(?i)/(\\w+:)?(timings|tps|mspt|lag|gc|memory|entities|spark|health|sysinfo)(\\s.*)?"
    - "regex:(?i)/(\\w+:)?(seed|datapack)(\\s.*)?"
```

A blocked command answers with a short "That command is blocked" message (`blocker.blocked` in the lang files). Operators are never blocked. Tab completion hides the same kind of commands (`Tab_Complete` in `chat.yml`), so players do not see them in the first place. With [PacketEvents](integrations#packetevents), blocked commands are stopped even earlier.

## Command cooldowns

Makes players wait between uses of specific commands, such as `/kit`, `/home` or `/rtp`, whichever plugin owns them.

```yaml
Command_Cooldowns:
  Bypass_Permission: chatalyst.bypass.cooldown
  Commands: {}
```

Add commands with their wait in seconds:

```yaml
Command_Cooldowns:
  Bypass_Permission: chatalyst.bypass.cooldown
  Commands:
    "/kit": 60
    "/home": 5
```

## Staff chat controls

| Command | Permission | What it does |
| --- | --- | --- |
| `/chatlock` (`/mutechat`) | `chatalyst.command.chatlock` | Locks the global chat |
| `/chatunlock` (`/unmutechat`) | `chatalyst.command.chatlock` | Unlocks it |
| `/slowmode <seconds>` / `/slowmode off` | `chatalyst.command.slowmode` | Forces a wait between messages for everyone |
| `/clearchat` (`/cc`) | `chatalyst.command.clearchat` | Pushes everyone's chat out of view with `Chat.Clear_Lines` blank lines (150) |
| `/chatalyst purge <player>` | `chatalyst.admin` | Removes a player's recent messages from everyone's chat, even up to 5 minutes after they left |
| The **X** next to a message | `chatalyst.deletemessages` | Deletes that one message for everyone |

All are op by default. `chatalyst.chat.bypass` lets a player keep talking in a locked or slowed chat, and `chatalyst.command.clearchat.bypass` keeps a staff member's chat when someone clears it, so they can review what was said.

## Bypass permissions

| Permission | Skips | Default |
| --- | --- | --- |
| `chatalyst.bypass.swear` | Swear filter | op |
| `chatalyst.bypass.spam` | Anti-spam and the character-repeat rule | op |
| `chatalyst.bypass.antibot` | Anti-bot wait | op |
| `chatalyst.bypass.security` | Chat security cleaning | nobody |
| `chatalyst.bypass.blocker` | Command blocker | op |
| `chatalyst.bypass.cooldown` | Command cooldowns | op |
| `chatalyst.bypass.caps` | Anti-caps | op |
| `chatalyst.bypass.ads` | Anti-advertising rules | op |

## Limits

- No filter is perfect. The swear filter is tuned to avoid false positives, so a very creative spelling can still get through. Add it to `Words` or `Contains` when you see it.
- The command blocker blocks by name. It cannot stop a plugin that offers the same information through a different command name; add that name too.
- The anti-bot only delays chat and commands. It does not stop bot accounts from joining; use a dedicated anti-bot plugin or your proxy for that.
