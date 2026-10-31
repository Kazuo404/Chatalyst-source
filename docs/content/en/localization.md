---
title: Languages & messages
description: Every player sees Chatalyst in their own Minecraft language. Edit any message, add new languages, and understand how English and Spanish texts work in the config.
---

Chatalyst speaks each player's language. A player whose Minecraft client is set to Spanish reads every message, menu and notice in Spanish, while the player next to them reads the same thing in English. You do not configure anything per player: the plugin reads the language the client reports.

Every text the plugin shows lives in the `lang/` folder, so you can reword any message without touching code. English (`en_US`) and Spanish (`es_ES`) ship complete, and you can add any other language by dropping in a new file.

## How the language is chosen

In `config.yml`:

```yaml
Locale: en_US
Auto_Detect_Language: true
```

| Option | Default | What it does |
| --- | --- | --- |
| `Locale` | `en_US` | The default language. Used for the console, for server-wide broadcasts and for any player whose client language has no file |
| `Auto_Detect_Language` | `true` | `true`: each player sees their own client language when a matching file exists. `false`: everyone sees `Locale` |

When a player joins, Chatalyst looks for a file in this order:

1. The exact client language, for example `pt_BR.yml`.
2. The same language from any country, for example `pt_PT.yml` for a `pt_BR` client.
3. The default `Locale`.

The language follows the client live: if a player changes their Minecraft language, the next message already uses the new one.

## Editing messages

Open `plugins/Chatalyst/lang/en_US.yml` (or `es_ES.yml`), change the text, and run `/chatalyst reload`. Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html), so colors, gradients, hover text and click actions all work:

```yaml
common:
  no-permission: "<#FF4D6D>You don't have permission to do that."
```

Words in braces such as `{player}` or `{id}` are filled in by the plugin. Keep them in your text; you can move them around the sentence.

> [!TIP]
> You do not need to keep a full copy up to date. If a key is missing from your file (for example, a message added in a new version), Chatalyst uses the built-in text for that language, and English as a last resort. An update never shows a raw key because your file is older.

## Adding a new language

1. Copy `lang/en_US.yml` to a new file named after the Minecraft locale, for example `lang/fr_FR.yml`, `lang/de_DE.yml` or `lang/pt_BR.yml`.
2. Translate the values. Leave the keys (the part before the colon) unchanged.
3. Run `/chatalyst reload`.

Players with that client language see the new file automatically. Anything you have not translated yet falls back to English.

## Texts inside the configuration files

Some texts are written directly in the config files instead of `lang/`: the auto-response bot replies, announcements, ban and kick screens, rule warnings, store cards and similar. These come in pairs:

```yaml
Warn: "<#FF4D6D>Watch your language."
Warn_es: "<#FF4D6D>Cuida tu lenguaje."
```

A Spanish client (any Spanish variant) sees the `_es` version; every other client sees the base version. If you delete the `_es` line, the base text is used for everyone. When the pair is a list (for example `Messages` and `Messages_es`), a missing Spanish list falls back as a whole, so a player never sees half one language and half the other.

> [!NOTE]
> These config pairs only know English and Spanish. A French client sees the English side of them. Everything in `lang/` is fully translatable into any language.

## Minecraft's own messages

With [PacketEvents](integrations#packetevents) installed, Chatalyst restyles Minecraft's plain grey command feedback (`/tp`, `/give`, `/gamemode`, `/time`…) in each client's language. The templates are in the `vanilla:` section of each lang file. Set a template to `""` to keep the original Minecraft text for that message. To leave specific messages alone, list their keys in `chat.yml`:

```yaml
Vanilla_Messages:
  Enabled: true
  Ignore: []
```

The unknown-command message (`Unknown_Command` in `chat.yml`) is also taken from the lang files, and it shows the same text for unknown, forbidden and hidden commands, so it never reveals that a command exists.

## Bilingual commands

Many commands answer to both an English and a Spanish name: `/anonymous` and `/anonimo`, `/market` and `/mercado`, `/support` and `/soporte`, `/freeze` and `/congelar`. See [all commands](commands) for the full list of aliases.

## Limits

- The language comes from the client setting. A player who plays with an English client but prefers Spanish sees English unless you set `Auto_Detect_Language: false` and `Locale: es_ES` for everyone.
- Only `lang/` files support extra languages. The `_es` pairs in the config files cover English and Spanish only.
- Chat typed by players is never translated by this system. For that, see [live translation](translation).
