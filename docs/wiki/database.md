# Storage and Database

By default Chatalyst stores everything in YAML files under `plugins/Chatalyst/data/`. Enable the database for larger servers or to share data across a network.

## Config

```yaml
Database:
  Enabled: true
  Type: MYSQL          # SQLITE (local file) or MYSQL (shared server)
  Host: db.example.com
  Port: 3306
  Database: chatalyst
  User: chatalyst
  Password: "..."
  # TLS: DISABLED, PREFERRED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY.
  # PREFERRED (default) uses TLS when the server offers it. Use REQUIRED or
  # stronger for any MySQL that is not on localhost.
  SSL_Mode: REQUIRED
  Network_Shared: true
  Shared_Refresh_Seconds: 5
  Server_Id: "survival-1"
```

`SQLITE` needs no server: it writes `plugins/Chatalyst/chatalyst.db` and is always per-server.

## Network Mode

With a shared MySQL, `Network_Shared` decides how backends behave:

- `true` — reports, tickets, mail, mutes, nicknames and toggles are network-wide. Atomic counters and conditional writes protect report ids and sensitive read-modify-write operations from concurrent updates.
- `false` — each server keeps its own data on the same MySQL, isolated by `Server_Id` (auto-generated from the port if left empty).

Give every backend a stable, unique `Server_Id`. When sharing is disabled it separates all namespaces. When sharing is enabled it still isolates stores that are intentionally local, such as the market, so the same listing cannot be sold on two servers.

With `Network_Shared: true`, Chatalyst reconciles every open namespace from MySQL every `Shared_Refresh_Seconds` (five seconds by default), so caches cannot remain stale forever. Redis sync is still recommended for immediate invalidations; see [Proxy and Redis Sync](proxy-redis.md).

## Reliability Notes

Normal writes update the memory cache immediately and are flushed in background batches every couple of seconds. A failed batch remains queued for retry, and shutdown performs a final synchronous flush.

Operations that must preserve a concurrent read-modify-write transition use an immediate database-backed compare-and-set (CAS). If another backend changes the row first, Chatalyst refreshes its cache and retries against the new value instead of overwriting it. This path is used for operations such as claiming reports, ticket replies and network mailbox appends.

YAML snapshots are written to a temporary file and moved atomically when the filesystem supports it. Chatalyst falls back to a normal replacement otherwise, so it never streams a snapshot directly into the live file.

## Changing Backends

Changing between YAML, SQLite and MySQL does **not** import `data/*.yml` automatically. Back up the server and migrate the values explicitly before changing `Database.Enabled` or `Database.Type`.

If SQL cannot be reached during startup, Chatalyst fails closed instead of replacing the authoritative database with empty YAML. This prevents a second data reality and avoids overwriting SQL data when MySQL returns.

## Live Reload

`/chatalyst reload` is safe with live chat: configuration (channels, rules, filters, formats) is swapped atomically, so messages sent during the reload use either the complete old config or the complete new one, never a half-loaded state.

For MySQL it also applies `Host`, `Port`, `Database`, `User`, `Password`, `SSL_Mode`, and `Shared_Refresh_Seconds` without a restart. Chatalyst briefly pauses store operations, flushes every pending write, validates a candidate connection, swaps it in, and reconciles every cache. The previous connection is closed only after the full transition succeeds. If the candidate or any reconciliation fails, the previous connection and caches are restored and verified.

Changes to `Database.Enabled`, `Database.Type`, `Database.Network_Shared`, or `Database.Server_Id` still require a restart because they alter the backend or namespace identity. The command reports those settings explicitly and keeps the previous storage active.
