---
title: Configuration files
description: Where every Chatalyst setting lives, how the files are split by topic, and how to reload, check and repair your configuration safely.
---

Chatalyst keeps its settings in several small YAML files instead of one giant `config.yml`. Each file covers one topic (chat, moderation, players, the Discord bot…), so you only open the file for the feature you are changing, and every option carries a comment explaining what it does.

All files live in `plugins/Chatalyst/`. They are created with commented defaults the first time the server starts, and new options are added automatically when you update the plugin.

## The files at a glance

| File | What it controls |
| --- | --- |
| `config.yml` | License key, update notices, per-group overrides, database, default language |
| `chat.yml` | Chat format, channels, channel router, `/me`, `/say`, mentions, grammar, tab-complete hiding, restyled vanilla messages, unknown-command message |
| `moderation.yml` | Anti-spam, anti-bot, chat security, command blocker, mute scope, [staff suite](staff-suite), warning points, staff audit, audit logs, reports, command cooldowns |
| `players.yml` | AFK, private messages, mail, nicknames, `/tag`, chat color, fancy signs, `/itemname`, newcomers, spy, [anonymous mode](anonymous), marriage |
| `bot.yml` | Automatic announcer and the [auto-response bot](auto-responses) |
| `network.yml` | Staff help requests, proxy bridge, network admin center, Redis |
| `integrations.yml` | Rank catalog, [Discord bot](discord-bot), Tebex, voice chat, dynmap, PacketEvents, scripting, custom variables, custom commands, live translation |
| `store.yml` | One-time purchases: items, commands and money ([store](store)) |
| `economy.yml` | Trade plugins, market, `/trade`, balance top |
| `nametag.yml` | Floating nametags, tab list and the TAB plugin hook |
| `giveaway.yml`, `rentals.yml`, `gifts.yml` | Giveaways, perk rentals and gift boxes |
| `rules.yml` | The [rules engine](rules-engine) (anti-advertising, anti-caps…) |
| `swear.yml` | The [swear filter](chat-filter#swear-filter) |
| `books.yml` | Server [books](books) |
| `messages.yml`, `events.yml`, `tags.yml`, `tagfx.yml` | Join/quit/death messages, scheduled events, title catalog and tag effects |
| `formats/` | One file per chat format (`chat.yml`, `spy.yml`…) |
| `lang/` | Every player-facing message, one file per language ([localization](localization)) |
| `protected-names.txt` | Shared list of names nobody may use as a nickname (kept up to date by the plugin) |

Player data is stored in `data/` and the audit logs in `logs/` (see [storage](storage)).

> [!TIP]
> A section always lives in the same file. If you are unsure where an option is, search all the `.yml` files for its name: it appears in exactly one of them.

## config.yml

`config.yml` only keeps the core settings. This is the real file (comments shortened):

```yaml
License:
  Key: ""

Permissions_List:
  Grant_Command: "/lp group <group> permission set {permission} true"

Updates:
  Check: true
  Url: ""
  Notify_Admins: true
  Download_Url: ""

Groups:
  Enabled: true
  List: {}

Database:
  Enabled: false
  Type: SQLITE
  # ... see the Storage page

Locale: en_US
Auto_Detect_Language: true
```

| Option | Default | What it does |
| --- | --- | --- |
| `License.Key` | `""` | Your personal license key (see [Getting a license](license)) |
| `Permissions_List.Grant_Command` | LuckPerms command | What clicking a permission in `/chatalyst permissions` types into your chat. `{permission}` is the node |
| `Updates.Check` | `true` | Checks `Updates.Url` on startup for a newer version. Nothing is downloaded |
| `Updates.Url` | `""` | URL that returns the latest version as plain text (`1.2.0`) or JSON (`{"version":"1.2.0"}`). Empty disables the check |
| `Updates.Notify_Admins` | `true` | Tells admins about a new version when they join |
| `Updates.Download_Url` | `""` | Optional link shown in the update notice |
| `Groups.Enabled` | `true` | Turns per-group overrides on or off |
| `Groups.List` | `{}` | Per-group tweaks keyed by the LuckPerms primary group (Vault as fallback) |
| `Locale` | `en_US` | Default language for the console, broadcasts and clients without a matching file |
| `Auto_Detect_Language` | `true` | Each player sees messages in their own client language |

### Per-group overrides

`Groups.List` lets a rank use a different chat format or a different anti-spam delay. Only listed groups are affected; everyone else uses the global settings.

```yaml
Groups:
  Enabled: true
  List:
    vip:
      Format: chat-vip          # formats/chat-vip.yml
      Chat_Delay_Seconds: 0     # no delay between messages
    default:
      Chat_Delay_Seconds: 3
```

`Format` applies only to channels that use the base chat format; staff or trade channels keep their own. `Chat_Delay_Seconds` replaces the [anti-spam](chat-filter#anti-spam) delay for that group.

## Reloading changes

Most changes apply with:

```text
/chatalyst reload
```

`/cht` is the short form of `/chatalyst`. The reload swaps the whole configuration at once, so a message sent during the reload uses either the old settings or the new ones, never a mix.

A few things still need a full restart:

- Adding or removing a custom command in `Command_Aliases` (existing ones reload).
- `Database.Enabled`, `Database.Type`, `Database.Network_Shared` and `Database.Server_Id` (see [storage](storage#reload-and-restart)).

## Checking your configuration

| Command | What it does |
| --- | --- |
| `/chatalyst doctor` | Reads every file and lists problems: invalid materials, bad IDs, texts that are too long, missing values |
| `/chatalyst doctor discord` | Asks Discord itself whether the bot has the permissions, role position and intents it needs |
| `/chatalyst features` | Lists which features are on and, for each one that is off, the exact option and file that turns it on |
| `/chatalyst permissions [category] [page]` | Lists every permission by category; clicking one fills in the grant command |

All of them need `chatalyst.admin` (op by default).

## Updating and self-repair

When you install a new version, Chatalyst adds the new options to your existing files and keeps your values and comments. You never have to delete a file to get a new setting.

If a file has a YAML syntax error (a missing quote, a bad indent), the plugin does not crash:

- For `config.yml` it saves your broken file as `config-broken-<date>.yml`, starts with default settings, recovers your license key so the server still starts, and prints the exact line with the problem in the console.
- For the other files it saves a copy as `<file>.broken-<date>.bak` and loads the default version of that file.

Fix the line in the backup, copy it back, and run `/chatalyst reload`.

> [!WARNING]
> Always keep a copy of your configuration before big edits. The automatic backup protects you from syntax errors, not from a value you changed by mistake.

## Limits

- YAML is strict about indentation: use spaces, never tabs.
- Some texts come in pairs (`Message` / `Message_es`). Extra languages you add in `lang/` see the English side of those pairs; everything in `lang/` is fully translatable (see [localization](localization)).
- Adding or removing custom commands needs a restart, because the server builds its command list at startup.
