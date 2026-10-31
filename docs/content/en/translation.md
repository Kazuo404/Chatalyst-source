---
title: Live translation
description: Opt-in live chat translation into each player's client language through a LibreTranslate-compatible service, with caching and a safe fallback.
---

Live translation lets players who speak different languages read each other. A player who turns it on reads other-language chat in their Minecraft client's language. The sender and players who share the sender's language see the original message.

It only touches what players write in chat. It never translates the plugin's own messages, menus, commands, bot replies or moderation notices: those stay exactly as you wrote them.

Translation is opt-in twice: you enable it for the server, and each player chooses to turn it on for themselves.

## How to turn it on

1. Get a translation backend that speaks the LibreTranslate API. Two easy options:
   - Self-host [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate) (free, no key) and set `Provider_Url: "http://127.0.0.1:5000/translate"`.
   - Use a hosted LibreTranslate endpoint and paste its API key.
2. In `plugins/Chatalyst/integrations.yml`, fill in `Provider_Url`, set `Enabled: true` and run `/cht reload`. You can also do it from `/cht gui`.
3. "Chat translation" now appears in each player's `/toggle` menu. It starts off; players turn it on with the menu or `/toggle translate`.

```yaml
Translation:
  Enabled: false
  # Full URL of the LibreTranslate-compatible /translate endpoint.
  Provider_Url: ""
  # Optional API key (leave empty for a keyless self-hosted server).
  Api_Key: ""
  # Give up on a translation after this many ms (then that viewer sees the original).
  Timeout_Ms: 1500
  # Don't translate very short messages (emotes, "gg", "ok"): min characters.
  Min_Length: 3
  # How many recent (language, text) results to cache in memory.
  Cache_Size: 500
```

## Configuration

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns translation on. It stays off while `Provider_Url` is empty. |
| `Provider_Url` | `""` | Full URL of the `/translate` endpoint. |
| `Api_Key` | `""` | API key, if your provider needs one. |
| `Timeout_Ms` | `1500` | Wait limit per translation (300 to 10,000 ms). After it, that reader sees the original. |
| `Min_Length` | `3` | Messages shorter than this are never translated. |
| `Cache_Size` | `500` | Recent results kept in memory (32 to 10,000). Repeated phrases are instant. |

### How it works

- A translation is requested only when a player who turned it on reads a message and their client language differs from the sender's. A server where everyone speaks one language costs nothing.
- Each target language is translated once per message, however many players read it.
- A short delay (about 0.2 to 0.5 seconds) is normal while the text is fetched.
- If the service is down, slow or disabled, everyone simply sees the original. Chat never breaks.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/toggle translate` | `chatalyst.command.toggle` (true) | A player turns live translation on or off for themselves. |

## Limits

- While a message is delivered translated, it reaches players as a system line instead of a signed chat line. Logging, the [Discord bot](discord-bot), mentions, moderation and shadow mute keep working, but the staff delete button cannot retract those lines from clients. `/clearchat` and mutes work as usual.
- Only public chat is translated. Private messages, mail and signs are not.
- Translation quality depends entirely on your provider.
- Messages are sent to the translation service you configure. Choose a self-hosted instance if chat must not leave your infrastructure.
