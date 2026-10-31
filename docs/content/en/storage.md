---
title: Storage & database
description: Where Chatalyst keeps player data. YAML by default, SQLite for a single server, or MySQL to share data across a network, with safe live reloads.
---

Chatalyst saves player data such as mutes, warnings, nicknames, chat colors, toggles, mail, reports and purchases. Out of the box it uses plain YAML files and needs nothing else. For bigger servers you can switch to SQLite, and for a network you can point every server to the same MySQL or MariaDB database so the data is shared.

Your choice does not change how any feature works; only where the data lives.

## Choosing a backend

| Backend | Setting | Best for |
| --- | --- | --- |
| YAML | `Enabled: false` (default) | Small and medium servers. Files in `plugins/Chatalyst/data/` |
| SQLite | `Enabled: true`, `Type: SQLITE` | A single server with a lot of data. One file, `plugins/Chatalyst/data/chatalyst.db`, no database server needed |
| MySQL / MariaDB | `Enabled: true`, `Type: MYSQL` | Networks that want to share data between servers |

The database drivers are downloaded by Paper at startup; they are not bundled in the plugin jar.

## Configuration

In `config.yml`:

```yaml
Database:
  Enabled: false
  Type: SQLITE
  Host: localhost
  Port: 3306
  Database: chatalyst
  User: root
  Password: ""
  SSL_Mode: PREFERRED
  Network_Shared: true
  Shared_Refresh_Seconds: 5
  Server_Id: ""
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | `false` stores everything in YAML files |
| `Type` | `SQLITE` | `SQLITE` or `MYSQL` |
| `Host` / `Port` / `Database` / `User` / `Password` | `localhost` / `3306` / `chatalyst` / `root` / `""` | MySQL connection |
| `SSL_Mode` | `PREFERRED` | `DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA` or `VERIFY_IDENTITY`. `PREFERRED` uses TLS when the server offers it. Use `REQUIRED` or stronger for a remote MySQL |
| `Network_Shared` | `true` | With a shared MySQL: `true` shares the data between servers, `false` keeps each server's data apart |
| `Shared_Refresh_Seconds` | `5` | How often a shared database is re-read as a safety net |
| `Server_Id` | `""` | This server's name for storage. Empty uses a name based on the port |

## Network mode

With several servers on the same MySQL, `Network_Shared` decides how they behave:

- **`true`**: reports, mutes, nicknames, mail, tickets and toggles are the same on every server. Best for one network.
- **`false`**: each server keeps its own data on the same database, kept apart by `Server_Id`.

Give every server a stable, unique `Server_Id`. Even with sharing on, some data stays per server on purpose, such as the [market](market), so the same listing can never be sold on two servers.

With sharing on, each server re-reads the database every `Shared_Refresh_Seconds`. Add [Redis](network#redis) for instant updates: a report claimed on one server shows on the others right away.

SQLite is always per server, because it is a local file.

## How data is kept safe

- **Writes are batched.** Changes update memory at once and are written in the background every couple of seconds. A batch that fails stays queued and is retried, and everything is written on shutdown.
- **No lost updates between servers.** Operations that two servers might do at the same moment (claiming a report, replying to a ticket, adding mail) are checked against the database and retried with the new value instead of overwriting it.
- **Atomic files.** YAML files are written to a temporary file first and then moved into place, so a crash never leaves a half-written file. A file that cannot be read is kept as `<name>.corrupt-<time>.yml` instead of being overwritten.
- **Fail-closed on startup.** If the configured database cannot be reached, Chatalyst does not start with empty YAML files. That would create a second, empty version of your data and could overwrite the real one when MySQL comes back.

## Reload and restart

`/chatalyst reload` applies these MySQL changes live, without a restart: `Host`, `Port`, `Database`, `User`, `Password`, `SSL_Mode` and `Shared_Refresh_Seconds`. Chatalyst pauses storage for a moment, writes everything pending, tests the new connection and switches to it. If anything fails, the previous connection is restored.

These need a full server restart, because they change where the data is or how it is named: `Enabled`, `Type`, `Network_Shared` and `Server_Id`. The reload tells you so and keeps the current storage active.

## Changing backend

> [!WARNING]
> Switching between YAML, SQLite and MySQL does **not** copy your existing data. Back up the server and move the data yourself before changing `Database.Enabled` or `Database.Type`, or you start with empty player data on the new backend.

## Other files

| Folder | What is inside |
| --- | --- |
| `data/` | Player data when using YAML, and the SQLite file |
| `logs/` | Daily audit logs `chat-YYYY-MM-DD.log` (see [spy & logs](spying#audit-logs)) |

## Limits

- There is no built-in migration between backends.
- A shared database without Redis shows changes from other servers after at most `Shared_Refresh_Seconds`, not instantly.
- The marriage shared chest is single-server: on a shared database, two spouses on different servers would open separate copies.
