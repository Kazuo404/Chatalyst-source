---
title: Private messages
description: Instant /msg and /reply between online players, with ignore lists, a personal off switch, staff spy and an optional fallback to mail.
---

Private messages are instant, one-to-one conversations between players who are online on the same server. They come with a persistent ignore list, a personal switch to stop receiving them, their own formats and optional visibility for staff through spy.

Private messages ship **disabled**. Many servers prefer [Mail](mail) only, which also reaches offline players. Mail keeps working whether private messages are on or off.

## How to turn it on

In `plugins/Chatalyst/players.yml`:

```yaml
Private_Messages:
  Enabled: false
```

Set `Enabled: true` and run `/cht reload`. While it is `false`, `/msg`, `/reply` and `/ignore` only answer that private messages are disabled.

## Configuration

| Option | File | Default | What it does |
|---|---|---|---|
| `Private_Messages.Enabled` | `players.yml` | `false` | Master switch for `/msg`, `/reply` and `/ignore`. |
| `Mail.Msg_Fallback` | `players.yml` | `false` | Sends a `/msg` to the recipient's mailbox when they turned private messages off. |
| `Chat.Name_Click_Prefer` | `chat.yml` | `mail` | `msg` makes clicking a name in chat suggest `/msg <player>` (only while private messages are on). |
| `Chat.Ignore.Hide_In_Chat` | `chat.yml` | `true` | `/ignore` also hides that player's public chat behind a "[show]" marker. |

### Formats

The sender and the receiver see different formats, stored in `formats/pm-sender.yml` and `formats/pm-receiver.yml`:

```yaml
Parts:

  from:
    Message: "<#FBF2FF>{sender_name} <#7B68A0>» <reset>"
    Hover:
      - "<#C0B3D9>{$pm-reply} {sender_name}"
    Suggest_Command: "/r "

  message:
    Message: "<#C0B3D9>{message}"
```

Placeholders: `{sender_name}`, `{receiver_name}` and `{message}`. Staff spy uses `formats/spy.yml`. See [Chat formats](formats) for every part field.

### Delivery rules

| Situation | What happens |
|---|---|
| The owner disabled private messages | `/msg`, `/reply` and `/ignore` are unavailable. |
| The recipient turned private messages off (`/toggle pm`) | The message is not delivered. With `Mail.Msg_Fallback: true` it goes to their mailbox instead and the sender is told. |
| The recipient ignores the sender | The message is silently dropped. It never falls back to mail. |
| The sender is muted | Private messages are blocked for the duration of the mute. |
| The recipient is vanished | Only staff with `chatalyst.bypass.reach` can reach them. |

Staff with `chatalyst.bypass.reach` reach players who ignore them or turned private messages off.

### Mail fallback

```yaml
  Msg_Fallback: false
```

When on, a `/msg` to a player who disabled private messages lands in their mailbox. All mailbox rules still apply: their receive-mail setting, blocked senders, the spam folder and the send cooldown.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/msg <player> <message>` (`/m`, `/w`, `/tell`, `/whisper`) | `chatalyst.command.msg` (true) | Sends a private message. |
| `/reply <message>` (`/r`) | `chatalyst.command.msg` (true) | Replies to your last conversation. |
| `/ignore <player>` | `chatalyst.command.ignore` (true) | Ignores or unignores a player. The list is saved. |
| `/toggle pm` | `chatalyst.command.toggle` (true) | Stops or resumes receiving private messages. |
| `/spy` | `chatalyst.spy` or `chatalyst.spy.pm` (op / false) | Staff: see other players' private messages. See [Spy, logs & combat log](spying). |

`chatalyst.bypass.reach` (op) lets staff message players who ignore them, have private messages off or are vanished. Nicknames are accepted wherever a player name is expected.

## Limits

- Private messages only reach players online on the same server. For offline players or other servers, use [Mail](mail).
- A private message is not stored. If you need a record, enable private-message logging, see [Spy, logs & combat log](spying).
- A player in [anonymous mode](anonymous) is shown by their alias to the other side. Staff spy still shows the real name.
