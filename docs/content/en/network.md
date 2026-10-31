---
title: Proxy & Redis networks
description: Run Chatalyst across a Velocity or BungeeCord network. Cross-server chat, signed network packets, Redis sync, and a network admin center for staff.
---

On a network, Chatalyst lets your servers talk to each other. Global chat can reach every backend, staff can see the chat of any server without joining it, announcements can go to the whole network, and staff can teleport to players on other servers. Every packet between servers is signed, so a modified client cannot fake one.

You use one universal jar: install the same `Chatalyst-<version>.jar` on every Paper/Purpur backend and, if you use the native mode, in the proxy's `plugins` folder too. Paper, Velocity and BungeeCord each load only their own part of it.

All settings are in `network.yml`.

## Choosing a transport

```yaml
Proxy:
  Enabled: false
  Mode: legacy
```

| Mode | What you install | Best for |
| --- | --- | --- |
| `legacy` | Nothing on the proxy | Simple networks. Uses BungeeCord's built-in forwarding; needs a player online on the sending server |
| `native` | The same jar in the proxy's `plugins` folder | Recommended. Automatic server names, cross-server teleports, staff directory |
| `redis` | A Redis server | Messages flow even when a server has no players online |
| `both` | Proxy jar and Redis | Only for migrating or testing; it can duplicate messages |

A chat channel only crosses servers when it has `Proxy: true` in `chat.yml` (see [channels](channels)). Local channels (world, region, party, frequency) stay local unless you mark them.

## Network security

Every packet between servers is signed (HMAC-SHA256) and old packets are rejected, so nobody can forge announcements, support messages or staff actions from a modified client.

```yaml
Proxy:
  Network_Secret: ""
```

- **Velocity with modern forwarding**: nothing to do. The key is derived from the forwarding secret on each backend and on the proxy.
- **Legacy or Redis mode without Velocity forwarding**: the key comes from `Redis.Shared_Secret` if it is set.
- **BungeeCord in native mode** (or any other case): set the same long random value (16 characters minimum, 32 or more recommended) as `Network_Secret` on every backend, and in `plugins/chatalyst/network-secret.properties` on the proxy:

```properties
network-secret=replace-with-one-long-random-network-secret
```

> [!DANGER]
> Without a key, network forwarding is **blocked**. Chatalyst never accepts unsigned network messages. Restart every backend and the proxy after changing the key.

## Server names

```yaml
Proxy:
  Server_Discovery:
    Mode: auto
    Manual_Server_Id: ""
```

| Option | Default | What it does |
| --- | --- | --- |
| `Mode` | `auto` | With the native bridge, each backend learns its exact name from the proxy (`lobby`, `survival`…) when the first player connects |
| `Manual_Server_Id` | `""` | With `Mode: manual`, the name this backend uses |

Before the first player joins, a backend temporarily uses `Database.Server_Id`, then `Redis.Server_Id`, then `server-<port>`. Server names used for [storage](storage) are never renamed by this.

## Redis

```yaml
Redis:
  Enabled: false
  Host: "127.0.0.1"
  Port: 6379
  Password: ""
  Database: 0
  Channel: "chatalyst:network"
  Server_Id: ""
  TLS: false
  Shared_Secret: ""
```

| Option | Default | What it does |
| --- | --- | --- |
| `Host` / `Port` / `Password` / `Database` | `127.0.0.1` / `6379` / `""` / `0` | Your Redis connection |
| `Channel` | `chatalyst:network` | Must be the same on every backend |
| `Server_Id` | `""` | Unique per backend. Empty uses `<server-name>-<port>` |
| `TLS` | `false` | Encrypts the connection. Turn it on whenever Redis is not on the same machine |
| `Shared_Secret` | `""` | **Required.** Same value on every backend (16+ characters). Redis stays disabled until it is set |

Every Redis message is signed and carries a timestamp, so only your servers can publish and replayed messages are rejected. If messages stop flowing, check that every backend has exactly the same secret and that their clocks are within five minutes of each other.

With a shared MySQL database, Redis also tells the other servers right away when data changes, such as a report claimed or a mail sent (see [storage](storage)).

## Network admin center

`/chatalyst admin` (or `/cht gui`) includes a network view for staff. It shows every server, its population and a recent chat timeline, so you can read what is happening on a server without joining it.

```yaml
Network_Admin:
  Monitor:
    Enabled: true
    History_Per_Server: 100
    Retention_Minutes: 60
    Max_Live_Subscriptions: 2
    Live_Format: "<#7B68A0>[<#B56CFF>{server}<#7B68A0>] <#C0B3D9>#{channel} <#FBF2FF>{player}<#C0B3D9>: <#FBF2FF>{message}"
  Announcements:
    Enabled: true
    Max_Message_Length: 512
```

| Option | Default | What it does |
| --- | --- | --- |
| `Monitor.Enabled` | `true` | Turns the chat monitor on |
| `History_Per_Server` | `100` | Messages kept per server |
| `Retention_Minutes` | `60` | How long they are kept |
| `Max_Live_Subscriptions` | `2` | How many server chats one staff member can mirror into their own chat at once |
| `Live_Format` | see above | How a mirrored message looks |
| `Announcements.Enabled` | `true` | Lets `/announce` target every server |
| `Announcements.Max_Message_Length` | `512` | Longest network announcement |

The monitor only relays public and channel chat handled by Chatalyst. It never captures commands, passwords, private messages or mail, and the history lives in memory only.

The admin center also has:

- **Live server feeds**: mirror another server's chat into yours (right-click a server card).
- **Staff directory**: every staff member online, their tier, server and world, with one-click teleport.
- **Players by server**: player heads with rank, balance, world, game mode, ping and AFK state; click to teleport, even to another server.

Vanished players never appear in these directories. Cross-server teleport needs `native` mode.

### Permissions

| Permission | What it grants |
| --- | --- |
| `chatalyst.admin.network` | Everything below (op by default) |
| `chatalyst.admin.network.monitor` | Open the chat monitor |
| `chatalyst.admin.network.monitor.server.*` | Every server; `...server.survival` allows only `survival` |
| `chatalyst.admin.network.monitor.channel.*` | Every channel; restricted channels otherwise need their normal permission |
| `chatalyst.admin.network.announce` | Send `/announce` to the whole network |
| `chatalyst.admin.staff.directory` / `.teleport` | See the staff directory / teleport to staff |
| `chatalyst.admin.players.directory` | Browse players by server |
| `chatalyst.admin.players.server.*` | See players of every server |
| `chatalyst.admin.players.teleport` | Teleport by clicking a player head |

## Rule commands on other servers

A [rule](rules-engine) can run a console command on a specific other server with `Actions.Proxy_Commands`. Because that is remote console access, it is off by default and each command must be allowed on **every** receiving server:

```yaml
Proxy:
  Rule_Commands:
    Enabled: false
    Allowed_Commands: [broadcast, say]
```

Only the listed command names are accepted; wildcards are not. Every execution is written to the audit log.

## Limits

- `legacy` and `native` modes need at least one player online on the sending server to carry a message. Use `redis` if you need messages to flow on empty servers.
- Redis-only networks cannot see the proxy's server list, so a server appears in the admin center once it has had some activity. Use manual server names if you need them fixed.
- Cross-server teleport only works with the native bridge on the proxy.
- The staff help desk that routes questions across the network is covered in [reports & support](reports#staff-help-requests).
