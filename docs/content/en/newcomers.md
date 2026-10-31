---
title: Newcomers
description: Welcome players on their first join with a server-wide greeting in each reader's language and console commands such as a starter kit.
---

First impressions matter. When someone joins your server for the first time, Chatalyst can announce it to everyone ("Let's welcome Steve, new on the server!") and run console commands for them, such as giving a starter kit or a starting permission.

This only fires on a player's very first join. Returning players are not affected.

## How to turn it on

The newcomer module is off by default. In `plugins/Chatalyst/players.yml`, set `Enabled: true` and run `/cht reload`:

```yaml
Newcomer:
  Enabled: false
  # true = every online player sees the welcome in their own client language.
  # false = use Welcome_Broadcast exactly as written for everyone.
  Localized_Broadcast: true
  Welcome_Broadcast: "<#FFB627>Let's welcome <#FFC933>{player_name}<#FFB627>, new on the server!"
  Commands: []
```

## Configuration

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns the newcomer welcome on. |
| `Localized_Broadcast` | `true` | Each online player reads the welcome in their own language, from the `newcomer.welcome-broadcast` key of the language files. |
| `Welcome_Broadcast` | "Let's welcome {player_name}, new on the server!" | Used as written for everyone when `Localized_Broadcast` is `false`. Leave it empty to send no welcome at all. |
| `Commands` | `[]` | Console commands run once for the new player. |

### The welcome message

With `Localized_Broadcast: true`, the text comes from `lang/en_US.yml` and `lang/es_ES.yml` (key `newcomer.welcome-broadcast`), so edit it there. It accepts `{player_name}`, `{world}` and `{ping}`. See [Languages & messages](localization).

With `Localized_Broadcast: false`, `Welcome_Broadcast` is sent as written and accepts every [placeholder](placeholders), including PlaceholderAPI ones.

### Commands

Commands run from the console, one after another, and accept the same placeholders. For example:

```yaml
  Commands:
    - "kit starter {player_name}"
    - "lp user {player_name} permission set essentials.home true"
```

### First-join MOTD

The private message a player sees on join is a separate feature. Its first-join variant is `Motd.First_Time` in `messages.yml`, and players can show it again with `/motd first`. See [Languages & messages](localization).

## Commands and permissions

This module has no commands of its own. `/motd [first]` (permission `chatalyst.command.motd`, default `true`) shows the join MOTD again.

## Limits

- "First join" means Minecraft has never saved data for that player on this server. On a network, each backend decides on its own, so a player can be greeted once per server.
- With `Localized_Broadcast: true`, the `Welcome_Broadcast` text is not shown; it only needs to be non-empty to keep the welcome on.
