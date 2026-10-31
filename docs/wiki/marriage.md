# Marriage (`/marry`, `/divorce`)

Players can propose to each other; once both agree, the server announces the wedding and
each spouse shows up in the other's chat hover.

**Opt-in.** The feature ships **disabled**. While it is off, `/marry` and `/divorce` only
answer with the "disabled" message and nothing else in the plugin changes.

## Turning it on

`players.yml`:

```yaml
Marriage:
  Enabled: true
  Request_Timeout: 60          # seconds a proposal stays open (minimum 10)
  Announce: true               # announce PUBLIC weddings (a private one never does)
  Announce_Divorce: false      # break-ups are private by default
  # The hover, which reads like a certificate: {partner} and {since} (time married).
  # {rank_color} paints the » separator with the player's rank colour, so the line
  # matches the rank/party/ping rows above it instead of standing out.
  Tag: "<#C0B3D9>{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>· <#C0B3D9>{since}"
```

Reload with `/cht reload`. The commands need `chatalyst.command.marry` (granted to
everyone by default — restrict it with LuckPerms if you want marriage to be a rank perk).

## How it works for players

| Command | What it does |
|---|---|
| `/marry <player>` | Sends a proposal. The other player gets **[ Accept ]** / **[ Decline ]** buttons. |
| `/marry accept` / `/marry deny` | Answers a pending proposal (the buttons run these). |
| `/marry` | Shows who you are married to and for how long. |
| `/divorce` | Ends the marriage. |
| `/marry bank` | Opens the **shared chest** both spouses can use. |
| `/marry tp` | Travels to your spouse. |
| `/marry <player> boda` | Propose **with a public ceremony**. |
| `/marry ceremony` | Hold your wedding again. |
| `/marry join` | Travel to a wedding you were invited to. |
| `/marry help` | The full command list. |

Aliases: `/casarse`, `/matrimonio`, `/divorciarse`. The target can be given by nickname as
well as by real name.

## The ceremony — the couple decides

A wedding can be quiet (just the announcement) or **a public ceremony**: a wedding march for
the whole server and an invitation button. **The people getting married choose**, not the
server owner:

| How | What happens |
|---|---|
| `/marry <player>` | **Private wedding: nobody is told.** No announcement, title, sound or particles — only the two of them get their message. |
| `/marry <player> boda` | **Public wedding**: summon, vows, wedding march, full-screen title and an invitation for the whole server. |
| `/marry ceremony` | Already married — hold the ceremony again whenever they like. |

The only trace a private wedding leaves is the hover: pointing at their names in chat still
shows who they are married to. The marriage is not hidden — there is simply no *announcement*.

Whoever receives the proposal **sees that a public ceremony is being asked for** before
accepting: saying yes to a ceremony in front of the whole server cannot be a surprise.

**The music.** Minecraft has no wedding music, so it is played *note by note* with note
block sounds. Each `Melody` entry is `"<note>:<tick>"` — note `0-24` (F#3 to F#5, a note
block's range, where `12` is F#4) and the tick it plays on (20 ticks = 1 second). The
default is Wagner's Bridal Chorus ("Here comes the bride") in C:
`G4 | C5 C5 C5 | G4 D5 C5 B4 | C5`. A malformed entry is skipped with a console notice —
the wedding still happens, that note just stays silent.

Config only holds the limits and the sheet music, never the choice:

```yaml
Marriage:
  Ceremony:
    Guest_Teleport: true      # SECURITY switch (see below)
    Window_Seconds: 60        # how long the invitation lasts
    Cooldown_Minutes: 10      # anti-spam for /marry ceremony
    Gathering_Seconds: 30     # so guests arrive before the vows
    Vow_Seconds: 60           # to say "I do"
    Style: "hearts"           # effect on the couple (firework-show, halo, none...)
    Instrument: "block.note_block.bell"
    Melody: ["13:0", "18:5", "18:20", "18:25", "13:40", "20:45", ...]   # 32 notes, ~16 s
```

> **Read this before leaving `Guest_Teleport` on.** The button sends whoever clicks it to
> wherever the couple married, and the invitation reaches the whole server. A couple can
> marry anywhere — including a lava pit or a PvP trap. With `false` the wedding still has
> music and an announcement, but nobody is teleported.

The invitation closes after `Window_Seconds`, and a `/cht reload` closes it immediately.

## Perks

```yaml
Marriage:
  Perks:
    Bank:
      Enabled: true
      Rows: 3              # 1-6 (9 slots per row)
    Teleport:
      Enabled: true
      Cooldown_Seconds: 120
    Join_Notice: true      # tells you when your spouse comes online
```

**Shared bank** (`/marry bank`). Both spouses open the *same* chest instance, so what one
stores the other sees immediately — exactly like a real chest with two people at it. It is
saved when the last viewer closes it and on server shutdown.

Two deliberate rules:

- **A divorce is refused while the bank still has items.** The plugin will not split them
  for you and will not delete them: empty it first. Breaking up must never make items
  disappear.
- The title is shown in the server's default language for both spouses — a shared
  inventory has one title, so it cannot follow each viewer's own language.

> **Single-server feature.** On a network with a shared database, two spouses connected to
> *different* backends would open separate copies and the last close would win. Keep it
> disabled on multi-backend networks, or accept that caveat.

**Teleport** (`/marry tp`) travels to your spouse with a cooldown; the cooldown only starts
if the trip actually succeeds. **Join notice** pings you when they come online — an
anonymous spouse never triggers it, since that would reveal who just logged in.

Rules enforced by the plugin:

- Nobody can be married to two people. Both sides are re-checked at the moment the
  proposal is accepted, not only when it is sent.
- Only one open proposal per player: a third player cannot overwrite a pending one and
  steal the "yes".
- Proposals expire on their own and are **not** persisted — a restart clears them.
- If the proposer logs off before the answer, the proposal is dropped.

## The hover

`formats/chat.yml` carries the line in both the `prefix` and `player-name` hovers:

```yaml
      - "{married_tag}"
```

`{married_tag}` renders `Marriage.Tag` with `{partner}` replaced. For a single player it
resolves to nothing **and the whole line disappears** from the hover — no blank row.

`Marriage.Tag` is the one hover line that lives in `players.yml` rather than
`formats/chat.yml`, so its own migration keeps it in step with the rank/party/ping rows:
on upgrade the `»` separator moves to `{rank_color}` (matching those rows) and the old
decorative heart is dropped. The migration only recognises the stock templates, so a tag
you have customised is left exactly as you wrote it — including any `{rank_color}` you add
yourself, which resolves to the viewer's rank colour like everywhere else.
Existing installs get this line added automatically on upgrade (config version 26); a
hover you have customised is left untouched, so add the line yourself if you want it.

## Placeholders

| Placeholder | Value |
|---|---|
| `{married_to}` | Spouse's shown name, or empty |
| `{married_tag}` | The decorated `Marriage.Tag` line, or empty |
| `{partner}` / `{since}` | Inside `Marriage.Tag`: spouse and how long married |
| `%chatalyst_partner%` | Spouse's shown name (PlaceholderAPI, for TAB/scoreboards) |
| `%chatalyst_married%` | `yes` / `no` |

## Anonymous mode

A player in [`/anonymous`](spy-anonymous.md) **never** shows a partner: `{married_to}`,
`{married_tag}` and both PlaceholderAPI variants go blank while disguised. Knowing who the
couple is identifies the anonymous half as reliably as their own name would.

## Notes

- Nicknames are inserted as plain, escaped text, so a MiniMessage nickname cannot inject
  formatting into the announcement or the hover.
- The two rows of a marriage are written with a durable compare-and-set: if the second one
  fails, the first is rolled back, so a half-marriage cannot exist.
- Divorce clears the partner's row first — an interrupted divorce leaves *you* marked, which
  you can fix by running the command again, instead of leaving them married to a ghost.
