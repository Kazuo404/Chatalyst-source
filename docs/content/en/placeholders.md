---
title: Placeholders
description: Every {placeholder} you can use in Chatalyst formats and messages, your own custom variables, and the %chatalyst_...% placeholders for PlaceholderAPI and TAB.
---

Placeholders are words in braces that Chatalyst replaces with live data: `{player_nick}` becomes the player's nickname, `{ping_ms}` their colored latency. You use them in [chat formats](formats), the tab list, [nametags](nametags), join messages and most other texts.

Chatalyst also works the other way round: it publishes `%chatalyst_...%` placeholders so PlaceholderAPI, TAB, scoreboards and other plugins can show Chatalyst data.

## How to turn it on

Internal `{...}` placeholders always work. To use `%...%` placeholders from other plugins in Chatalyst texts, or `%chatalyst_...%` in other plugins, install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/). Chatalyst registers its expansion automatically; there is nothing to download.

## Configuration

### Player placeholders

| Placeholder | Value |
|---|---|
| `{player_name}` / `{player}` | The account name. |
| `{player_nick}` | The nickname with its colors, or the name if there is none. |
| `{rank_nick}` | The nickname colored with the player's nick color, or with the rank color if they chose none. |
| `{nick_color}` | The player's nick color as a MiniMessage tag, or empty. |
| `{player_display}` | The Bukkit display name as plain text. |
| `{player_prefix}` / `{player_suffix}` | Rank prefix and suffix from LuckPerms, or Vault as fallback. |
| `{player_group}` | The internal primary group id (for example `mvpplusplus`). |
| `{rank_tag}` | The rank prefix, or the group name when the group has no prefix. |
| `{rank_color}` | The first color of the rank prefix. |
| `{rank_gradient}` | A neon gradient built from the rank color. Always opens a `<gradient>` tag. |
| `{world}` | The world name. |
| `{ping}` | Latency in milliseconds, as a plain number. |
| `{ping_ms}` | Latency as a number colored by quality (`23ms` green, `320ms` red). |
| `{ping_quality}` | Excellent, Good, Fair, Slow or Laggy. |
| `{ping_bars}` | Colored signal bars. |
| `{party}` / `{party_tag}` | The Parties party name and the decorated tag from `Chat.Party_Tag`. Empty without a party. |
| `{afk}` / `{afk_tag}` | `yes`/`no`, and the AFK suffix or empty. |
| `{tag_prefix}` / `{tag_suffix}` | The equipped [title](tags) or personal tag, on its side. |
| `{nametag_title}` | The floating [nametag](nametags) title. |
| `{nametag_icon_left}` / `{nametag_icon_right}` | The nametag side icons. |
| `{married_to}` / `{married_tag}` | The spouse's name and the hover line from `Marriage.Tag`. Empty for single players. See [Marriage](marriage). |
| `{msg_command}` | What clicking the name suggests: `/inbox send <name>` or `/msg <name>`. |
| `{ranks_command}` | The rank store command as it is currently named. |

### Message placeholders

| Placeholder | Where | Value |
|---|---|---|
| `{message}` | Chat formats | The player's message. It is inserted safely: a player cannot inject formatting through it. |
| `{message_uuid}` | Chat formats | Id used by the delete button. |
| `{sender_name}` / `{receiver_name}` | Private message formats | Both sides of a [private message](private-messages). |
| `{$label}` | Any format | A translated label from the language files, for example `{$rank}` or `{$ping}`. Each reader sees their own language. |

### Custom variables

Define reusable text once in `integrations.yml` and use it anywhere as `{name}`:

```yaml
Variables:
  server_name: "<gradient:#240046:#9D4EDD:#E0AAFF>YourServer</gradient>"
  discord_link: "discord.gg/example"
  # SCRIPT variables (JavaScript). Start the value with "js:". You get 'player'
  # (the player) and 'playerName'. Examples:
  #   {vip_tag}  ->  shows [VIP] only if the player has the permission.
  vip_tag: "js: player.hasPermission('chatalyst.vip') ? '<#D8B4FE>[VIP] ' : ''"
  #   {name_len} ->  the length of the player's name.
  name_len: "js: player.getName().length()"
```

A value that starts with `js:` is a small JavaScript expression. It only gets `player` and `playerName`, and dangerous classes are blocked. Script variables need `Scripting.Enabled: true` (the default) and the Nashorn library, which Paper downloads on startup.

### PlaceholderAPI placeholders

With PlaceholderAPI installed, other plugins can read:

| Placeholder | Value |
|---|---|
| `%chatalyst_nick%` | Nickname, or name. |
| `%chatalyst_nickcolor%` | Primary nick color as a legacy code (`&c` or `&#RRGGBB`). |
| `%chatalyst_channel%` | The channel the player writes in. |
| `%chatalyst_chatcolor%` | The chosen chat color tag. |
| `%chatalyst_muted%` / `%chatalyst_mute_reason%` | `yes`/`no` and the mute reason. |
| `%chatalyst_warnings%` | Warning points. |
| `%chatalyst_afk%` | `yes`/`no`. |
| `%chatalyst_prefix%` / `%chatalyst_suffix%` / `%chatalyst_group%` | Rank prefix, suffix and group. In [anonymous mode](anonymous) they show the disguise instead. |
| `%chatalyst_anonymous%` | `yes`/`no`. |
| `%chatalyst_partner%` / `%chatalyst_married%` | Spouse's name and `yes`/`no`. |
| `%chatalyst_fx_<name>%` / `%chatalyst_fx_auto%` | Current frame of an animated rank tag. See [Tags, titles & effects](tags). |
| `%chatalyst_title_id%`, `_name`, `_text`, `_prefix`, `_suffix`, `_rarity`, `_category`, `_position`, `_season`, `_points` | The equipped title. |
| `%chatalyst_achievement_points%`, `%chatalyst_achievements_earned%`, `_total`, `_percent` | Achievement progress. |
| `%chatalyst_collections_completed%` / `%chatalyst_collections_total%` | Collection progress. |

> [!TIP]
> For TAB and scoreboards, prefer `%chatalyst_prefix%` over `%luckperms_prefix%`: it does not reveal the real rank of a player in anonymous mode.

### TAB placeholders

When the TAB plugin is installed, Chatalyst registers `%chatalyst_nick%`, `%chatalyst_nick_plain%`, `%chatalyst_channel%`, `%chatalyst_muted%`, `%chatalyst_warnings%` and `%chatalyst_chatcolor%` directly in TAB, without PlaceholderAPI. See [Floating nametags](nametags).

## Commands and permissions

Placeholders have no commands or permissions of their own.

## Limits

- An unknown `{token}` is left as it is, so typos are easy to spot.
- Placeholders are replaced in one pass. A value that itself contains `{...}` (for example a nickname) is never replaced again, which prevents players from injecting placeholders.
- In [anonymous mode](anonymous), rank, tags, party, marriage and nametag placeholders are replaced by the disguise or left empty, and `{player_name}` becomes the alias.
