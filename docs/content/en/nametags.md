---
title: Floating nametags
description: Replace the one-color vanilla name above the head with a floating tag that shows gradients, rank, icons, a title line and animations, plus the tab list.
---

Minecraft can only draw the name above a player's head in one solid color. The floating nametag replaces it with a floating text line that shows the full style: gradients letter by letter, bold, the rank prefix, ItemsAdder icons and the AFK tag. You can add a second line above it for a title such as "KING OF EVERYTHING", and animate it.

The same file (`nametag.yml`) also holds the classic scoreboard nametag and Chatalyst's own tab list.

## How to turn it on

Everything is off by default. In `plugins/Chatalyst/nametag.yml`, set `Nametag.Fancy.Enabled: true` and run `/cht reload`:

```yaml
Nametag:
  Enabled: false
  Prefix: "{player_prefix}"
  Suffix: "{player_suffix}"
  Name_Color: "{rank_color}"
  Update_Interval: 30
  Fancy:
    Enabled: false
    Format: "{nametag_icon_left}{player_prefix}{player_nick}{afk_tag}{nametag_icon_right}"
    Height: 0.3
    Update_Interval: 2
    Hide_Sneaking: true
```

> [!WARNING]
> Without TAB, Chatalyst hides the vanilla name by itself. With TAB installed, also set `scoreboard-teams` → `invisible-nametags: true` in TAB's `config.yml`, or the vanilla name shows behind the floating one.

## Configuration

### Floating tag

| Option | Default | What it does |
|---|---|---|
| `Fancy.Enabled` | `false` | Turns the floating nametag on. |
| `Fancy.Format` | icons, prefix, nickname, AFK tag | What the line shows. Accepts every [placeholder](placeholders). |
| `Fancy.Height` | `0.3` | How high the text floats above the head. |
| `Fancy.Update_Interval` | `2` | Seconds between refreshes when nothing is animating. |
| `Fancy.Hide_Sneaking` | `true` | Dims the tag while the player sneaks. |

The tag follows the player through teleports, deaths and world changes, and hides in vanish and spectator mode.

### Side icons

`{nametag_icon_left}` and `{nametag_icon_right}` sit on each side of the tag and are set per player with `/nametagtitle iconleft` and `/nametagtitle iconright`. They are meant for ItemsAdder icons (a shield for a guardian, a crown, a clan emblem) but accept any MiniMessage. They are empty until set.

### Title line

A second line above the tag:

```yaml
    Title:
      Enabled: false
      Format: "{nametag_title}"
      Default_Format: ""
      Height: 0.55
      Max_Length: 32
```

| Option | Default | What it does |
|---|---|---|
| `Title.Enabled` | `false` | Turns the title line on. |
| `Title.Format` | `{nametag_title}` | The per-player title set with `/nametagtitle title`. |
| `Title.Default_Format` | `""` | Used when the player has no title of their own. Point it at a LuckPerms meta value for per-rank titles, for example `%luckperms_meta_nametag_title%`. |
| `Title.Height` | `0.55` | Height of the title line. Keep it above the tag's `Height`. |
| `Title.Max_Length` | `32` | Maximum title length. |

If the resolved title is empty, the whole line is removed.

### Animation

One animation clock drives the floating tag, the tab list and the nickname frame used in new chat messages.

```yaml
    Animation:
      Enabled: false
      Mode: "frames"
      Interval_Ticks: 6
      Tab_Interval_Ticks: 10   # minimo 6; subelo en redes grandes
      Apply_To:
        Tab: true              # requiere Tab_List.Enabled o TAB.Sync_Nick
        Chat: true             # snapshot vigente; no reenvia ni duplica mensajes
      Frames:
        - "<#FF4D6D>{player_nick}"
        - "<#E0AAFF>{player_nick}"
        - "<#3DFFA8>{player_nick}"
        - "<#B56CFF>{player_nick}"
      Wave:
        Content: "{player_nick}"
        Colors:
          - "#FFD700"
          - "#FF4D6D"
          - "#FFD700"
      Per_Player: false
```

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns the shared animation on. |
| `Mode` | `frames` | `frames` rotates the `Frames` list. `wave` slides the `Wave.Colors` gradient across `Wave.Content`. |
| `Interval_Ticks` | `6` | Animation speed (2 to 200 ticks). |
| `Tab_Interval_Ticks` | `10` | Tab list update speed (6 to 200). Raise it on large networks. |
| `Apply_To.Tab` | `true` | Animates the tab list (needs `Tab_List.Enabled` or `TAB.Sync_Nick`). |
| `Apply_To.Chat` | `true` | New chat messages use the current frame. |
| `Per_Player` | `false` | Lets each player pick their own preset from `/nickcolor`. |

With `Per_Player: true`, players choose one of the 32 shipped `Presets` (rainbow, fire, ocean, neon, gold, aurora and more, all editable). Per-player presets animate only the floating tag and the title line; the tab list and chat show the static gradient.

The animation replaces the nickname **color** but keeps its styles (bold, italic and so on).

### Classic nametag

`Nametag.Enabled` uses scoreboard teams to put the rank prefix and suffix around the vanilla name. The name itself can only be one solid color: `{rank_color}` takes it from the rank, `{nick_color}` from the player's `/nickcolor` choice. `Update_Interval` is in seconds (minimum 5). Disable it if another nametag plugin already manages teams.

### Tab list

```yaml
Tab_List:
  Enabled: false
  Name_Format: "{player_prefix}{rank_color}{player_name}{afk_tag}"
  Update_Interval: 10
  Header:
    - "<gradient:#240046:#9D4EDD:#E0AAFF>Your Server</gradient>"
    - ""
  Footer:
    - ""
    - "<#C0B3D9>Connection: {ping_ms} <#7B68A0>· {ping_quality}"
```

`Update_Interval` is in seconds. `{ping_ms}` shows the latency as a colored number, `{ping_quality}` as a word and `{ping_bars}` as signal bars.

### TAB plugin

When TAB is installed, Chatalyst registers its placeholders directly in TAB (no PlaceholderAPI needed): `%chatalyst_nick%`, `%chatalyst_nick_plain%`, `%chatalyst_channel%`, `%chatalyst_muted%`, `%chatalyst_warnings%` and `%chatalyst_chatcolor%`.

```yaml
TAB:
  Sync_Nick: true
```

`Sync_Nick` pushes nicknames and their animation frames straight into TAB's list. While TAB is present, Chatalyst's own `Tab_List` and classic `Nametag` stay off so the two do not fight.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/nametagtitle title <text\|off>` (`/ntitle`) | `chatalyst.nametagtitle` (op) | Sets your title line. |
| `/nametagtitle iconleft\|iconright <text\|off>` | `chatalyst.nametagtitle` (op) | Sets your side icons. |
| `/nametagtitle <title\|iconleft\|iconright> <player> <text\|off>` | `chatalyst.nametagtitle.others` (op) | Changes another player's title or icons. |

| Permission | Default | What it allows |
|---|---|---|
| `chatalyst.nametagtitle.color` | op | Colors and ItemsAdder icons in the title and icons. |
| `chatalyst.nametag.seeself` | op | See your own floating nametag (useful in F5 for testing). |

Titles and icons pass the same checks as nicknames: invisible characters are removed, colors need the `.color` permission, the rules and swear filter apply, and click or hover tags are blocked.

## Limits

- The title line is a second floating entity. If the gap between the two lines looks wrong on your version, adjust `Height` and `Title.Height`.
- Icons need ItemsAdder (or a resource pack that maps the token). Without it, `:shield:` shows as text.
- Chat lines cannot be animated after they are sent: each new message uses the frame of that moment.
- In [anonymous mode](anonymous), the title and side icons are hidden.
