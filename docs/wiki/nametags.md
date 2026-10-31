# Floating Nametags: title, side icons and animation

The fancy nametag replaces the flat vanilla name above the head with one or two floating
`TextDisplay` lines that render the **full** style — gradients, bold, italics, ItemsAdder
icons — bypassing the one-solid-colour limit of classic scoreboard nametags. The text is
**floating, with no background box**. Everything is bilingual (each viewer reads it in their
own language) and Folia-safe.

## The tag line

The main line (rank prefix + nick) is configured in `nametag.yml`:

```yaml
Nametag:
  Fancy:
    Enabled: false
    Format: "{nametag_icon_left}{player_prefix}{player_nick}{afk_tag}{nametag_icon_right}"
    Height: 0.3            # how high above the head
    Update_Interval: 2     # seconds between refreshes (when not animating)
    Hide_Sneaking: true    # dim while sneaking, like the vanilla name
```

Hiding the vanilla name: without TAB, Chatalyst does it with its own team
(`NAME_TAG_VISIBILITY: NEVER`). With **TAB** installed the teams are TAB's, so set
`scoreboard-teams -> invisible-nametags: true` in TAB's `config.yml` (Chatalyst logs the
reminder on start), or the vanilla name shows behind the fancy one.

## Side icons: a shield for a guardian

`{nametag_icon_left}` and `{nametag_icon_right}` sit on either side of the tag and are set
per player. They are meant for ItemsAdder icons — a shield to mark a guardian, a crown, a
clan emblem — but accept any MiniMessage:

```
/nametagtitle iconleft :shield:
/nametagtitle iconright :crown:
/nametagtitle iconleft off
```

They are empty (and invisible) until set, and only render if the default `Format` keeps the
`{nametag_icon_left}` / `{nametag_icon_right}` placeholders (it does out of the box).

## The title line: "REY DE TODO"

A second floating line **above** the tag, for a title. Enable it and point it at the
per-player title:

```yaml
Nametag:
  Fancy:
    Title:
      Enabled: false
      Format: "{nametag_title}"      # the per-player title set with the command
      Default_Format: ""             # fallback when the player has none (see below)
      Height: 0.55                   # above the tag's Height
      Max_Length: 32
```

Set it with the command (self, or another player with the staff permission):

```
/nametagtitle title <gradient:#FFD700:#FFA500>REY DE TODO
/nametagtitle title Nec1x REY DE TODO
/nametagtitle title off
```

Aliases: `/ntitle`, `/nametagicon`. If the resolved title is empty **the whole line is
removed** — no floating blank row. When a player has no title of their own, `Default_Format`
takes over, which is where **per-rank titles** live: point it at a LuckPerms/PlaceholderAPI
value and every member of a rank gets the same title without a command:

```yaml
Default_Format: "%luckperms_meta_nametag_title%"
```

```
/lp user Nec1x meta set nametag_title "REY DE TODO"
```

## Animation across the floating tag, TAB and chat

The same animation clock can drive the floating tag and TAB continuously. Chat captures
the current frame when a message is sent; an existing chat line cannot be edited by the
Minecraft client, so it remains on that frame instead of continuing to move.

Configure it in `nametag.yml`:

```yaml
Nametag:
  Fancy:
    Animation:
      Enabled: false
      Mode: "frames"           # frames | wave
      Interval_Ticks: 6        # animation speed (min 2)
      Tab_Interval_Ticks: 10   # TAB update speed (min 6; raise on large networks)
      Apply_To:
        Tab: true              # built-in Tab_List or TAB with Sync_Nick
        Chat: true             # snapshot at send time; never duplicates messages
      Frames:                  # mode=frames: cycles these, one per interval
        - "<#FF4D6D>{player_nick}"
        - "<#FFC933>{player_nick}"
        - "<#3DFFA8>{player_nick}"
        - "<#00E5FF>{player_nick}"
      Wave:                    # mode=wave: slides a gradient over the text
        Content: "{player_nick}"
        Colors: ["#FFD700", "#FF4D6D", "#FFD700"]
```

`frames` rotates the list — alternating colours, blinking text, swapping icons — one frame
every `Interval_Ticks`. `wave` keeps a single line and slides the `Wave.Colors` gradient
across `Wave.Content` (the gradient phase advances each tick, giving a colour wave). When
animation is on, the whole nametag refreshes at `Interval_Ticks` instead of
`Update_Interval`. A `frames` mode with no frames falls back to the static `Format`.

Animation templates own the nickname **colour**, so a static `/nickcolor` colour cannot
mask the moving frame/gradient. The Styles submenu still applies: bold, italic, underline,
strikethrough and obfuscated decorations are preserved inside every animated frame.

For the built-in list, enable `Tab_List.Enabled`. With the TAB plugin, keep
`TAB.Sync_Nick: true`; Chatalyst sends each current frame through TAB's API. TAB uses its
own bounded `Tab_Interval_Ticks` because every name change is retransmitted to viewers.

In chat, `{player_nick}` and `{rank_nick}` use the frame that was current when the message
was rendered. New messages therefore follow the animation, while old lines stay immutable.
Trying to fake continuous chat animation would require resending or replacing signed chat
lines, causing duplicate messages and compatibility problems, so Chatalyst does not do it.

## Commands and permissions

```
/nametagtitle <title|iconleft|iconright> <text|off>            your own
/nametagtitle <title|iconleft|iconright> <player> <text|off>   staff
```

| Permission | Default | Grants |
| --- | --- | --- |
| `chatalyst.nametagtitle` | op | Set your own title/icons |
| `chatalyst.nametagtitle.color` | op | Use colours (MiniMessage) and ItemsAdder icons |
| `chatalyst.nametagtitle.others` | op | Change other players' title/icons |

The text passes the same defences as `/nick` and `/tag`: invisible characters stripped,
colours only with the `.color` permission, the swear filter and rule engine, and
`<click>`/`<hover>` are blocked — the title is rendered into everyone's view above your head,
so it must not carry actions.

## Anonymity

A title like "REY DE TODO" or a guardian icon floating over a player in `/anonymous` would
give the streamer away exactly like their rank, so `{nametag_title}`,
`{nametag_icon_left}` and `{nametag_icon_right}` are **blanked while disguised**.

## Honest limits

- The title is a **second display entity** mounted on the player. The two lines separate by
  the difference between the tag's `Height` and `Title.Height`; if your Minecraft build
  stacks passengers differently, tune those two numbers until the gap looks right.
- Icons rely on **ItemsAdder** (or any resource pack that maps the `:token:`): without it,
  the `:shield:` token shows as literal text.
- On very large servers, raising `Update_Interval` / `Interval_Ticks` keeps the
  per-tick text rebuild cheap. Raise `Tab_Interval_Ticks` separately to reduce tab-list
  update traffic without making the floating tag less smooth.
