---
title: Discord bot
description: A two-way Discord bot built into Chatalyst. Chat bridge, account linking, rank and ban sync, punishment roles, and staff tools from Discord, with no DiscordSRV.
---

Chatalyst includes its own Discord bot. It connects when your server starts and disconnects when it stops, so there is no DiscordSRV, no external process and no webhook to maintain. One bot does everything: it mirrors chat both ways, links Minecraft accounts to Discord, keeps ranks and bans in sync, and gives your staff a private channel to handle reports and moderation from Discord.

Everything is off by default. You turn on only the parts you want, in the `Discord` section of `integrations.yml`.

## Creating the bot

1. Open the [Discord Developer Portal](https://discord.com/developers/applications), click **New Application**, give it a name and create it.
2. Open **Bot** and press **Reset Token**. Copy the token: it works like a password. Never share it.
3. Under **Privileged Gateway Intents**, enable **MESSAGE CONTENT INTENT**. Enable **SERVER MEMBERS INTENT** too if you will sync ranks from Discord to Minecraft.
4. Open **Installation**, keep **Guild Install**, and add the `bot` scope. Give it **View Channels**, **Send Messages** and **Read Message History**. Add **Manage Roles** for rank sync or the linked role, and **Ban Members** for ban sync.
5. Use the install link to add the bot to your Discord server.
6. In Discord, open **User Settings > Advanced** and turn on **Developer Mode**. Right-click your server and your chat channel to copy their IDs.
7. Fill in `integrations.yml`:

```yaml
Discord:
  Enabled: true
  Bot_Token: "YOUR_PRIVATE_TOKEN"
  Guild_Id: "123456789012345678"
  Channel_Id: "234567890123456789"
```

8. Restart the server or run `/chatalyst reload`. The console confirms which bot account connected.

Run `/chatalyst doctor discord` at any time: it asks Discord directly whether the bot has the permissions, role position and intents your configuration needs.

> [!DANGER]
> If your token is ever exposed, reset it in the Developer Portal immediately. Anyone with the token controls the bot.

> [!TIP]
> For rank sync, the linked role, ban sync and punishment roles, move the bot's role **above** every role it has to give, take or ban in **Server Settings > Roles**. Discord does not let a bot manage roles at or above its own.

## Chat bridge

```yaml
Discord:
  Activity: "with {online}/{max} players"
  Activity_Refresh_Seconds: 60
  Minecraft_To_Discord: true
  Discord_To_Minecraft: true
  Channel_Ids: {}
  To_Discord_Format: "**{player}**: {message}"
  Channel_Formats: {}
  To_Minecraft_Format: "<#5865F2>[Discord] <#FBF2FF>{discord_user}<#C0B3D9>: <reset>{message}"
  Max_Inbound_Length: 256
  Announce_Join_Quit: false
  Join_Message: "{player} joined"
  Quit_Message: "{player} left"
```

| Option | Default | What it does |
| --- | --- | --- |
| `Activity` | `with {online}/{max} players` | The bot's "Playing…" status. `{online}` never counts vanished staff |
| `Activity_Refresh_Seconds` | `60` | How often the status updates |
| `Minecraft_To_Discord` | `true` | Sends game chat to Discord |
| `Discord_To_Minecraft` | `true` | Sends Discord messages into the game |
| `Channel_Ids` | `{}` | Maps a game channel to its own Discord channel, e.g. `trade: "123..."` |
| `To_Discord_Format` | `**{player}**: {message}` | How a game message looks in Discord |
| `Channel_Formats` | `{}` | A different look per game channel, so trade and auctions sharing one Discord room read differently. Placeholders: `{player}`, `{message}`, `{channel}` |
| `To_Minecraft_Format` | see above | How a Discord message looks in game |
| `Max_Inbound_Length` | `256` | Longest Discord message accepted into the game |
| `Announce_Join_Quit` | `false` | Posts joins and quits to Discord |

The main channel mirrors global chat. Private and local channels never leave the game unless you map them in `Channel_Ids`. Messages from Discord go through the swear filter before anyone in game sees them, the bot ignores other bots and webhooks, and nothing typed in game can ping `@everyone` or a role in Discord.

### Market cards

When a sale or auction closes on the [market](market), the bot can post a card with the seller, the buyer, the item and the price.

```yaml
Discord:
  Market:
    Enabled: false
    Channel_Id: ""
    Sales: true
    Auctions: true
    Returns: false
```

`Channel_Id` empty uses the main channel. `Returns` (expired, withdrawn or unsold listings) is off because it is the noisiest.

## Account linking

Players link their Minecraft account to Discord in two steps:

1. In game: `/discord link`. They receive a short code.
2. In Discord: they send `!link CODE` to the bot, by private message or in the configured channel.

```yaml
Discord:
  Linking:
    Enabled: true
    Command: "!link"
    Channel_Id: ""
    Linked_Role_Id: ""
    Code_Expires_Seconds: 600
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `true` | Allows linking |
| `Command` | `!link` | The Discord command that completes the link |
| `Channel_Id` | `""` | Channel where the command is accepted. Empty = any channel. Private messages to the bot always work |
| `Linked_Role_Id` | `""` | Role given when an account links and removed when it unlinks. Empty = none |
| `Code_Expires_Seconds` | `600` | How long a code stays valid |

The link is stored by the account's UUID, so a name change or a `/nick` never breaks it. `/discord unlink` removes it and `/discord status` shows it. Codes are single-use and work across servers that share a database.

### Mandatory linking

`Linking.Required` makes linking a condition for playing. An unlinked player is held when they join: they get their code automatically and cannot chat or use commands until they link.

```yaml
    Required:
      Enabled: false
      Only_Buyers: true
      Bypass_Permission: "chatalyst.discord.exempt"
      After_Auth_Login: true
      Auth_Fallback_Seconds: 20
      Auth_Login_Event: ""
      Announce_Delay_Ticks: 20
      Reminder_Seconds: 45
      Kick_After_Seconds: 0
      Auto_Code: true
      Sound: "minecraft:entity.experience_orb.pickup"
      Restrict:
        Chat: true
        Commands: true
        Movement: false
        Interaction: true
        Damage: true
      Allowed_Commands:
        - "/discord"
        - "/login"
        - "/register"
        - "/l"
        - "/reg"
        - "/premium"
        - "/help"
```

| Option | Default | What it does |
| --- | --- | --- |
| `Only_Buyers` | `true` | Holds only players who bought a rank whose Discord role cannot be delivered until they link. `false` holds everyone |
| `Bypass_Permission` | `chatalyst.discord.exempt` | Players with this permission are never held (op by default) |
| `After_Auth_Login` | `true` | With AuthMe, nLogin or similar, waits until the player has logged in |
| `Auth_Fallback_Seconds` | `20` | If the login plugin's event cannot be detected, shows the prompt this many seconds after joining |
| `Auth_Login_Event` | `""` | Event class of an unlisted login plugin |
| `Reminder_Seconds` | `45` | How often the reminder repeats |
| `Kick_After_Seconds` | `0` | Disconnects a player still unlinked after this time. `0` never kicks |
| `Auto_Code` | `true` | Issues the code automatically |
| `Restrict.*` | see above | What a held player cannot do. `Damage` blocks damage taken and dealt |
| `Allowed_Commands` | see above | Commands a held player may use. `/discord` always works; add your login plugin's commands |

> [!NOTE]
> Mandatory linking is **fail-open**. It only holds players while the whole chain really works: the bot enabled, a valid token, the connection to Discord ready and your server reachable. If Discord is down or the bot was removed, nobody is held and anyone waiting is released. Players are never locked out for something outside their control.

## Rank sync

Keeps LuckPerms groups and Discord roles matched. Ranks are declared once in the central `Rank_Catalog` at the top of `integrations.yml`, which is shared with Tebex (see [store](store#the-rank-catalog)).

```yaml
Discord:
  Rank_Sync:
    Enabled: false
    Direction: BOTH
    Initial_Source: MINECRAFT
    Exclusive_Discord_Roles: true
    Remove_On_Discord_Role_Loss: true
    Fallback_Group: "default"
    Sync_On_Ready: true
    Ready_Sync_Limit: 500
    Debounce_Milliseconds: 350
```

| Option | Default | What it does |
| --- | --- | --- |
| `Direction` | `BOTH` | `BOTH`, `MINECRAFT_TO_DISCORD` or `DISCORD_TO_MINECRAFT` |
| `Initial_Source` | `MINECRAFT` | Which side wins right after an account is linked |
| `Exclusive_Discord_Roles` | `true` | A member wears one rank role at a time: giving the role of their rank removes the other managed rank roles. `Discord_Priority` decides which wins when a member holds several |
| `Remove_On_Discord_Role_Loss` | `true` | Losing the role in Discord removes the group in game |
| `Fallback_Group` | `default` | Group given when a player loses their synced rank |
| `Sync_On_Ready` | `true` | Checks linked members when the bot connects |
| `Ready_Sync_Limit` | `500` | Maximum members checked at that moment |
| `Debounce_Milliseconds` | `350` | Groups quick changes into one update |

Discord can never grant a LuckPerms group that is not in the catalog, and it can never take away a rank that was paid for through Tebex.

## Ban sync

One ban, both sides. A player banned in Minecraft loses their Discord seat, and someone banned on Discord stops playing.

```yaml
Discord:
  Ban_Sync:
    Enabled: false
    Direction: BOTH
    Minecraft_Reason: "Banned on our Discord server"
```

`Direction` is `BOTH`, `TO_DISCORD` or `TO_MINECRAFT`. Accounts are matched only through the link, never by name, so an unlinked player is simply not mirrored. The bot needs the **Ban Members** permission.

Only a **permanent** Minecraft ban becomes a Discord ban, because a Discord ban has no expiry and would outlive a temporary one. A temporary ban is shown with a punishment role instead (see below), if that feature is on. Before banning, the bot sends the player a private message, since afterwards Discord may no longer let it reach them.

## Punishment roles

The bot can create and maintain roles that show a punishment in the member list: banned, frozen or muted, with a rough idea of how long is left. It creates the roles itself.

```yaml
Discord:
  Punishment_Roles:
    Enabled: false
    Refresh_Seconds: 300
    Server_Name: "our server"
    Ban:
      Remove_On_Permanent: true
      Dm: "You were permanently banned from **{server}**.\nReason: {reason}"
      Appeal: "If you believe this is a mistake, you can appeal by replying to this message."
      Tiers:
        month:
          Name: "Banned · 1+ month"
          Color: "#8B0000"
          Min_Remaining_Seconds: 2592000
        week:
          Name: "Banned · 1+ week"
          Color: "#FF4D6D"
          Min_Remaining_Seconds: 604800
        days:
          Name: "Banned · days"
          Color: "#FF7A9E"
          Min_Remaining_Seconds: 86400
        hours:
          Name: "Banned · hours"
          Color: "#FF4D6D"
          Min_Remaining_Seconds: 0
```

The same structure exists for `Freeze` (one tier, "Frozen · under review") and `Mute` ("Muted · 1+ day" and "Muted"). Tiers are matched from the longest down, so keep the last one at `0`. `Refresh_Seconds` moves members down a tier as their time runs out and removes the role when the punishment ends. With `Remove_On_Permanent: true` a permanent ban removes the member from Discord (after the `Dm` message); with `false` they stay and wear the ban role. Renaming a tier leaves the old role in place on purpose: delete it by hand in Discord.

> [!NOTE]
> Tiers exist because a Discord role name is shared by everyone who wears it, so it cannot show each member's exact time. The exact time goes in the private message.

## Staff channel

Reports, staff chat and security alerts can go to a private Discord channel through the same bot.

```yaml
Discord:
  Staff:
    Enabled: false
    Channel_Id: ""
    Priority_Channel_Id: ""
    Reports: true
    Staff_Chat: true
    Alerts: true
    Handle_From_Discord: false
    Commands_From_Discord: false
    Command_Prefix: "!"
    Allowed_Channel_Ids: []
    Allowed_User_Ids: []
    Allowed_Role_Ids: []
    DM_Reporter: false
    Priority_From_Discord: false
```

| Option | Default | What it does |
| --- | --- | --- |
| `Channel_Id` | `""` | The private staff channel |
| `Priority_Channel_Id` | `""` | Separate channel for priority reports. Empty uses `Channel_Id` |
| `Reports` / `Staff_Chat` / `Alerts` | `true` | What is posted there |
| `Handle_From_Discord` | `false` | Lets staff manage reports from Discord |
| `Commands_From_Discord` | `false` | Lets staff run moderation commands from Discord |
| `Allowed_Channel_Ids` | `[]` | Channels where commands are accepted |
| `Allowed_User_Ids` / `Allowed_Role_Ids` | `[]` | Who may use them (user IDs or role IDs) |
| `DM_Reporter` | `false` | Sends the reporter a private message about their report, if their account is linked |
| `Priority_From_Discord` | `false` | Allows priority reports to be listed and handled from Discord |

A command is accepted only when it comes from an allowed channel **and** from an allowed user or role. A private channel alone is never treated as permission.

**Report commands** (with `Handle_From_Discord`): `!reports`, `!info <id>`, `!claim <id>`, `!reply <id> <message>`, `!resolve <id> [note]`, `!pending <id> [note]`, `!reject <id> [note]`, `!reopen <id>`. Report cards also carry buttons.

**Moderation commands** (with `Commands_From_Discord`): the [staff suite](staff-suite) tools that make sense from outside the game, such as `!ban`, `!kick`, `!freeze`, `!mute`, `!check` and `!seen`. They run as the console and their answer comes back to the channel. Tools that need you in the game (`/invsee`, `/tpto`, `/vanish`…) are refused with the reason, and `/clearinventory` is refused because it cannot be undone.

## Limits

- The bot uses text commands (`!link`, `!reports`), not slash commands.
- Linking needs the player to be online in game to get a code.
- Moderation commands from Discord only work while the [staff suite](staff-suite) is enabled.
- If you run another Discord bridge (such as DiscordSRV) at the same time, it keeps posting on its own, including real names of [anonymous](anonymous) players. Remove it once the built-in bot is on.
