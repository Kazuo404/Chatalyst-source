# Proxy and Redis Sync

Chatalyst supports three network transports for channels marked `Proxy: true`.

## Modes

`legacy` uses the BungeeCord `Forward` plugin messaging channel. It needs no proxy jar, but forwarding depends on player connections.

`native` uses the same universal Chatalyst release jar on Paper/Purpur and on Velocity or BungeeCord. The proxy loads only its small bridge entry point through the `chatalyst:main` plugin channel; it never starts Bukkit features, storage, commands or GUIs.

`redis` uses Redis pub/sub. This is the preferred production mode for networks that want forwarding even when a backend has no local carrier player.

## Network packet authentication

Native and legacy plugin messages use HMAC-SHA256, a five-minute timestamp window and a bounded nonce cache to reject forged or replayed packets. On Velocity with modern forwarding this is zero-configuration: each backend and the universal proxy jar derive a protocol-specific key from the existing forwarding secret. The forwarding secret itself is never used directly as the HMAC key.

BungeeCord has no equivalent forwarding secret. For native Bungee networks, generate one random value (16 characters minimum, 32+ recommended), set it as `Proxy.Network_Secret` on every backend, and put the same value in `plugins/chatalyst/network-secret.properties` on the proxy:

```yaml
Proxy:
  Network_Secret: "replace-with-one-long-random-network-secret"
```

```properties
network-secret=replace-with-one-long-random-network-secret
```

An explicit `Proxy.Network_Secret` always overrides automatic derivation. Legacy or Redis-only setups may derive the plugin-message key from `Redis.Shared_Secret`; Redis still signs its own pub/sub envelope separately. A missing key or one shorter than 16 characters fails closed and blocks native/legacy forwarding—unsigned compatibility mode is not available. Restart every backend and proxy after changing key material.

## Automatic server discovery

With `Proxy.Mode: native` (or `both`), no backend list or network server ID has to be copied by hand. The universal Velocity/Bungee bridge already owns the registered-server list. When the first player connects, each Paper/Purpur backend asks the proxy for its own identity; the proxy answers only that source connection with its exact registered name, such as `lobby` or `survival`. Chatalyst then uses those names for topology cards, monitored chat, staff presence, support routing and the player directory.

```yaml
Proxy:
  Enabled: true
  Mode: native
  Server_Discovery:
    Mode: auto
    Manual_Server_Id: ""
```

Plugin messaging needs a connected player, so before the first join Chatalyst temporarily falls back to `Database.Server_Id`, then `Redis.Server_Id`, and finally `server-<port>`. The proxy-authored identity replaces that fallback as soon as a carrier is available. It is requested again after reloads and player joins, so the network can recover without a backend restart.

Manual operation remains available for legacy, Redis-only or deliberately fixed setups:

```yaml
Proxy:
  Server_Discovery:
    Mode: manual
    Manual_Server_Id: "survival"
```

In manual mode the proxy response is ignored. If `Manual_Server_Id` is empty, the same compatibility fallback applies. `Database.Server_Id` and `Redis.Server_Id` still control their respective storage/Redis namespaces; automatic proxy discovery changes only the runtime network identity and never renames stored data.

## Backend Config

```yaml
Proxy:
  Enabled: true
  Mode: redis

Redis:
  Enabled: true
  Host: "127.0.0.1"
  Port: 6379
  Password: ""
  Database: 0
  Channel: "chatalyst:network"
  Server_Id: "survival-1"
  # TLS for the Redis connection. Strongly recommended when Redis is reached over a network.
  TLS: false
  # REQUIRED. Same long random value (16+ chars, 32+ recommended) on every backend.
  Shared_Secret: "change-me-to-a-long-random-string"
```

Every backend must use the same Redis `Channel` and the same `Shared_Secret`, but each backend should have a unique `Server_Id`.

## Targeted rule console commands

`Actions.Proxy_Commands` is a privileged rules-engine action. Each entry has the form
`"<target-server> <command>"`; it is never broadcast and cannot address the proxy console. The
target is the exact runtime identity shown in the network administration center, such as
`survival`:

```yaml
# network.yml — configure this on the sender and every possible receiver
Proxy:
  Enabled: true
  Rule_Commands:
    Enabled: true
    Allowed_Commands: [broadcast, say]

# rules.yml
Rules:
  remote-notice:
    Match: '^network notice$'
    Actions:
      Proxy_Commands:
        - "survival broadcast {player} triggered {rule}"
```

Authentication alone does not authorize remote console access. The sending backend and receiving
backend both require `Rule_Commands.Enabled: true`, and the command label must appear in each
local `Allowed_Commands`; wildcards are not accepted. Control characters, oversized commands,
invalid/self/proxy targets and trailing packet data are rejected. Packets carry a bounded
timestamp and event UUID, so `Proxy.Mode: both` cannot execute one action twice. Every accepted
execution produces a `RULE` audit entry with the declared authenticated-backend source, using the shared
sensitive-command redaction boundary before writing command arguments.

Native and legacy plugin messaging still need a player carrier on the sending side and an active
backend connection on the destination. Use Redis mode when remote automation must work without
players online. Keep the allowlist minimal and prefer a dedicated narrow command over granting a
general administrative command.

## Redis Message Security

`Shared_Secret` is **mandatory**: Redis sync stays disabled until it is set (the startup log tells you). Every message is signed with HMAC-SHA256 and carries a timestamp + nonce, so only your own servers can publish to the channel and replayed messages are rejected. If messages between servers stop flowing, check that all backends share the exact same secret and that their clocks are within 5 minutes of each other.

Enable `TLS: true` whenever Redis is not on localhost — the plain Redis protocol sends the password and every message unencrypted.

## Shared Database Cache

With `Database.Network_Shared: true` (shared MySQL), Redis sync also propagates cache invalidations: a report claimed or mail sent on one server becomes visible on the others within moments. Without Redis, each server only sees its own cached data until restart — enable `Proxy.Mode: redis` on networks with a shared database.

## Channel Config

```yaml
Channels:
  List:
    global:
      Proxy: true
```

Local channel types such as frequency, world, region, and party are intentionally local unless you explicitly mark a channel as proxy-forwarded.

## Network Administration Center

The main administration GUI includes a network operations center for staff with `chatalyst.admin.network.monitor`. It shows the proxy servers, current population and a paginated recent-chat timeline for each backend. Staff can inspect a server without connecting to it and click an entry to read its complete text.

```yaml
Network_Admin:
  Monitor:
    Enabled: true
    History_Per_Server: 100
    Retention_Minutes: 60
  Announcements:
    Enabled: true
    Max_Message_Length: 512
```

The monitor relays only public and channel messages processed by Chatalyst. It does not capture commands, passwords, private messages or mail. History is bounded, deduplicated, kept only in memory and discarded on restart. Access is separate from normal administration: `chatalyst.admin.network.monitor` reads timelines and `chatalyst.admin.network.announce` enables the network scope in `/announce`.

The native Velocity/Bungee bridge automatically supplies the full registered-server list, this backend's exact identity and live player counts. Redis-only mode still relays observed chat and announcements without a carrier player, but it can only list servers that have produced local or observed activity because Redis does not expose proxy topology; use manual discovery there when stable names are required.

## One Universal Jar

Install the signed `Chatalyst-<version>.jar` unchanged in every backend `plugins` directory and in the proxy `plugins` directory. The jar contains three isolated descriptors. Paper reads `plugin.yml`, Velocity reads `velocity-plugin.json`, and BungeeCord reads `bungee.yml`; the other platform entry points remain unloaded. Separate proxy downloads are no longer required.

## Live Multi-server Feeds

From `/cht admin`, **Live server feeds** lets each staff member mirror up to two server chats into their current chat. The limit is configurable with `Network_Admin.Monitor.Max_Live_Subscriptions`. The network center also supports right-clicking a server card to toggle its live feed, while left-click opens the bounded timeline.

Permissions are deliberately layered:

- `chatalyst.admin.network.monitor` opens the monitor.
- `chatalyst.admin.network.monitor.server.*` allows every backend, while `chatalyst.admin.network.monitor.server.lobby` allows only `lobby`.
- Channel access is still checked. A restricted `staff` channel requires its normal channel permission, or the explicit override `chatalyst.admin.network.monitor.channel.staff`. The `channel.*` node allows every channel.

Operators receive both wildcard nodes by default. With LuckPerms, grant the base monitor node plus only the server/channel nodes appropriate to each staff group.

## Natural Private Support

Players do not need to know an `@` syntax. Natural questions such as `any admin?`, `is an owner available?`, `hay alguien del staff?`, or `necesito un helper` are recognized in the default chat. Chatalyst requires a whole configured rank or team word plus help intent, so ordinary phrases such as `I bought the admin rank` are not intercepted. The original request never appears in general chat, channel history, or network-monitor feeds.

Requests always start at the lowest configured tier (`helper` by default), even when the player asks for an admin. If nobody answers, the alert advances through `mod`, `admin`, and `owner` across the proxy. Staff receives a visible **Reply** action in chat; no accept command needs to be typed. The proxy bridge arbitrates simultaneous clicks, so only the first staff member opens the session. Everyone else is told who is already handling the player, and stale clicks cannot replace the winner. Both participants then type normally and only they receive those messages. Staff closes with `/support close`; the user is explicitly returned to the default/general channel. Pending requests and inactive sessions expire automatically.

```yaml
Staff_Mentions:
  Enabled: true
  Require_At: false
  Groups: [admin, administrador, helper, ayudante, mod, moderador, owner, soporte, staff, equipo]
  Help_Intent_Words: [algun, alguna, alguien, hay, necesito, ayuda, ayudar, atender, puede, podria, disponible, hablar, need, help, assist, available, support]
  LuckPerms_Group_Permission: chatalyst.staff.mentionable
  Cooldown_Seconds: 15
  Notify_Sender: true
  Priority:
    Escalation_Seconds: 8
    Tiers:
      helper:
        Groups: [helper, ayudante]
        Permission: chatalyst.support.tier.helper
      mod:
        Groups: [mod, moderador]
        Permission: chatalyst.support.tier.mod
      admin:
        Groups: [admin, administrador]
        Permission: chatalyst.support.tier.admin
      owner:
        Groups: [owner]
        Permission: chatalyst.support.tier.owner
  Private_Chat:
    Enabled: true
    Pending_Timeout_Seconds: 300
    Session_Timeout_Minutes: 30
    Max_Message_Length: 512
```

Matching is case- and accent-insensitive, uses precompiled whole-word patterns, and ignores email-like text. LuckPerms is read only during load/reload, never in the per-message hot path. Mute, sanitizing, rules and anti-spam still run before private-session capture.

Any loaded LuckPerms group inheriting `chatalyst.staff.mentionable` is added automatically, so custom group names do not need to be duplicated in the list. Run `/cht reload` after changing group permissions.

## Live Staff Directory and Teleport

The administration GUI includes a paginated, ephemeral staff directory. Each entry shows the staff member's configured tier, backend, and world. Staff with `chatalyst.admin.staff.directory` can view it; `chatalyst.admin.staff.teleport` enables one-click teleport. For a remote target, the native Velocity/Bungee bridge connects the requester to the correct backend and then performs the exact teleport there.

Presence is kept only in memory, refreshed every 10 seconds, and expires automatically. The destination backend checks the teleport permission again before moving the requester. Cross-server teleport requires `Proxy.Mode: native` (or `both`) and the universal jar on the proxy; Redis and legacy forwarding can still carry chat, but they cannot securely move a player between proxy servers.

## Players by Server

The administration GUI also provides **Players by server**. It first displays the permitted proxy backends; selecting `survival`, for example, opens only the fresh player profiles published by Survival. Each player head shows the username, primary LuckPerms/Vault rank, Vault balance, backend, world, game mode, ping and AFK state. Clicking the head teleports the administrator to that player, including a native proxy handoff when they are on another backend.

Permissions are separated for least privilege:

- `chatalyst.admin.players.directory` opens the browser.
- `chatalyst.admin.players.server.*` exposes every backend, while `chatalyst.admin.players.server.survival` exposes only Survival.
- `chatalyst.admin.players.teleport` enables the head-click teleport action.

Vanished players are never published to the network directory. Presence remains memory-only, is refreshed every 10 seconds and expires after 30 seconds. The destination backend rechecks the directory, teleport and server permissions against the player's actual current backend before moving the administrator.
