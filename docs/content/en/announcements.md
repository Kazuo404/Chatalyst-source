---
title: Announcements
description: Rotating automatic announcements that wait for an active chat, quick broadcasts to chat, title, action bar, boss bar or toast, and an animated composer.
---

Chatalyst has two kinds of announcements. The **announcer** rotates your messages automatically, and in smart mode it only speaks when the chat is actually alive, so a quiet server is not spammed. For one-off messages, staff use `/broadcast` or the `/announce` composer, which adds displays, sounds, styles and animations from a menu.

Automatic announcements are shown in each player's language: English clients read `Messages`, Spanish clients read `Messages_es`.

## How to turn it on

The announcer is on by default. It lives in `plugins/Chatalyst/bot.yml`:

```yaml
Announcer:
  Enabled: true
  Interval: 300          # seconds between announcements
  Random: false          # true = random order
  Smart:
    Enabled: true
    Min_Players: 2         # below this online count, never announce
    Min_Chatters: 3        # fixed minimum of different players chatting
    Chatters_Percent: 10   # ...or this % of online players, whichever is higher
  Prefix: "<#7B68A0>[<#B56CFF>Info<#7B68A0>] <reset>"
  # Title shown on "/broadcast toast <message>" (plain text; toasts don't support colors).
  Toast_Title: "Announcement"
  Toast_Title_es: "Anuncio"
  Messages:
    - "<#C0B3D9>Remember to respect other players."
    - "<#C0B3D9>Switch channels with <#FBF2FF>/channel<#C0B3D9>."
    - "<#C0B3D9>Send mail to anyone with <#FBF2FF>/inbox<#C0B3D9>."
  Messages_es:
    - "<#C0B3D9>Recuerda respetar a los demas jugadores."
    - "<#C0B3D9>Cambia de canal con <#FBF2FF>/channel<#C0B3D9>."
    - "<#C0B3D9>Envia correo a cualquiera con <#FBF2FF>/inbox<#C0B3D9>."
```

Edit the messages and run `/cht reload`.

## Configuration

### Automatic announcer

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns the rotating announcer on. |
| `Interval` | `300` | Seconds between announcements. |
| `Random` | `false` | `true` = random order instead of the list order. |
| `Smart.Enabled` | `true` | Only announces when the chat is active (see below). |
| `Smart.Min_Players` | `2` | Below this number of online players, never announce. |
| `Smart.Min_Chatters` | `3` | Minimum number of different players who must have chatted since the last announcement. |
| `Smart.Chatters_Percent` | `10` | Or this percentage of online players, whichever is higher. |
| `Prefix` | `[Info]` | Text in front of each automatic announcement. |
| `Messages` / `Messages_es` | 3 examples | The announcements. The two lists match by position. |

In smart mode, with 100 players online and `Chatters_Percent: 10`, ten different players must have written since the previous announcement. With 5 players, the fixed minimum of 3 applies. When the chat is quiet the announcement waits for a later interval; nothing is lost.

If a Spanish line is missing, Spanish players see the English one. The console uses English.

### Quick broadcasts

`/broadcast [chat|title|actionbar|bossbar|toast] <message>` sends a static message to one display. Without a display it goes to chat. `Toast_Title` and `Toast_Title_es` set the title of toast broadcasts.

### The /announce composer

`/announce` opens a menu for a polished announcement:

1. Choose a display: `EPIC` (animated title, framed chat and animated boss bar together), chat, title, action bar, boss bar or toast.
2. Choose a sound, a style from the chat color palette and an animation.
3. Click the message item and type the text in chat (`cancel` aborts).
4. Choose the scope: this server or the whole network.
5. Preview it privately, or send it.

Available animations: static, typewriter, expand, glitch, color wave, frames, pulse, alert flash, marquee and countdown. They apply to title, action bar and boss bar; chat and toast stay static. Animations run for about eight seconds.

The **frames** animation uses your own templates from `bot.yml`, where `{msg}` is the composed text:

```yaml
  Animation:
    Interval_Ticks: 6
    Frames:
      - "<gradient:#240046:#7B2CBF>{msg}</gradient>"
      - "<gradient:#7B2CBF:#9D4EDD>{msg}</gradient>"
      - "<gradient:#9D4EDD:#C77DFF>{msg}</gradient>"
      - "<gradient:#C77DFF:#E0AAFF>{msg}</gradient>"
```

`Interval_Ticks` accepts 2 to 200.

### Network announcements

The "whole network" scope appears when `Proxy.Enabled` and `Network_Admin.Announcements.Enabled` (in `network.yml`) are both on. Each server renders the announcement itself, with the same display, style, sound and animation. See [Proxy & Redis networks](network).

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/announce` | `chatalyst.command.announce` (op) | Opens the composer. |
| `/broadcast [display] <message>` (`/bc`) | `chatalyst.command.broadcast` (op) | Sends a static broadcast. |
| `/toggle announcements` | `chatalyst.command.toggle` (true) | A player stops seeing the automatic announcer. |

| Permission | Default | What it allows |
|---|---|---|
| `chatalyst.admin.network.announce` | op | Send `/announce` to the whole network. |
| `chatalyst.command.broadcast.chat`, `.title`, `.actionbar`, `.bossbar`, `.toast` | false | One display each (see Limits). |

## Limits

- `/toggle announcements` only hides the automatic announcer. Staff broadcasts and `/announce` still reach everyone.
- The server checks `chatalyst.command.broadcast` before `/broadcast` runs, and that node already allows every display. So today the per-display nodes cannot be used on their own to give someone only one display.
- The alert flash animation changes color quickly. Use pulse or color wave for routine notices.
- Rotating announcements are per server. Use the composer's network scope for a network-wide message.
