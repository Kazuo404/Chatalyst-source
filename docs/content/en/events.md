---
title: Scheduled events
description: Announce upcoming server events, list them with /events, remind everyone before they start and tell joining players, all managed from a menu.
---

Scheduled events tell your players what is coming: a boss fight on Saturday at 20:00, a build contest, a drop party. Players see the upcoming events with `/events`, get a "starting soon" reminder before each one, and are told about nearby events when they join.

Staff create and edit events from a menu, so nobody has to type dates by hand.

## How to turn it on

Events are always available and the list starts empty. Staff with `chatalyst.command.events.admin` open `/events admin` and create the first event from the menu: name, day, hour, minute, description and reminder, all with clicks.

The settings live in `plugins/Chatalyst/events.yml`:

```yaml
Settings:
  Reminder_Enabled: true   # broadcast "starting soon" once, Remind_Minutes before each event
  Join_Notice_Hours: 24    # tell joining players about events within this many hours (0 = off)
  Announce_To: everyone    # everyone = broadcast the reminder to the whole server · none = off

Events: {}
```

## Configuration

### Settings

| Option | Default | What it does |
|---|---|---|
| `Reminder_Enabled` | `true` | Broadcasts a "starting soon" reminder once, `Remind_Minutes` before each event. |
| `Join_Notice_Hours` | `24` | Joining players are told about events within this many hours. `0` = off. |
| `Announce_To` | `everyone` | `everyone` broadcasts reminders to the whole server. `none` turns them off. |

### Events

Each event lives under `Events.<id>`. The menu writes them for you, but you can also edit them by hand:

| Field | What it does |
|---|---|
| `Name` | The event name (also used for English clients). |
| `Name_es` | Optional Spanish name. |
| `Description` | Optional one-line description. |
| `When` | Date and time as epoch milliseconds. The menu sets it for you. |
| `Remind_Minutes` | Minutes before the event to send the reminder. `0` = no reminder. |
| `Reminded` | Managed automatically: `true` once the reminder was sent. |

Players with a Spanish client see `Name_es` when it is set; everyone else sees `Name`. Names are limited to 128 characters and descriptions to 512.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/events` (`/event`, `/eventos`) | `chatalyst.command.events` (true) | Lists upcoming events with their date and time left. |
| `/events admin` | `chatalyst.command.events.admin` (op) | Opens the event manager. |

## Limits

- An event happens once. There are no repeating events: create a new one for the next date.
- Events are reminders and announcements only. They do not run commands or start anything by themselves.
- Dates are shown in the server's time zone.
