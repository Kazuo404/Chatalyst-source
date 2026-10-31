# Discord Bot and Staff Workflow

Chatalyst includes its own two-way Discord bot. It needs no DiscordSRV, external process,
or webhooks: the bot connects when the plugin starts and disconnects when it stops.

## Complete tutorial: create and install the bot

1. Open the official
   [Discord Developer Portal](https://discord.com/developers/applications), sign in,
   select **New Application**, enter a name, and press **Create**.
2. Open **Bot**, optionally customize its name/icon, and press **Reset Token**. Copy the
   token once: it is a password, not the Application ID, Public Key, or Client Secret.
   Never publish it or commit it to Git.
3. Under **Privileged Gateway Intents**, enable:

   - **MESSAGE CONTENT INTENT**, required for chat and `!` commands.
   - **SERVER MEMBERS INTENT** when `Rank_Sync.Direction` includes Discord → Minecraft
     (`BOTH` includes it).

4. Open **Installation**. Keep **Guild Install** enabled under Installation Contexts,
   select **Discord Provided Link**, and add the `bot` scope to Guild Install.
   `applications.commands` is optional because Chatalyst uses text commands, not slash
   commands.
5. Request only **View Channels**, **Send Messages**, **Read Message History**, and
   **Manage Roles** when rank synchronization will be used.
6. Copy the **Install Link**, open it, choose **Add to server**, and select your server.
7. For rank sync, open **Server Settings > Roles** and move the bot role above every role
   it will manage. Discord prevents a bot from granting roles at or above its highest role.
8. Open **User Settings > Advanced**, enable **Developer Mode**, then right-click the server
   and channel to copy their numeric IDs.
9. Edit `plugins/Chatalyst/integrations.yml`:

```yaml
Discord:
  Enabled: true
  Bot_Token: "PRIVATE_TOKEN"
  Guild_Id: "123456789012345678"
  Channel_Id: "234567890123456789"
  Minecraft_To_Discord: true
  Discord_To_Minecraft: true
  To_Discord_Format: "**{player}**: {message}"
  To_Minecraft_Format: "<#8B5CF6>[Discord] <#FBF2FF>{discord_user}<#C0B3D9>: <reset>{message}"
```

Restart the server or run `/chatalyst reload`. The console first confirms startup and then
the bot account that connected. Reset the token immediately in the Developer Portal if it
is ever exposed.
Run `/chatalyst doctor` to find invalid IDs or incomplete configuration.

Official references: [create and install a Discord app](https://docs.discord.com/developers/quick-start/getting-started),
[Gateway and intents](https://docs.discord.com/developers/events/gateway), and
[permissions/role hierarchy](https://docs.discord.com/developers/topics/permissions).

The bot ignores bot and webhook messages to prevent loops. Minecraft messages cannot
produce real user, role, or `@everyone` mentions.

## Per-channel routing

The main Discord channel mirrors global chat. Local and private channels never leave the
game unless explicitly mapped:

```yaml
Discord:
  Channel_Ids:
    trade: "345678901234567890"
    staff: "456789012345678901"
```

An incoming message in a mapped Discord channel is delivered only to players listening to
the matching Chatalyst channel. An unknown game-channel name is blocked and logged.

## Staff report commands

Staff alerts use the same bot in a private channel:

```yaml
Discord:
  Staff:
    Enabled: true
    Channel_Id: "567890123456789012"
    Priority_Channel_Id: ""
    Handle_From_Discord: true
    Command_Prefix: "!"
    # REQUIRED: an allowed channel plus an allowed user or role.
    Allowed_Channel_Ids: ["567890123456789012"]
    Allowed_User_Ids: []
    Allowed_Role_Ids: ["678901234567890123"]
```

Authorization is fail-closed: the message must come from an allowed channel **and** from
an allowed user or role. A private channel alone is not an identity check.

Commands:

- `!reports`
- `!info <id>`
- `!claim <id>`
- `!reply <id> <message>`
- `!resolve <id> [note]`
- `!pending <id> [note]`
- `!reject <id> [note]`
- `!reopen <id>`

Priority tickets are blocked from Discord by default. Enable
`Discord.Staff.Priority_From_Discord` only when the channel and authorization lists are
restricted to the appropriate rank.

## Linking, DMs, and rank synchronization

The player runs `/discord link` in Minecraft and sends `!link CODE` to the bot by DM
(the configured guild also works). Codes are single-use and expiring, and the resulting
link is stored in YAML/SQL. `/discord unlink` removes it. Report updates can use the link
to DM the reporter.

Manual mappings remain available as an administrative fallback:

```yaml
Discord:
  Linked_Accounts:
    "00000000-0000-0000-0000-000000000000": "789012345678901234"
  Staff:
    DM_Reporter: true
```

The key is always the Minecraft UUID and the value is the Discord user ID.

## Central rank catalog

Configure each rank once. The YAML key (`owner`, `vip`, `vip_monthly`) is the stable Rank
ID used by Chatalyst and Tebex; `LuckPerms_Group` is the exact LuckPerms group, and
`Discord_Role_Id` is the numeric Discord role ID:

```yaml
Rank_Catalog:
  owner:
    LuckPerms_Group: "owner"
    Discord_Role_Id: "111111111111111111"
    Discord_Priority: 1000
    Tebex_Enabled: false
    Tebex_Duration: "permanent"
  vip:
    LuckPerms_Group: "vip"
    Discord_Role_Id: "222222222222222222"
    Discord_Priority: 100
    Tebex_Enabled: true
    Tebex_Duration: "permanent"

Discord:
  Rank_Sync:
    Enabled: true
    Direction: BOTH
    Initial_Source: MINECRAFT
    Fallback_Group: default
```

`Discord_Priority` selects the winner when a member holds several managed roles. Only
catalog groups can flow from Discord into LuckPerms. Changes are event-driven and
coalesced for 350 ms.

## Tebex-purchased ranks

Install the official Tebex plugin, set `Tebex_Enabled: true` on the catalog rank, and
enable `Tebex.Enabled`. Use the Rank ID, not the raw LuckPerms group:

```text
chatalyst tebex grant {id} vip {transaction}
```

Use this for Expiry, Refund, and Chargeback:

```text
chatalyst tebex revoke {id} vip {transaction}
```

Set the duration through `Tebex_Duration` (`permanent`, `30d`, `12h`, etc.). The
transaction and absolute expiry are persisted before LuckPerms changes, so a retry cannot
duplicate or extend the rank. Keep `Console_Only: true`.
When `Rank_Catalog` exists, the old syntax that accepted a raw group and duration is
blocked so it cannot bypass `Tebex_Enabled`.

### Player rank store

`/cht ranks` opens a paginated purchase GUI; `/cht shop`, `/cht shops`, `/cht tienda`, and
`/cht rangos` are aliases. The player store and administrative editor have separate jobs:
`/cht ranks` displays products, while `/cht tebex gui` designs the celebration shown after
a purchase is delivered.

Enable the store and give each card the package's exact HTTPS URL:

```yaml
Tebex:
  Enabled: true
  Shop:
    Enabled: true
    Title: "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Rank Store</bold></gradient>"
    Home_Url: "https://your-store.tebex.io"
    Selection_Message: "<#C0B3D9>You selected {rank}. Use the secure button:"
    Button_Text: "<gradient:#B56CFF:#E4A8FF><bold>BUY {rank_id}</bold></gradient>"
    Show_Raw_Link: false

Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Tebex_Enabled: true
    Tebex_Duration: "30d"
    Shop:
      Visible: true
      Display_Name: "<gradient:#B56CFF:#E4A8FF><bold>VIP</bold></gradient>"
      Description:
        - "<#C0B3D9>Exclusive colors and cosmetics"
        - "<#C0B3D9>Lasts for 30 days"
      # Vanilla material or ItemsAdder namespaced ID:
      Icon: "example:vip_icon"
      Price: "$9.99 USD"
      Purchase_Url: "https://your-store.tebex.io/package/1234567"
      Order: 10
      Featured: true
      View_Permission: ""
```

An inventory click cannot silently open a browser: Minecraft requires the player to confirm
an `open_url` event. Chatalyst therefore closes the GUI and sends a clickable chat button
linked only to the selected package. Only valid HTTPS URLs are published; HTTP, malformed,
or credential-bearing URLs are rejected. As an alternative to `Purchase_Url`,
`Tebex.Shop.Rank_Url_Template` supports `{rank_id}`, `{player}`, and `{uuid}`, although an
exact per-package URL is safer when Tebex uses numeric IDs.

`Shop.Icon` accepts vanilla materials (`EMERALD`) and ItemsAdder IDs
(`namespace:vip_icon`). Names, descriptions, messages, and buttons also resolve ItemsAdder
font images such as `:vip_badge:`. If ItemsAdder is missing or the ID does not exist, the
card safely falls back to `EMERALD`; `/cht doctor` explains the fallback.

### Purchase presentation

Open `/chatalyst tebex gui` (or `/cht tebex gui`) to edit delivery without touching files.
The first card controls the global presentation; the remaining cards represent every
Tebex-enabled `Rank ID`. Each rank can inherit the global design or own a custom one.

The GUI controls the audience (`PURCHASER` for the buyer only or `GLOBAL` for everyone),
the `SUBTLE`, `FANCY`, `SILENT`, and `CUSTOM` presets, every MiniMessage text, chat, toast,
title, action bar, sound, and cosmetic firework effects, plus a preview before saving.
Presets only change effect switches; they never replace custom text.

The same settings are available in `integrations.yml`:

```yaml
Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Tebex_Enabled: true
    Tebex_Duration: "30d"
    Presentation:
      Use_Global: false
      Preset: FANCY
      Audience: GLOBAL
      Chat:
        Message: "<gradient:#B56CFF:#E4A8FF>✦ {player} is now {rank_id} ✦</gradient>"
      Title:
        Enabled: true
        Title: "<gold><bold>NEW RANK!</bold></gold>"
      Firework:
        Enabled: true

Tebex:
  Presentation:
    Enabled: true
    Audience: PURCHASER
    Preset: SUBTLE
    Offline_Delivery: true
```

Available placeholders are `{player}`, `{rank_id}`, `{rank}`, `{group}`, `{duration}`, and
`{transaction}`. The presentation runs only after LuckPerms confirms the rank and Chatalyst
durably commits the `DONE` receipt. A private delivery made while the buyer is offline is
stored and displayed on their next join. Fireworks are cosmetic and deal no damage. This
feature does not depend on the Discord bot.
