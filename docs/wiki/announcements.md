# Announcements and Animations

Chatalyst has two announcement paths:

- `Announcer` rotates configured messages automatically and can wait for real chat activity.
- `/announce` opens the manual composer GUI for staff with `chatalyst.command.announce`.

The quick `/broadcast`, `/say` and `/me` commands remain available, but they do not expose the animation selector. Use `/broadcast [chat|title|actionbar|bossbar|toast] <message>` to select a static display.

## Composer Workflow

1. Run `/announce` as a player.
2. Select a display: `EPIC`, chat, title, action bar, boss bar or toast.
3. Select a one-shot sound, a style from the `/chatcolor` palette and an animation.
4. Click the message item and type plain announcement text in chat. The message is captured instead of published as normal chat; `cancel` or `cancelar` aborts entry.
5. Choose the scope: the current backend or the entire proxy network.
6. Preview privately or send to the selected scope.

Full announcements are also written to the console and staff-security audit. The chosen sound plays once when preview or delivery starts.

## Network-wide Announcements

The scope selector appears when `Proxy.Enabled` and `Network_Admin.Announcements.Enabled` are both enabled. Staff need `chatalyst.admin.network.announce` in addition to `chatalyst.command.announce`. The same display, style, sound and animation are rendered independently on every backend, so an `EPIC` announcement reaches players in the lobby, survival and every other connected server.

The administration GUI also has a **Global network announcement** shortcut. Preview always remains private and local; only the final send crosses the network. The originating backend records `network-announce` in the staff audit, and receiving backends identify the source server and staff sender in their console.

Native and legacy plugin messaging need an online player to carry a backend message through the proxy. Redis mode can deliver announcements without a local carrier. Packets are bounded, timestamped and deduplicated; received packets are never relayed again.

## Displays

`EPIC` combines an animated title, framed static chat and an animated boss bar. A selected sound is optional and plays separately.

Animations apply only to title, action bar and boss bar. Chat and toast are one-shot displays and therefore remain static even when an animation is selected.

## Presets

| Preset | Effect |
| --- | --- |
| Static | Shows the selected style without animation. |
| Typewriter | Reveals the text progressively. |
| Expand | Reveals the text from the center outwards. |
| Glitch | Starts obfuscated and decodes from left to right. |
| Color wave | Moves a gradient/rainbow phase; plain colors fall back to rainbow. |
| Frames | Cycles configurable MiniMessage templates, including colors, gradients and decorations. |
| Pulse | Alternates the selected style with a dim gray version. |
| Alert flash | Alternates the selected style with bold red. |
| Marquee | Scrolls a 24-character window through a repeating ticker. |
| Countdown | Shows 3, 2, 1, then the message. |

Animations run at 10 frames per second for about eight seconds. A new `/announce` replaces the previous animation for each affected player and display, preventing interleaved titles/action bars and stacked boss bars. A private preview only replaces that admin's current animation.

### Configurable frames

The `Frames` preset uses `Announcer.Animation` in `bot.yml`. Every list entry is a complete MiniMessage template; `{msg}` inserts the text entered in the composer. This means each frame can use a plain color, a gradient, bold/italic decoration or surrounding fixed text:

```yaml
Announcer:
  Animation:
    Interval_Ticks: 6
    Frames:
      - "<gradient:#FF4D6D:#FFC933>{msg}</gradient>"
      - "<gradient:#FFC933:#3DFFA8><bold>{msg}</bold></gradient>"
      - "<gradient:#3DFFA8:#00E5FF>{msg}</gradient>"
      - "<gradient:#00E5FF:#FF4D6D>{msg}</gradient>"
```

`Interval_Ticks` accepts 2–200 ticks. The frame template replaces the palette style selected in the GUI while this preset is active. For a network announcement, the `frames` preset crosses the proxy and each backend renders its own local sequence; keep this section identical across backends when you want the exact same appearance everywhere.

> **Accessibility:** Alert flash changes color rapidly. Use it sparingly; pulse or color wave is a calmer choice for routine notices.

## Player Preference

`/toggle announcements` disables only automatic rotating announcements. It does not suppress manual staff broadcasts or `/announce` notices.
