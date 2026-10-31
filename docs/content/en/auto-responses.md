---
title: Auto responses
description: A chat bot that answers common player questions automatically, in each player's language, and only offers what your server really has.
---

New players ask the same questions again and again: how do I make money, how do I claim land, where is my purchase. The auto-response bot answers them for you. When a message looks like a known question, the bot replies a moment later with the answer you wrote, in the player's own language.

It understands how people really type. "komo gano din3ro!!!" and "how do i make money" reach the same answer. And its answers can adapt to your server: a reply about money only mentions `/jobs` if `/jobs` exists, and only mentions the market if your market is on.

Chatalyst ships with 44 ready-made answers in English and Spanish (money, balance, private messages, mail, channels, reports, Discord, ranks, homes, warps, land claims, kits, voting, PvP, lag and more). Edit, delete or add your own in `bot.yml`.

## Turning it on

It is on by default. The global settings are at the top of the `Auto_Responses` section in `bot.yml`:

```yaml
Auto_Responses:
  Enabled: true
  Bypass_Permission: "chatalyst.bypass.autoresponse"
  Auto_Language: true
  Language: "en"
  Private_Questions: true
  Player_Cooldown_Seconds: 60
  Global_Cooldown_Seconds: 10
  Delay_Ticks: 15
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `true` | Turns the whole bot on or off |
| `Bypass_Permission` | `chatalyst.bypass.autoresponse` | Messages from players with this permission never trigger the bot (op by default, so staff do not set it off) |
| `Auto_Language` | `true` | Each player gets the answer in their client language (English or Spanish) |
| `Language` | `en` | Fixed language (`en` or `es`) used when `Auto_Language` is `false` |
| `Private_Questions` | `true` | A recognized question is not sent to public chat. Only the asker sees their question and the answer, which keeps chat clean |
| `Player_Cooldown_Seconds` | `60` | Default wait before the same player gets the same answer again |
| `Global_Cooldown_Seconds` | `10` | Default wait before the same answer is given again to anyone |
| `Delay_Ticks` | `15` | Short pause before answering, so it feels natural (20 ticks = 1 second) |

## Writing a response

Each entry under `Responses` needs at least one way to detect the question and one answer. This one comes from the real file:

```yaml
    mail:
      Keywords:
        - "correo|mail|inbox|buzon|carta|offline|desconect"
        - "mand|envi|escrib|dej|send|write|leave|como|komo|how"
      Triggers:
        - "how do i send mail"
        - "offline message"
        - "regex:\\bc[oó]mo (mando|envio|env[ií]o) (correo|un mensaje a alguien offline)\\b"
      Reply:
        - "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Open your mailbox with <#E0AAFF>/inbox<#C0B3D9>: you can write to players even when they are offline."
      Reply_es:
        - "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Abre tu buzon con <#E0AAFF>/inbox<#C0B3D9>: puedes escribir a jugadores aunque esten desconectados."
      To: player
```

| Key | What it does |
| --- | --- |
| `Triggers` | Text the message must contain, or a full regular expression with the `regex:` prefix. Write both languages |
| `Keywords` | Flexible detection. Each line is a group of words separated by a vertical bar; the response fires when the message contains one word from **every** group |
| `Reply` / `Reply_es` | The answer lines for English and Spanish clients. If one is missing, the other is used. `{player}` is the asker's name |
| `If_Plugin` | Alternative replies used only when a given plugin is installed |
| `Smart` | An answer built from parts that depend on what your server has (see below) |
| `To` | `player` answers only the asker; `everyone` sends it to the whole server, each viewer in their language |
| `Player_Cooldown_Seconds`, `Global_Cooldown_Seconds`, `Delay_Ticks` | Override the global values for this response |

A response fires if **any** of its detections matches. Before matching, the message is normalized: lowercase, no accents, leet speak undone (`din3r0` becomes `dinero`), stretched letters shortened (`holaaaa` becomes `hola`) and punctuation turned into spaces.

> [!TIP]
> In `Keywords`, use short word stems like `gan`, `consig` or `earn`. A stem catches every form of the word: `gano`, `ganar`, `ganando`.

## Smart replies

A smart reply is built from parts, and each part is only said if what it `Needs` is available right now. If no part applies, the `Unavailable` line is used instead. A part with no `Needs` is always said.

```yaml
    how-to-money:
      Keywords:
        - "dinero|plata|money|coin|coins|lana|feria|billete|cash|oro|eco|bucks|dosh|moolah|dough|rich"
        - "gan|consig|hag|hac|sac|junt|farm|earn|make|get|neces|need|wanna|gimme|grind|start"
      Smart:
        Intro: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Hi <#FBF2FF>{player}<#C0B3D9>, ways to earn money here:"
        Intro_es: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Hola <#FBF2FF>{player}<#C0B3D9>, formas de ganar dinero aqui:"
        Parts:
          - Needs: "command:jobs"
            Text: "<#C0B3D9>• Work a job with <#E0AAFF>/jobs<#C0B3D9>."
            Text_es: "<#C0B3D9>• Trabaja un empleo con <#E0AAFF>/jobs<#C0B3D9>."
          - Needs: "market"
            Text: "<#C0B3D9>• Sell your own items on <#E0AAFF>/market<#C0B3D9>."
            Text_es: "<#C0B3D9>• Vende tus propios items en <#E0AAFF>/market<#C0B3D9>."
        Unavailable: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>There's no money system enabled on this server yet."
        Unavailable_es: "<#7B68A0>[<#B56CFF>Bot<#7B68A0>] <#C0B3D9>Aun no hay un sistema de dinero activo en este servidor."
      To: player
```

What a part can need:

| Needs | Available when |
| --- | --- |
| `command:<name>` | That command exists on the server, e.g. `command:jobs` |
| `plugin:<Name>` | That plugin is installed and enabled (name as shown in `/plugins`) |
| `pm` | Private messages are on |
| `mail` | Mail is on |
| `market` / `trade` | The market / direct trading is on |
| `channels` | Chat channels are on |
| `announcements` | The announcer is on |
| `economy` / `baltop` | An economy plugin is present / the money ranking is on |
| `store` / `ranks` | Item deliveries / the rank storefront are on |
| `rent` | Perk rentals are on, with an economy and at least one offer |
| `discordlink` | Discord account linking is actually usable |

In reply texts, `{ranks_command}` is replaced with whatever your storefront command is called (see [store](store)). Availability is checked each time the bot answers, so turning a feature on or off applies right away.

## How it fits with the rest of the chat

The bot only answers messages that got through: if the [rules engine](rules-engine) or the [anti-spam](chat-filter#anti-spam) blocked the message first, the bot stays silent. The swear filter and logging still apply to the question as usual.

## Limits

- Replies come in English and Spanish only. Players with other client languages get the English reply.
- The bot answers questions in chat; it does not hold a conversation or remember what a player asked before.
- Detection is based on words and patterns you write. If players ask in a way none of your triggers cover, add a trigger or a keyword.
