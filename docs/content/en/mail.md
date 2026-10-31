---
title: Mail
description: An in-game mailbox for online and offline players, with a GUI inbox, book-and-quill writing, friends, a spam folder and join notifications.
---

Mail lets players write to each other even when the other player is offline. The inbox is a menu: each mail shows the sender's head, nickname and a preview. Clicking one opens it as a written book with **Reply** and **Delete** buttons. Writing uses the vanilla book and quill.

Mail is independent from [Private messages](private-messages). It is on by default, and it is what clicking a player's name in chat suggests out of the box.

## How to turn it on

Mail is enabled by default in `plugins/Chatalyst/players.yml`:

```yaml
Mail:
  Enabled: true
  Send_Cooldown_Seconds: 3
  Max_Message_Length: 1024
  Max_Messages: 100
  Msg_Fallback: false
  Require_Friend: false
  Notifications:
    Enabled: true
    Toast: true
    Sound:
      Enabled: true
      Key: "minecraft:block.note_block.chime"
      Volume: 0.65
      Pitch: 1.35
```

## Configuration

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Master switch for the mailbox. |
| `Send_Cooldown_Seconds` | `3` | Seconds a player must wait between two mails. `0` disables it. |
| `Max_Message_Length` | `1024` | Maximum characters per mail. Kept between 64 and 4096. |
| `Max_Messages` | `100` | Mails kept per folder (inbox and spam). When full, the oldest is dropped. `0` or less = unlimited. |
| `Msg_Fallback` | `false` | Delivers a `/msg` to the mailbox when the recipient turned private messages off. |
| `Require_Friend` | `false` | `true` = mail from non-friends goes to the spam folder instead of the inbox. |
| `Notifications.Enabled` | `true` | Notifies new mail with a clickable chat line and action bar. |
| `Notifications.Toast` | `true` | Also shows an advancement-style toast. |
| `Notifications.Sound.*` | chime, `0.65`, `1.35` | Notification sound, volume and pitch. |

Players with unread mail are also notified when they join.

### Friends, spam and blocking

- **Friends.** Players send friend requests with `/inbox friend <player>`. The request arrives with clickable **Accept** and **Deny** buttons. The friends menu shows real skins and current nicknames.
- **Spam folder.** With `Require_Friend: true`, mail from strangers is not refused, it just goes to spam so it does not interrupt.
- **Blocking.** Any sender can be blocked from a mail. Blocked senders are managed from the settings menu.
- **Privacy.** Each player can turn off receiving mail from the settings menu.

## Commands and permissions

All mail commands need `chatalyst.command.mail` (default `true`).

| Command | What it does |
|---|---|
| `/inbox` | Opens the inbox menu. |
| `/inbox spam` | Opens the spam folder. |
| `/inbox friends` | Opens the friends menu. |
| `/inbox friend <player>` | Sends a friend request. |
| `/inbox accept <player>` / `/inbox deny <player>` | Answers a friend request. |
| `/inbox send <player> <message>` | Sends a mail from the command line. |
| `/inbox read` | Prints your inbox in chat and marks it as read. |
| `/inbox reply <message>` | Replies to the newest mail. |
| `/inbox clear` | Empties your inbox. |

`chatalyst.bypass.mailcooldown` (op) skips the send cooldown. The command is `/inbox` on purpose: it does not collide with EssentialsX's `/mail`.

## Limits

- Mail is stored in your storage backend. With a shared MySQL database it follows players across the network, see [Storage & database](storage).
- A muted player cannot send mail while the mute lasts.
- A player in [anonymous mode](anonymous) sends mail under their alias.
