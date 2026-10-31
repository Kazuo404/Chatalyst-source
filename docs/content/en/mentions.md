---
title: Mentions
description: Play a sound and highlight the name when someone is mentioned in chat, by real name or nickname, with a per-player off switch.
---

Mentions make sure people notice when they are talked to. When a message names a player, that player hears a sound and sees their own name highlighted in the line. Nobody else sees the highlight.

A mention works with the real name and with the nickname, because the nickname is often the only name other players know. It also works with the alias of a player in [anonymous mode](anonymous).

## How to turn it on

Mentions are on by default. The settings live in `plugins/Chatalyst/chat.yml`:

```yaml
Mentions:
  Enabled: true
  # Seconds between mention pings for the SAME mentioned player (0 = no cooldown).
  Cooldown_Seconds: 0
  Require_At: false
  Sound: "minecraft:block.note_block.pling"
  # Highlight the mentioned player's name in the chat (only they see their name highlighted).
  Highlight:
    Enabled: true
    Color: yellow
    Bold: true
```

Run `/cht reload` after editing.

## Configuration

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns mention sounds on or off. |
| `Cooldown_Seconds` | `0` | Minimum seconds between two pings for the same player. `0` = no cooldown. Capped at one hour. |
| `Require_At` | `false` | `true` = only `@name` counts. `false` = the bare name counts too. |
| `Sound` | `minecraft:block.note_block.pling` | Namespaced sound key. |
| `Highlight.Enabled` | `true` | Highlights the mentioned name in the line, only for the mentioned player. |
| `Highlight.Color` | `yellow` | Highlight color. |
| `Highlight.Bold` | `true` | Makes the highlighted name bold. |

### How a mention is detected

- The name must appear as a whole word. `@Notch`, `Notch,` and `notch.` all count. `Notchy` does not.
- Case does not matter.
- Only players who actually receive the message are pinged. A player in another [channel](channels) or on another radio frequency is not.
- The sender never pings themselves.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/toggle mentions` | `chatalyst.command.toggle` (true) | Each player can turn the mention sound off for themselves. |

## Staff mentions

Asking for staff in chat ("any admin?", "I need a helper") is a separate feature: the request is routed privately to the right staff tier instead of pinging everyone. It is configured in `network.yml` under `Staff_Mentions`. See [Reports & support](reports).

## Limits

- The sound plays only for players who receive the message. Mentions in private messages or mail do not play this sound.
- A very short name (two or three letters) can match ordinary words. Set `Require_At: true` if your players have such names.
