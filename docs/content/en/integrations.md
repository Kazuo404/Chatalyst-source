---
title: Integrations
description: The plugins Chatalyst works with, what each connection adds, and the settings for voice chat, dynmap, PacketEvents, custom variables and custom commands.
---

Chatalyst works on its own, but it gets better next to the plugins you probably already run. Every integration is optional: it is detected when the server starts and skipped silently if the other plugin is not installed. You never need to install something just to make Chatalyst start.

This page lists what each connection adds. The bigger ones have their own pages: the [Discord bot](discord-bot), [Tebex and the store](store), the [proxy and Redis](network) and [live translation](translation).

## Detected automatically

These need no configuration. Install the other plugin and Chatalyst uses it.

| Plugin | What Chatalyst does with it |
| --- | --- |
| LuckPerms | Reads ranks, prefixes and suffixes for chat formats; per-group overrides; rank sync with Discord and Tebex |
| Vault | Fallback for ranks and prefixes when LuckPerms is missing; the economy for `/baltop`, the market, rentals and store money |
| PlaceholderAPI | Any `%placeholder%` works in formats and messages, and Chatalyst adds its own `%chatalyst_...%` placeholders (see [placeholders](placeholders)) |
| ItemsAdder | Font images and emojis in chat, custom icons in menus and store cards, custom item ids in `[item]` |
| Nexo | Custom item ids in `[item]` |
| WorldGuard | Region chat channels (see [channels](channels)) |
| Parties | Party chat channel and the `{party_tag}` placeholder |
| TAB | Anonymity-aware placeholders and nickname sync, so TAB and Chatalyst never fight over the tab list |
| EssentialsX | Nicknames are mirrored into Essentials (`/tpa <nick>` works); Chatalyst loads first and keeps names such as `/msg`, `/nick`, `/afk` and `/me` |
| Lib's Disguises / Morphy | A disguised player's nametag and rank are hidden so they do not give the disguise away; vanished staff can keep a disguised body (see [staff suite](staff-suite#vanish)) |
| MythicMobs | Custom mob names appear correctly in death messages |
| AuthMe, nLogin and similar | Mandatory Discord linking waits until the player has logged in |
| AdvancedKits, PlayerKits2, PlayerKits | The store can preview the real contents of a kit (see [store](store)) |
| PlayerVaultsX | Staff with `chatalyst.command.vaultsee` review anyone's vaults, online or offline, with `/vaultsee <player> [number]` or the button in the player menu: a read-only copy that never opens the vault. With `chatalyst.command.vaultsee.edit`, a right-click opens the real vault through PlayerVaultsX itself (see [staff suite](staff-suite)) |
| Market and auction plugins | Listings can be announced in the trade channel (see [market](market)) |

> [!NOTE]
> Chatalyst never takes a command name that another plugin already owns unless the feature says so (for example `/ranks` or `/store` are skipped with a console message if taken). The only exception is the [staff suite](staff-suite), which you switch on on purpose.

## Voice chat

Links Chatalyst mutes to your voice chat plugin, so a muted player is also muted in voice. It works with Plasmo Voice or a Simple Voice Chat addon that offers mute and unmute console commands.

```yaml
Voice:
  Enabled: false
  Provider: auto
  Mute_Command: "vmute {player} {duration} {reason}"
  Unmute_Command: "vunmute {player}"
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | Turns the bridge on |
| `Provider` | `auto` | `auto` tries Plasmo Voice first and then the commands below; `plasmo` prefers Plasmo Voice names; `custom` only uses the commands below |
| `Mute_Command` | `vmute {player} {duration} {reason}` | Console command run when a player is muted |
| `Unmute_Command` | `vunmute {player}` | Console command run when the mute ends |

## dynmap

Places a marker on the web map for every open [report](reports), at the spot where it was made, so staff can see where problems are happening.

```yaml
Dynmap:
  Reports:
    Enabled: false
    Set_Id: "chatalyst.reports"
    Set_Label: "Chatalyst Reports"
    Icon: "exclamation"
    Persistent: false
    Hide_By_Default: false
    Show_Labels: false
    Layer_Priority: 10
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | Turns the report markers on |
| `Set_Id` / `Set_Label` | `chatalyst.reports` / `Chatalyst Reports` | The marker layer id and the name shown on the map |
| `Icon` | `exclamation` | dynmap icon used for each marker |
| `Persistent` | `false` | Whether dynmap keeps the markers after a restart |
| `Hide_By_Default` | `false` | Starts with the layer hidden |
| `Show_Labels` | `false` | Shows the label next to each marker |
| `Layer_Priority` | `10` | Order of the layer in the map's layer list |

## PacketEvents

PacketEvents lets Chatalyst work at the network level. It is optional, but some features need it.

```yaml
PacketEvents:
  Enabled: false
```

With PacketEvents installed and `Enabled: true`:

- Blocked commands are stopped before the server even processes them.
- Minecraft's own command feedback (`/tp`, `/give`…) is restyled in each player's language (see [localization](localization#minecrafts-own-messages)).
- [Anonymous mode](anonymous) can also hide the real name inside titles, action bars, boss bars and menu titles written by other plugins.

Without it everything else keeps working, and the console tells you on startup which extras are off.

## Custom variables

Reusable text you can place in any format or message as `{name}`. Change it once here and every place that uses it updates.

```yaml
Variables:
  server_name: "<gradient:#240046:#9D4EDD:#E0AAFF>YourServer</gradient>"
  discord_link: "discord.gg/example"
  vip_tag: "js: player.hasPermission('chatalyst.vip') ? '<#D8B4FE>[VIP] ' : ''"
  name_len: "js: player.getName().length()"
```

A value starting with `js:` is a small JavaScript expression that runs per player. It only gets `player` and `playerName`, runs in a filtered engine that blocks dangerous classes, and is compiled once and cached.

```yaml
Scripting:
  Enabled: true
```

Set `Scripting.Enabled: false` if you do not use `js:` variables. The JavaScript engine is downloaded by Paper at startup; it is not part of the plugin jar.

## Custom commands

Simple commands that send a few lines and/or run console commands, such as `/discord` or `/web`.

```yaml
Command_Aliases:
  discord:
    Aliases: [dc]
    Permission: ""
    Messages:
      - "<#5865F2>Our Discord: <#FBF2FF><underlined>https://discord.gg/example"
    Messages_es:
      - "<#5865F2>Nuestro Discord: <#FBF2FF><underlined>https://discord.gg/example"
    Commands: []
  web:
    Aliases: [website, pagina]
    Messages:
      - "<#3DFFA8>Our website: <#FBF2FF><underlined>https://example.com"
    Messages_es:
      - "<#3DFFA8>Nuestra web: <#FBF2FF><underlined>https://example.com"
```

| Option | What it does |
| --- | --- |
| `Aliases` | Other names for the same command |
| `Permission` | Permission needed to use it (empty = everyone) |
| `Messages` / `Messages_es` | Lines sent to the player (English / Spanish clients) |
| `Commands` | Console commands to run |

> [!WARNING]
> Custom commands are registered when the server starts. Editing an existing one applies with `/chatalyst reload`, but adding or removing one needs a restart.

## Limits

- Chatalyst talks to other plugins through their public API or by detecting them. If one of them changes its API in a big update, the integration switches itself off instead of breaking your server, and the console says so.
- The voice bridge only runs the commands you configure. It cannot mute voice on a plugin that has no mute command.
- Staff security alerts and command audit trails are covered in [punishments](punishments#staff-audit).
