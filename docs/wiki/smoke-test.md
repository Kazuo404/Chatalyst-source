# Smoke Test Checklist

Run this on a real test server before every release. It covers the residual risk the static
audit cannot close: real Paper/Folia schedulers, real SQLite/MySQL drivers, and full
command/GUI round trips.

## Environment Matrix

Repeat the core flow on each row that applies to your deployment:

| Server | Java | Storage |
|---|---|---|
| Paper 1.21.1 | 21 | SQLite (default) |
| Paper 1.21.11 | 21 | MySQL |
| Paper 26.2+ | 25 | SQLite or MySQL |
| Folia latest | 21+ | SQLite or MySQL |

Optional: Velocity or BungeeCord in front with `Network_Secret` configured, and one run with
Redis network mode.

## 1. Lifecycle

- [ ] Fresh start: banner prints, license validates, no warnings or stack traces.
- [ ] `/chatalyst reload` completes and chat still renders afterwards.
- [ ] MySQL: change `SSL_Mode` or rotate the password, then run `/chatalyst reload`; it must
      confirm the reconnect while preserving nicks, sanctions, mail, and market state.
- [ ] MySQL: temporarily use invalid credentials; reload must reject only the candidate,
      report that the previous connection was verified, and keep storage operational.
- [ ] Temporarily changing `Network_Shared` must report “restart required” without switching
      the active namespaces. Restore the value before continuing.
- [ ] Clean shutdown (`stop`): no errors, no cancelled-task spam; on next start no recovery
      journal warnings unless a crash actually happened.

## 2. Chat Core

- [ ] Send global chat; verify per-viewer rendering, mentions, and channel history (`/ch history`).
- [ ] Switch write/listen channels and mute a channel; restart and verify the selection persisted.
- [ ] Frequency chat between two players on the same frequency.
- [ ] Translation stays off by default; `/toggle translate` opts in and translated lines arrive.

## 3. Moderation (two accounts, run on Folia too)

> **Opt-in:** the staff suite now ships with `Staff_Moderation.Enabled: false` so Chatalyst
> never competes with another staff plugin. Set it to `true` in `moderation.yml` before this
> section; while disabled, `/staff`, `/ban`, `/vanish`… are not even registered. With
> `Command_Priority: true` (default) Chatalyst's staff commands answer even when another
> plugin also registers the same labels.

> **OP pitfall:** with the default `Staff_Moderation.Exempt_Ops: true`, operators cannot be
> punished or frozen and always see vanished staff. Either de-op the target account or set
> `Exempt_Ops: false` (and `Vanish.Ops_See_Vanished: false`) in `moderation.yml` before
> testing, otherwise every check below reports "protected target".

- [ ] `/mute`, `/unmute`, `/ban`, `/unban`, `/kick`, `/freeze`, `/vanish <other>` from a staff
      account against a player standing in a *different* region (far away). No
      `IllegalStateException`/region errors in console; feedback returns to the moderator.
- [ ] Staff toolbox: `/invsee` and `/echest` open read-only reviews (also via the chest and
      ender chest buttons in `/players` → manage), `/seen` and `/check`
      answer for online, offline and vanished targets, `/tpto` and `/tphere` teleport across
      regions without console errors (Spanish aliases: `/verinv`, `/verender`, `/visto`,
      `/checar`, `/irhacia`, `/traer`).
- [ ] Hard freeze: the frozen player is COMPLETELY static (no walking, no jumping, no camera
      movement) and sees the full notice (title + panel + sound) even as OP when
      `Exempt_Ops: false`. Any disconnect (quit, connection lost, client kill) bans permanently
      after the short grace (`Disconnect_Grace_Seconds`, default 3).
- [ ] Staff dashboard → Players opens the moderation list: ANY click on a head goes straight to
      the manage menu (no social message/ignore menu). With `chatalyst.command.invsee.edit` /
      `echest.edit`, the chest buttons open EDITABLE views whose changes apply on close.
- [ ] Command-free report flow: open a report → **Moderate** → freeze the target with a preset
      reason while they are OFFLINE (custom reasons are typed in chat and never reach public
      chat). On their next login the freeze notice appears and they cannot move; disconnecting
      again bans them permanently (FreezeGuard). The chosen reason must appear in the audit log.
- [ ] Freeze visibility: freezing (or a frozen player joining) alerts every staff member with
      the reason and a clickable **[GO TO THEM]** button; LEFT-clicking (hitting) a frozen
      player deals no damage and shows who froze them and why (right-click stays untouched);
      the dashboard ice button opens the frozen list (click teleports, shift+click releases).
- [ ] Vanish coverage: a vanished staff member disappears from the world, the tab list, join/
      quit messages, name tags, every list and menu, AND the server-list ping (the "x/y"
      counter and hover names must not include them).
- [ ] Ban screen: a banned player's login attempt shows the full screen — reason, staff,
      remaining time broken down (years/months/days/hours/minutes, recalculated per attempt)
      and the exact unban date; permanent bans say "never".
- [ ] Connection ban: `/banip <player>` (or the "Account + connection" scope in the composer)
      kicks everyone sharing the connection; another account joining from it is rejected AND
      auto-banned (`Ip_Ban.Ban_New_Accounts`), staff gets the IpGuard alert, `/check` shows the
      connection ban, and `/unbanip` lifts only the connection part.
- [ ] Ban composer: the Ban button opens the composer — cycle duration, cycle preset reasons or
      shift+click to write one with styles (`<rainbow>`, `&4`, server styles), cycle the style,
      and the preview must match the actual ban screen the target sees.
- [ ] Unmute/unban a player that has no sanction: the message must say "not found", not a
      storage error — and vice versa when the database is down.
- [ ] `/chatlog <date>` on a large log answers without freezing the tick.

## 3b. Container Log, Inventory Sharing and Anonymous Mode

- [ ] Open a chest, place/break a barrel and flip a lever with account B; with `/spy` and
      `chatalyst.spy.container` on account A the live lines arrive with coordinates.
- [ ] `/spy <account B>`: only their lines arrive (test with a third account that must not
      show up, and with a PM of theirs); `/spy all` restores everything. The spy menu
      lists the watched players.
- [ ] With the spy OFF, `/spy <account B>` then `/spy <account B>` again: the spy ends up
      OFF (not global). Repeat with the spy already on globally beforehand: removing the
      last target returns to global. The message distinguishes both cases.
- [ ] From the GUI: in `/players` → manage, the **Watch** button adds/removes that player
      (icon turns into a target). In `/staff`, right-clicking the spy toggles it, and the
      lore reflects state and scope.
- [ ] `/containerlog` lists the entries; **[View]** opens the chest contents (shulkers
      inside open on click), **[Go]** teleports to the exact block and **[TP]** to the
      player. Break the chest and press **[View]**: it reports there is no container. `/containerlog <player> today` re-reads the day file.
- [ ] Read-only `/invsee` on a player carrying a shulker: clicking opens its contents and
      the arrow goes back. Same inside `/echest`.
- [ ] With `invsee.edit`: **right-click** (empty cursor) on a shulker opens its contents;
      left-click still picks it up normally.
- [ ] Open the same chest 10 times in a row: ONE spy line; after `Repeat_Seconds` the next
      one reports "+9 more". Open several villagers: no identical repeated lines as they
      walk. Craft, enchant and complete a trade: their lines appear, and the trade one
      shows what was taken and what was paid.
- [ ] With `invsee.edit`: swap an equipped armor piece and the off hand from the bottom
      row; on close the target wears it.
- [ ] `/clearinventory <player>` clears storage, armor, off hand and cursor; `all` also
      clears the ender chest; a typo'd mode clears nothing.
- [ ] Account B types `[inv]` and `[ec]`: anyone can open the view for 5 minutes; after
      expiry the button answers "expired". Shulkers inside open on click.
- [ ] `/anonymous` on account B: real-looking alias in chat/tab/nametag, no rank or tags
      (also with formats using `%luckperms_prefix%`), incognito skin for account A and a
      disguised head in `/players`. The real-name lore line only shows to staff with the
      `/realname` permission.
- [ ] Anonymous: `/nick off` is locked with a notice; relog and still anonymous but with
      a DIFFERENT alias AND skin (Rotate_Name_On_Join); a PvP
      death shows the alias; with the Discord bot, it posts the alias in the formatted
      bot message.
- [ ] Anonymous with a `/chatcolor` set: their messages come out WITHOUT that color (not
      recognisable by color). `/anonymous off` + on in the same session returns the SAME
      alias and skin; after a relog, different ones.
- [ ] While anonymous, QUIT the server: the quit message (and the Discord one if enabled)
      must announce the ALIAS, never your real name. Same with just `/nick`.
- [ ] `/anonymous off` restores the exact original nickname, rank, chat color and skin.

- [ ] With PacketEvents active: `/tp`, `/xp query`, `/kill`, `/give` and `/gamemode` come
      out with the plugin's styling (» and colors) in each client's language. Set
      `Vanilla_Messages.Enabled: false` and the vanilla ones return. Without PacketEvents,
      an informational console notice and no errors.

## 4. Preferences Durability

- [ ] Set nick, tag, chat color/style, and toggles; restart the server; all survive.
- [ ] With MySQL: stop the database, attempt to change a preference — the command must report
      failure and the old value must remain (no cache-only success).
- [ ] `/itemname` consumes exactly one quota per rename; two rapid renames never exceed the limit.

## 5. Mail and Friends

- [ ] Send, accept, and deny friend requests through the GUI; verify the accepted identity is
      the one clicked (add several pending requests first).
- [ ] Block/unblock and privacy changes persist across restart.

## 6. Market and Trade (Vault economy installed)

- [ ] List an item, buy it from a second account; money and item both move; history records it.
- [ ] Direct trade with double acceptance; modify the offer after one accept — confirmations
      must reset.
- [ ] Kill the server (`kill -9` / end task) mid-trade, restart: no duplicated or lost items;
      leftovers land in the claim stash; `/market settlements` shows anything needing an operator.

## 7. Reports and Rules

- [ ] Two players `/report` concurrently: IDs are unique and increase monotonically.
- [ ] A rule with a data store counter increments correctly across a restart.

## 8. Network (if proxied)

- [ ] Without a configured secret the plugin messaging transport must refuse to start (fail-closed).
- [ ] With the secret, cross-server private messages and staff handoff work in both directions.

## 9. Purchases and mandatory linking (2026-07-27 work)

Everything below was rewritten recently and **no part of it has run on a real server**. These
are the exact paths where an independent audit found bugs, so they are the ones worth walking.
Run them with `Store.Enabled: true`, one product of each kind, and a Tebex sandbox package.

### 9a. The multi-package cart — the highest-value check

Tebex's `{transaction}` identifies the **payment**, not the package, so a cart with two
products runs two commands carrying the same id. This used to deliver only the first.

- [ ] Buy **two different products in one payment** (sandbox, or run both commands by hand with
      the *same* transaction id: `chatalyst store give <uuid> kit-a tbx-test-1` and
      `chatalyst store give <uuid> kit-b tbx-test-1`).
- [ ] `/store` shows **two** pending purchases, not one.
- [ ] Both are delivered. Neither is reported as a duplicate.
- [ ] Repeat with two **rank** packages in one payment: both ranks land in LuckPerms.

### 9b. Full inventory — nothing may hit the ground

- [ ] Fill the inventory to two free slots. Buy a product with three item stacks.
- [ ] `/store claim` refuses with the "make room" message. **Nothing drops on the floor.**
- [ ] The purchase is still listed in `/store` afterwards.
- [ ] Free some slots, claim again: it is delivered exactly once.
- [ ] With three separate one-item purchases and two free slots, claim all: two are delivered,
      the third stays pending. Check the floor around you is clear.

### 9c. Disconnecting mid-delivery

- [ ] With a purchase pending, join and quit within the `Join_Delay_Ticks` window.
- [ ] Rejoin: the purchase is still owed and is delivered. It is never delivered twice.

### 9d. Restart with work outstanding

- [ ] Leave one purchase pending and one rank entitlement active, then restart the server.
- [ ] `/chatalyst store status` still counts the pending purchase.
- [ ] `/chatalyst tebex status` still counts the recorded purchase.
- [ ] The rank is still applied in LuckPerms after the restart.

### 9e. Delivery when LuckPerms is not ready

- [ ] Run a rank grant command in the first seconds of startup (or with LuckPerms disabled).
- [ ] It is **recorded**, not rejected, and the rank appears once the reconciler runs.

### 9f. Expiry and refunds

- [ ] Grant a rank with a short `Tebex_Duration` (e.g. `2m`). Confirm LuckPerms drops the
      permissions at the exact second on its own.
- [ ] Within one sweep interval, the Discord role is removed too.
- [ ] Buy a rank twice (two transactions), then revoke the **first** one: the rank survives.
- [ ] Grant a rank to a player who ALSO has that group set manually by an admin with a context
      (e.g. `server=survival`). Let the purchase expire: the admin's context-scoped grant must
      still be there.

### 9g. Mandatory linking — must always fail open

- [ ] With `Required.Enabled: true` and the bot connected: an unlinked player is held, receives
      a code automatically, and cannot chat or use commands except `/discord`.
- [ ] Completing `!link CODE` in Discord releases them immediately.
- [ ] **Stop the bot** (blank the token and reload) while someone is held: they are released
      within a tick of the reminder task, with an explanation. Nobody stays stuck.
- [ ] Revoke `chatalyst.command.discord` from a test player: they are **not** held at all.
- [ ] With AuthMe or nLogin installed, the prompt appears only after `/login`, never before.

### 9i. The sectioned storefront

Off by default, so the first check is that nothing changed until you ask for it.

- [ ] With `Store_Sections.Enabled: false`, `/ranks` is the same flat paginated list as before.
- [ ] Turn it on with two sections and give one rank and one good a `Shop.Category`.
- [ ] `/ranks` now opens on the sections; each shows its entry count.
- [ ] Entering a section lists only its entries; the bottom-left button returns to the
      sections rather than closing the menu.
- [ ] A section whose entries are all hidden by `View_Permission` does not appear at all.
- [ ] Give an entry a `Category` that does not exist: it must appear under "Other", **not**
      disappear from the store.
- [ ] A good shows no duration and no "you already own this" line; a rank still shows both.
- [ ] Clicking any card sends the secure purchase button for that exact entry.

### 9h. Config relocation (only on a server that ran an older build)

- [ ] After the first startup, `nametag.yml` exists with your previous `Nametag` values, and
      `integrations.yml` no longer contains that section.
- [ ] `integrations.yml.pre-move.bak` exists.
- [ ] `/chatalyst features` lists the nametag toggles pointing at `nametag.yml`.

## Evidence

Capture for each run: server software and build, Java version, storage backend, the startup log
from the Chatalyst banner to the first join, and any console warning. File failures against the
matching audit finding in [audit-2026-07-15.es.md](audit-2026-07-15.es.md).
