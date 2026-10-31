# Radio Frequency Chat

The built-in `local` channel is a radio channel, not distance-based proximity chat. Players hear it only when they have an active frequency and receive messages from players tuned to the same value.

## Configuration

```yaml
Channels:
  Frequency:
    # Empty starts the radio disabled. Set "10.00" to tune new players automatically.
    Default: ""
  List:
    local:
      Type: FREQUENCY
      Force_Listen: true
```

Valid frequencies range from `10.00` to `99.99`. The selection persists in the configured storage backend. A blank default means new players neither hear nor transmit radio messages until they tune in.

## Commands

- `/frequency` opens the tuning GUI.
- `/frequency 42.50` tunes directly; a comma decimal separator is also accepted.
- `/frequency off` disables the radio.
- `/f <message>` transmits once on the tuned frequency without changing the active chat channel.

`/f` uses the normal chat pipeline, including mute checks, rules, anti-spam, formatting, logs and channel permissions. `Force_Listen` keeps the local radio audible while the player remains in another active channel, but delivery is still filtered by frequency.

Staff with `chatalyst.bypass.range` can hear every active frequency for moderation. Frequency, world, region and party channels are not written to the global channel replay history because replaying them could expose scoped messages.

## Network Behavior

Frequency chat is local to one backend. Do not proxy it: positions and tuned audiences are backend-specific. Use a separate `GLOBAL` channel when the same conversation must cross Velocity, BungeeCord or Redis.
