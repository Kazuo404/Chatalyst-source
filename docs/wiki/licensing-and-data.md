# Licensing, DRM and data

Chatalyst uses a license system. This page says exactly what it does, so you can decide before
you buy, and so you can tell your players what your server stores.

## License check (DRM)

- Each purchase has one license key, and the same key works on all your servers.
- The server needs internet access to reach the license service.
- It contacts the service when it starts, then every 6 hours to check that the key has not been
  revoked, and once a day to report that it is running.
- A short outage changes nothing: a successful check stays valid for 7 days.
- **Chatalyst turns itself off if it has no valid check for 7 days, or if the key is revoked.**
  It also refuses to start if its signed files were modified.

## What is sent to the license service

- License key and a random install id
- The server's IP address and port, and the IP the service sees
- Server software and version, Chatalyst version and build label
- MOTD, online mode, and current and maximum player count
- Operating system, Java version, CPU cores and memory limit

No player names, chat messages, player IP addresses or server files are sent. A modified copy
reports the same server details once.

## What Chatalyst stores on your server

Everything below stays in your own files or database.

- **Player connections:** when alt tracking is on, IP addresses and their links to accounts are
  stored for moderation. They are deleted after `Staff_Moderation.Alts_Retention_Days` (365 by
  default). Set `Track_Alts: false` to keep only the latest connection, or 0 days to keep rows
  forever.
- **Chat logs:** daily log files, when logging is enabled.
- **Moderation records:** bans, mutes, warnings and their history.

As the server owner, you decide what to keep and you are responsible for telling your players.

## Optional outgoing connections

These only run if you turn them on: the Discord bot and webhooks, Tebex and other store
integrations, Redis, MySQL, live translation, and the update check.

## Open-source components

See `META-INF/THIRD-PARTY-NOTICES.md` inside the jar.
