---
title: Marriage
description: Players propose, marry in private or with a public ceremony and wedding march, and share a chest, a teleport and a line in the chat hover.
---

Marriage lets two players make it official. One proposes, the other accepts, and from then on their partner shows in the chat hover like a marriage certificate. Couples get a shared chest, a teleport to each other and a notice when the other comes online.

The couple decides how public it is: a plain proposal is private, and asking for a ceremony turns it into a server event with an invitation, vows and a wedding march played note by note.

## How to turn it on

Marriage ships **disabled**. In `plugins/Chatalyst/players.yml`, set `Enabled: true` and run `/cht reload`:

```yaml
Marriage:
  Enabled: false
  # Seconds a proposal stays open before it expires (minimum 10).
  Request_Timeout: 60
  Announce: true
  # Divorces are private by default: breaking up in front of everyone is not fun.
  Announce_Divorce: false
  Tag: "<#C0B3D9>{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>· <#C0B3D9>{since}"
```

While it is off, `/marry` and `/divorce` only answer that the feature is disabled. The commands need `chatalyst.command.marry`, which everyone has by default; remove it from groups if you want marriage to be a rank perk.

## Configuration

### General

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns marriage on. |
| `Request_Timeout` | `60` | Seconds a proposal stays open (minimum 10). |
| `Announce` | `true` | Announces public weddings to the server, in each player's language. A plain `/marry` is always silent. |
| `Announce_Divorce` | `false` | Announces divorces. |
| `Tag` | certificate line | The hover line. `{partner}` is the spouse, `{since}` how long they have been married. |

The shipped `formats/chat.yml` shows `{married_tag}` in the name hover. For a single player it resolves to nothing and the line disappears.

### Ceremony

| How | What happens |
|---|---|
| `/marry <player>` | Private wedding. No announcement, title, sound or particles. |
| `/marry <player> boda` | Public wedding: invitation, vows, wedding march, title and effects. The other player sees that a ceremony was asked for before accepting. |
| `/marry ceremony` | Already married: hold the ceremony again. |

A public wedding runs in three steps: the server is told it starts in `Gathering_Seconds` and the invitation opens; each spouse gets an "I do" button and has `Vow_Seconds` to press it; with both vows the march plays and the marriage is saved. If someone backs out, disconnects or runs out of time, the wedding is called off and nothing is saved.

| Option | Default | What it does |
|---|---|---|
| `Ceremony.Guest_Teleport` | `true` | The invitation button teleports guests to the wedding. |
| `Ceremony.Gathering_Seconds` | `30` | Time for guests to arrive before the vows. |
| `Ceremony.Vow_Seconds` | `60` | Time each spouse has to say "I do". |
| `Ceremony.Window_Seconds` | `60` | How long the invitation lasts for `/marry ceremony`. |
| `Ceremony.Cooldown_Minutes` | `10` | Minutes before the same couple can hold another ceremony. |
| `Ceremony.Style` | `hearts` | Effect on the couple: `hearts`, `firework-show`, `fireworks`, `halo`, `spiral`, `enchant`, `totem`, `notes`, `snow`, `portal`, `none`. |
| `Ceremony.Instrument` | `block.note_block.bell` | Note block sound for the march. |
| `Ceremony.Melody` | Wagner's Bridal Chorus | The march, note by note, as `"<note>:<tick>"`. |

> [!WARNING]
> The invitation reaches the whole server and teleports whoever clicks it to wherever the couple married, which could be a lava pit or a PvP trap. Set `Guest_Teleport: false` to keep the music and announcement without teleporting anyone.

Each `Melody` entry is a note from `0` to `24` (F#3 to F#5, the note block range; `12` is F#4) and the tick it plays on (20 ticks = 1 second). The default plays about 16 seconds. A malformed entry is skipped with a console notice.

### Perks

```yaml
  Perks:
    Bank:
      Enabled: true
      Rows: 3            # 1-6 (9 slots per row)
    # /marry tp travels to your spouse.
    Teleport:
      Enabled: true
      Cooldown_Seconds: 120
    # Tells you when your spouse comes online (anonymous players never trigger it).
    Join_Notice: true
```

| Option | Default | What it does |
|---|---|---|
| `Perks.Bank.Enabled` | `true` | A shared chest both spouses open with `/marry bank`. |
| `Perks.Bank.Rows` | `3` | Chest size, 1 to 6 rows. |
| `Perks.Teleport.Enabled` | `true` | `/marry tp` travels to your spouse. |
| `Perks.Teleport.Cooldown_Seconds` | `120` | Cooldown, counted only when a trip succeeds. |
| `Perks.Join_Notice` | `true` | Tells you when your spouse comes online. |

Both spouses see the same chest, so what one stores the other sees at once. A divorce is refused while the chest still has items: empty it first, so a break-up never makes items disappear.

## Commands and permissions

All commands need `chatalyst.command.marry` (default `true`).

| Command | What it does |
|---|---|
| `/marry <player>` (`/casarse`, `/matrimonio`) | Proposes. The other player gets Accept and Decline buttons. |
| `/marry <player> boda` | Proposes with a public ceremony. |
| `/marry accept` / `/marry deny` | Answers a proposal. |
| `/marry` | Shows who you are married to and since when. |
| `/marry bank` | Opens the shared chest. |
| `/marry tp` | Travels to your spouse. |
| `/marry ceremony` | Holds a ceremony for an existing marriage. |
| `/marry join` | Travels to a wedding you were invited to. |
| `/marry help` | Lists every marriage command. |
| `/divorce` (`/divorciarse`) | Ends the marriage. |

Placeholders: `{married_to}`, `{married_tag}`, `%chatalyst_partner%` and `%chatalyst_married%`. See [Placeholders](placeholders).

## Limits

- The shared chest is a single-server feature. On a network with a shared database, spouses on different servers would open separate copies and the last one to close would win.
- Nobody can be married to two people, and a player can only have one open proposal. Proposals are not saved: a restart clears them.
- A player in [anonymous mode](anonymous) never shows a partner, since the partner would identify them.
