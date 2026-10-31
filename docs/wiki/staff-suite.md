# Staff Moderation Suite

A complete, GUI-first moderation toolkit: kick, ban, tempban, connection (IP) bans, mute
(with shadow mutes), warns with automatic escalation, freeze reviews, staff notes, vanish,
inventory inspection and a punishment history per player. Everything is audited and every
player-facing text is localized (each viewer reads their own language).

## Opt-in by design

The suite ships **disabled** (`Staff_Moderation.Enabled: false` in `moderation.yml`) so
Chatalyst never competes with a staff plugin the server already uses (other moderation or staff plugins). While disabled, none of the staff commands are even registered.

With the suite enabled, `Command_Priority: true` (the default) makes Chatalyst claim the
plain labels (`/ban`, `/kick`, `/vanish`...) even if another plugin registered them first;
the displaced command is remembered and restored if you disable the module again on
reload. Set it to `false` to only take free labels (the namespaced `/chatalyst:ban` forms
always work).

`Exempt_Ops: true` (default) makes operators unpunishable and unfreezable. Set it to
`false` on small servers where every trusted player keeps OP and must still be
moderatable.

## Commands

Every tool has bilingual aliases — each command works in English and Spanish.

| Command | Aliases | Purpose |
| --- | --- | --- |
| `/staff [menu\|on\|off\|players]` | `/staffmode`, `/modmode` | Staff mode and the moderation dashboard |
| `/kick <player> [reason]` | `/expulsar` | Fancy kick with colors, gradients and named styles |
| `/warn <player> <reason>` | `/amonestar`, `/advertir` | Warn: adds warning points and records history |
| `/note <player> <text>` | `/nota` | Attach a staff note (works offline) |
| `/ban <player> [reason]` | `/banear` | Permanent ban with a fancy screen |
| `/tempban <player> <time> [reason]` | `/bantemporal` | Temporary ban |
| `/unban <player>` | `/pardon`, `/desbanear` | Remove a Chatalyst or native profile ban |
| `/banip <player\|ip> [time] [reason]` | `/ipban`, `/banearip` | Ban the account and its connection (blocks alts) |
| `/unbanip <player\|ip>` | `/ipunban`, `/desbanearip` | Remove a connection ban |
| `/freeze <player> [reason]` | `/congelar` | Freeze a player for a staff review |
| `/unfreeze <player>` | `/descongelar` | Release a player from a review |
| `/vanish [player] [on\|off]` | `/v`, `/esfumarse` | Toggle staff vanish |
| `/invsee <player>` | `/verinv` | Review (and optionally edit) an inventory |
| `/echest <player>` | `/ec`, `/verender` | Review (and optionally edit) an ender chest |
| `/seen <player>` | `/visto`, `/lastseen` | When a player was last seen |
| `/check <player>` | `/checar` | Punishment and status summary |
| `/tpto <player>` | `/goto`, `/irhacia` | Teleport yourself to a player |
| `/tphere <player>` | `/bring`, `/traer` | Bring a player to you |
| `/clearinventory <player> [all\|ender]` | `/clearinv`, `/ci`, `/limpiarinv` | Clear inventory, equipped armor, off hand and cursor (`ender`/`all` also clears the ender chest) |

`/mute <player> [time] [reason]` (`-s` for shadow mute) and `/unmute` are part of the
chat moderation set and work with or without the suite.

## GUI-first moderation

Staff never has to type commands:

- **Dashboard** (`/staff menu`): online player list (click goes straight to the manage
  menu), frozen-players list, reports and tools.
- **Manage menu**: kick, ban, mute, freeze, warn, notes, history, alts and inventory
  buttons for one player.
- **Reason presets** (`Reason_Presets` in `moderation.yml`) or a **custom reason typed in
  chat** that is captured privately — it never reaches public chat. Colors, gradients and
  named styles are allowed exactly like in a command.
- **Ban composer**: duration (permanent first, then `Ban.Gui_Durations`), reason, visual
  style and an exact preview of the ban screen before confirming.
- **Mute composer**: the same experience — durations from `Mute.Gui_Durations`, presets or
  chat-typed reasons with styles, preview — plus a **shadow mute** toggle (`-s`: the muted
  player does not know).
- **Offline manage menu**: moderate players who are not connected, including connection
  bans.
- Every confirmation dispatches the real command, which re-verifies permissions, protects
  bypass-protected targets and writes the audit log.

## Punishments

- **Kick / ban / tempban** show localized fancy screens configured in `moderation.yml`
  (`Kick.Screen`, `Ban.Screen` + `_es` variants). The ban screen supports `{remaining}`
  (full live countdown — years, months, days, hours, minutes, recalculated on every login
  attempt) and `{expires}` (exact date and time).
- **Warns** (`/warn`) add `Warning_Points.Manual_Warn_Points` points (0 = record only),
  respect `chatalyst.bypass.warn` and land in the punishment history. Points decay per
  `Warning_Points.Decay_After`.
- **Automatic escalation** (`Staff_Moderation.Escalation`): crossing `Kicks_For_Ban`
  recent kicks (within `Kick_Window`) or `Warn_Points_For_Ban` warning points applies an
  automatic ban of `Ban_Duration` with a localized default reason, announces it to staff
  and records it in the history. Either trigger can be disabled with `0`.
- **Punishment history**: the last 20 entries per player (ban, tempban, kick, freeze,
  mute, FreezeGuard ban, escalation ban) are shown by `/check` (last 3) and in full by the
  history menu, newest first, read-only.

## Freeze reviews

`/freeze` starts a staff review:

- Works **offline**: the review starts on the target's next login.
- Full immobilization: no walking, jumping, camera turns or mounts.
- The frozen player's only channel is the **private review chat** with the freezing staff
  member (plus `/support` if the reviewer is offline). `Allowed_Commands` controls the
  short whitelist; `Restrict_Chat: true` keeps their chat out of the public eye.
- A full-screen notice (localized, with a Discord review button when
  `Discord_Invite_URL` is set) warns: **do not disconnect**.
- Leaving in any way (quit, connection loss, client kill) triggers a **permanent ban**
  after `Disconnect_Grace_Seconds` (default 3s — just enough for a proxy transfer or an
  instant reconnect).
- Staff alerts include a clickable **[GO TO THEM]**; the dashboard lists frozen players
  and left-click shows the reason.

## Connection (IP) bans and alts

- `/banip` bans the account **and** its SHA-256-hashed connection, kicks everyone sharing
  it and rejects future logins with the fancy ban screen. With
  `Ip_Ban.Ban_New_Accounts: true`, new accounts joining through a banned connection are
  banned durably too — switching IP afterwards no longer saves the alt. Staff is alerted
  either way (IpGuard).
- **No raw IP is ever stored** — only its hash, enough to group names that join from the
  same connection.
- With `Track_Alts: true`, `/check` shows possible-alt hints and the **alts menu** lists
  one head per account sharing the connection; click opens that alt's offline manage menu.
  Shared connection is a **clue, not proof** (siblings, shared cafes): the menu only
  inspects, it never punishes in bulk.
- Honest note: Minecraft Java has no hardware ban. A hashed-connection ban plus automatic
  alt-banning is the strongest a server can do.

## Staff notes

`/note <player> <text>` attaches shared staff memory to a player ("verbally warned, next
one is a mute") — incidents that did not reach a punishment. Notes work offline, are
capped per player (oldest drops), never show to the player, and have a read/add/delete
menu (shift+click deletes). Permission: `chatalyst.staff.notes`.

## Vanish, staff mode and inventories

- `/vanish` hides staff; with `Vanish.Hide_From_Ping: true` vanished players also leave
  the server-list ping counter and hover sample. `Ops_See_Vanished: false` makes even OPs
  need an explicit `chatalyst.seevanished` grant.
- Staff mode (`/staff on`) can auto-vanish (`Staff_Mode.Vanish_On_Enter`) and allow
  flight (`Staff_Mode.Allow_Flight`).
- `/invsee` and `/echest` open live views; with `chatalyst.command.invsee.edit` /
  `chatalyst.command.echest.edit` staff can edit (armor and offhand included). Changes
  apply on close and everything is audited.

## Key permissions

| Node | Grants |
| --- | --- |
| `chatalyst.command.staff` | Staff mode and dashboard |
| `chatalyst.command.kick` / `.ban` / `.mute` / `.freeze` / `.warn` | Each punishment |
| `chatalyst.command.banip` | Connection bans |
| `chatalyst.command.invsee` / `.invsee.edit` | Inventory view / edit (same for `.echest`) |
| `chatalyst.command.vanish` / `.vanish.others` | Own / others' vanish |
| `chatalyst.command.seen` / `.check` | Lookups |
| `chatalyst.staff.notes` | Read, add and delete staff notes |
| `chatalyst.staff.alts` | See possible-alt hints and the alts menu |
| `chatalyst.seevanished` | See vanished staff |
| `chatalyst.bypass.kick` / `.ban` / `.mute` / `.freeze` / `.warn` | Protect staff from non-operator staff |
| `chatalyst.notify.security` | Security alerts (IpGuard, offensive names, filter evasion) |

All moderation actions are written to the audit log (see `Log` in `moderation.yml`) and,
with `Staff_Security.Enabled`, sensitive staff commands are mirrored to console and the
private staff bot channel.
