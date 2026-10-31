---
title: Channels
description: Separate chat rooms for global, trade, staff, radio, region and party chat, with history replay and a router that keeps sales out of global chat.
---

Channels split your chat into rooms. Every player writes in one channel at a time and reads the channels they are allowed to hear. A survival server can keep sales in `trade`, staff talk in `staff` and nearby conversations on a radio frequency, while global chat stays readable.

Channels are on by default. The shipped layout has `global`, `local` (radio), `staff`, `region` and `party` ready to use, and `trade` and `auction` prepared but disabled.

## Channel types

| Type | Who receives the message |
|---|---|
| `GLOBAL` | Everyone on the server. With `Proxy: true`, the whole network. |
| `WORLD` | Players in the same world. |
| `FREQUENCY` | Players tuned to the same radio frequency, at any distance. |
| `REGION` | Players standing in the same chat region (Chatalyst regions or WorldGuard regions). |
| `PARTY` | Members of the same party (Parties plugin). |

> [!NOTE]
> `REGION` and `PARTY` channels behave like normal channels when WorldGuard or Parties is missing, so nothing breaks.

## How to turn it on

Everything lives in `plugins/Chatalyst/chat.yml` under `Channels`. The module is enabled out of the box:

```yaml
Channels:
  Enabled: true
  Default: global
  # Channel players WRITE in when they join the server:
  #   default = everyone starts back in the default channel (the general chat)
  #   last    = remember the channel they were using when they left
  On_Join: default
  Exclusive: true
```

Run `/cht reload` after editing.

## Configuration

### Global options

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Master switch for the channel system. |
| `Default` | `global` | The main chat. New players write here. |
| `On_Join` | `default` | `default` puts everyone back in the default channel on join. `last` remembers the channel they left in. |
| `Exclusive` | `true` | Channels act as separate rooms (see below). `false` goes back to overlapping channels. |
| `History.Enabled` | `true` | Keeps a short in-memory history per `GLOBAL` channel. |
| `History.Size` | `20` | Messages kept per channel. |
| `History.Show_On_Switch` | `10` | Messages replayed when you switch into a channel. `0` = only on demand. |
| `History.Retention_Minutes` | `30` | Older entries are dropped. |
| `Frequency.Default` | `""` | Radio frequency new players start on. Empty = radio off until they tune in. |

### Defining a channel

Each channel is an entry under `Channels.List`. This is the shipped `global` and `staff` pair:

```yaml
  List:
    global:
      Aliases: [g, global]
      Type: GLOBAL
      Permission: ""
      Format: chat
      Auto_Listen: true
      # Proxy: true forwards this channel across the whole network (needs Proxy.Enabled below).
      Proxy: false
    staff:
      Aliases: [s, sc, staff]
      Type: GLOBAL
      Permission: chatalyst.channel.staff
      Format: staff
      Auto_Listen: true
      # Staff must never miss staff messages, even with Channels.Exclusive on.
      Force_Listen: true
      # Required for support escalations and staff chat across Velocity/Bungee.
      Proxy: true
```

| Field | What it does |
|---|---|
| `Enabled` | `false` hides the channel from commands, aliases, tab completion, the router and the GUI. |
| `Aliases` | Short names for `/channel <alias>`. |
| `Type` | One of the five types above. |
| `Permission` | Empty = anyone. Otherwise required to read and write. |
| `Format` | The format file in `formats/` this channel uses. See [Chat formats](formats). |
| `Auto_Listen` | `true` = heard without joining (only when `Exclusive` is off). |
| `Force_Listen` | Always heard, even with `Exclusive` on. The shipped `staff` and `local` channels use it. |
| `Proxy` | Sends the channel across a Velocity/BungeeCord/Redis network. See [Proxy & Redis networks](network). |
| `Auto_Enable_With_Market_Plugins` | Lets a disabled `trade` or `auction` channel appear when a supported market plugin is detected. |

### Exclusive channels

With `Exclusive: true` (the default) each channel is its own room. You always hear the default channel (unless you mute it) and the channel you are writing in. Being in `global` hides `trade`, `auction` and the others. Exceptions:

- channels with `Force_Listen: true`;
- channels you subscribed to with `/channel listen <name>`;
- players with `chatalyst.channel.seeall`.

When you switch into a channel, the last messages are replayed and clearly marked as already sent, so you catch up instantly.

### Radio frequency (local chat)

The shipped `local` channel is a radio, not proximity chat. Players tune a frequency between `10.00` and `99.99` and only hear others on the same frequency, at any distance. With `Frequency.Default` empty, nobody is on the radio until they tune in. The chosen frequency is saved.

- `/frequency` opens the tuner, `/frequency 42.50` tunes directly, `/frequency off` turns the radio off.
- `/f <message>` speaks once on your frequency without leaving your current channel.

Radio messages go through mutes, rules, anti-spam, formatting and logs like any other message. Staff with `chatalyst.bypass.range` hear every frequency.

### Trade and auction channels

`trade` and `auction` ship with `Enabled: false`. With `Auto_Enable_With_Market_Plugins: true`, Chatalyst turns them on at runtime when it detects a supported auction or shop plugin (AuctionHouse, zAuctionHouse, CrazyAuctions, PlayerAuctions, AuctionMaster, QuickShop, ChestShop, ShopGUI+, EconomyShopGUI, DynamicShop, BossShopPro). Set `Enabled: true` to keep one visible permanently. The built-in market also posts here, see [Market & trading](market).

### Chat regions without WorldGuard

`REGION` channels work without WorldGuard. Stand in one corner and run `/cht region pos1`, go to the opposite corner and run `/cht region pos2`, then `/cht region add <name> [channel]`. Regions are saved in `regions.yml`. If you give a region a channel, players switch to it when they walk in and back when they leave. With WorldGuard installed, its regions count too.

### Smart channel router

The router keeps global chat free of sale spam. Messages written in the default channel that match a pattern are moved to the target channel automatically (or blocked with a hint).

```yaml
Channel_Router:
  Enabled: true
  Action: move           # move = deliver in the target channel · block = cancel + hint
  Notify: true           # tell the player their message was moved (at most once a minute)
  # One-off shortcuts: starting a message with the prefix sends it to that channel without
  # switching (player's explicit choice; the prefix is stripped). Example below.
  Shortcut_Prefixes: {}
  # Shortcut_Prefixes:
  #   "!": trade
  #   "?": auction
```

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns the router on. |
| `Action` | `move` | `move` delivers the message in the target channel. `block` cancels it and shows a hint. |
| `Notify` | `true` | Tells the player their message was moved, at most once a minute. |
| `Shortcut_Prefixes` | `{}` | Prefixes that send one message to a channel without switching. |
| `Rules.<channel>.Patterns` | Spanish and English sale/auction words | Case-insensitive Java regexes. |

Staff with `chatalyst.bypass.router` are never routed.

### Per-group chat format and delay

`config.yml` has a `Groups` section keyed by LuckPerms primary group (Vault group as fallback). Each group can have its own `Format` (applies to channels that use the base chat format) and `Chat_Delay_Seconds` (overrides the anti-spam delay; `0` = no delay).

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/channel` (`/ch`) | `chatalyst.command.channel` | Opens the channel GUI. |
| `/channel <name>` | `chatalyst.command.channel` | Switches the channel you write in. |
| `/channel list` | `chatalyst.command.channel` | Lists the channels as text. |
| `/channel mute <name>` | `chatalyst.command.channel` | Hides or shows a channel. |
| `/channel listen <name>` | `chatalyst.command.channel` | Subscribes to a channel without writing in it. |
| `/channel history [name]` | `chatalyst.command.channel` | Shows the recent history of a channel. |
| `/channel send <name> <msg>` | `chatalyst.channel.send` | Sends one message to a channel without joining it. |
| `/channel sendas <player> <name> <msg>` | `chatalyst.channel.sendas` | Sends a message as another online player (console allowed). |
| `/frequency [NN.NN\|off]` | `chatalyst.command.frequency` | Tunes the radio. |
| `/f <message>` | `chatalyst.command.frequency` | Speaks once on your frequency. |
| `/staffchat <msg>` (`/sc`) | `chatalyst.channel.staff` | Sends one message to the staff channel. |
| `/cht region pos1\|pos2\|add\|remove\|list` | `chatalyst.admin` | Manages chat regions. |

Other nodes: `chatalyst.channel.seeall` (hear every channel with `Exclusive` on, default `false`), `chatalyst.bypass.range` (hear every frequency, default op), `chatalyst.bypass.router` (never routed, default op). See [all commands](commands) and [all permissions](permissions).

## Limits

- History is kept in memory only and is cleared on restart. Only `GLOBAL` channels are recorded: replaying radio, world, region or party chat could show messages the viewer was never meant to see.
- Radio (`FREQUENCY`) chat stays on one server. Do not proxy it. Use a separate `GLOBAL` channel for network-wide talk.
- The router checks patterns only for players writing in the default channel.
