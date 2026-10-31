---
title: Store, ranks & Tebex
description: Sell ranks and one-time goods through Tebex with deliveries that are never lost. Rank catalog, in-game storefront, item claims and purchase celebrations.
---

Chatalyst connects your Tebex store to the game without a second queue or a separate plugin. It sells two kinds of things, and treats them differently on purpose:

- **Ranks** are a *state*. The player holds the rank, it may expire, and if it ever goes missing Chatalyst puts it back. Ranks are defined in `integrations.yml` and repaired continuously.
- **Goods** (items, commands, money) are an *event*. They are handed over exactly once and never repeated, even if the command arrives twice. Goods are defined in `store.yml`.

In both cases a purchase is written to disk before anything is given, because Tebex considers a command done the moment it sends it and never resends it. Nothing depends on Tebex retrying.

Players browse everything in one in-game storefront, `/ranks`, and collect their goods with `/store`.

> [!NOTE]
> You need the official Tebex plugin installed. Chatalyst uses its command executor and never polls Tebex a second time, which would risk delivering the same purchase twice.

## The rank catalog

Every rank is declared once in `Rank_Catalog` at the top of `integrations.yml`. The key (`vip` below) is a stable ID used by Tebex, Discord and the storefront. It is empty by default:

```yaml
Rank_Catalog: {}
```

An example, taken from the comments of the real file:

```yaml
Rank_Catalog:
  vip:
    LuckPerms_Group: "vip"
    Discord_Role_Id: "222222222222222222"
    Discord_Priority: 100
    Tebex_Enabled: true
    Tebex_Duration: "permanent"
    Presentation:
      Use_Global: true
    Shop:
      Visible: true
      Display_Name: "<gradient:#B56CFF:#E4A8FF><bold>VIP</bold></gradient>"
      Description:
        - "<#C0B3D9>Exclusive chat colors and cosmetics"
      Icon: EMERALD
      Price: "$9.99 USD"
      Purchase_Url: "https://your-store.tebex.io/package/1234567"
      Order: 10
      Featured: true
      View_Permission: ""
      Preview:
        - Icon: DIAMOND_HELMET
          Name: "<#E0AAFF>Rank prefix"
          Lore:
            - "<#C0B3D9>Shown in chat and the tab list"
```

| Option | What it does |
| --- | --- |
| `LuckPerms_Group` | The exact LuckPerms group this rank gives |
| `Discord_Role_Id` | The Discord role that goes with it (see [Discord bot](discord-bot#rank-sync)) |
| `Discord_Priority` | Which role wins when a member holds several |
| `Tebex_Enabled` | Whether Tebex may deliver this rank |
| `Tebex_Duration` | `permanent`, or a duration such as `30d` or `12h` |
| `Presentation` | Celebration shown on delivery; `Use_Global: true` uses the global one |
| `Shop` | The card shown in `/ranks`. Needs `Visible: true` and a real HTTPS `Purchase_Url` |
| `Shop.Icon` | A vanilla material (`EMERALD`) or an ItemsAdder id (`myserver:vip_icon`) |
| `Shop.Preview` | What the buyer sees before paying. Write here exactly what the rank includes |

## Selling ranks with Tebex

Turn it on in `integrations.yml`:

```yaml
Tebex:
  Enabled: false
  Console_Only: true
  Reconcile:
    Interval_Seconds: 300
    Batch_Size: 10
  Allowed_Groups: []
  Notify_Discord: true
```

Then, in your Tebex panel, give the package these commands (`vip` is the catalog ID):

```text
Initial:  chatalyst tebex grant {id} vip {transaction}
Expiry:   chatalyst tebex revoke {id} vip {transaction}
```

Use the same `revoke` line for refunds and chargebacks. Always use `{id}` (the player's UUID), not `{username}`: a name can change, a UUID never does.

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | Turns rank delivery on |
| `Console_Only` | `true` | Only the console (Tebex) may run the delivery commands. Keep it on |
| `Reconcile.Interval_Seconds` | `300` | How often the background check runs |
| `Reconcile.Batch_Size` | `10` | Players checked per pass. Half of it is reserved for ranks that just expired |
| `Allowed_Groups` | `[]` | Groups Tebex may give. Empty = any existing group; list your store ranks for maximum safety |
| `Notify_Discord` | `true` | Announces deliveries in the private staff Discord channel |

### Nothing paid for is ever lost

A purchase is stored as what the player owns: the rank, its exact expiry and the transaction. The LuckPerms group and the Discord role are then derived from that record. Chatalyst compares the record with reality and repairs any difference when the player joins, after every purchase, when a buyer links Discord, and in a slow background sweep.

This covers a failed delivery, but also things no retry could notice: a rank removed by hand, a Discord sync overwriting a paid group, or a restored backup.

A rank ends when its `Tebex_Duration` runs out. The expiry is written onto the LuckPerms group itself, so permissions stop at the exact second, and the sweep then updates the Discord role and the record.

> [!WARNING]
> Chatalyst only touches groups it delivered itself, so a rank you gave by hand is never removed. The flip side: if you remove a **paid** rank by hand in LuckPerms, it comes back on the next check, because the purchase says the player still owns it. Use `/chatalyst tebex revoke` or let the duration end it.

### Payments outside Tebex

For a payment taken elsewhere (a direct transfer, a deal on Discord), deliver it by hand with the payment reference:

```text
/chatalyst tebex grant <player-or-uuid> <rank> <payment-id>
```

The duration comes from the rank's `Tebex_Duration`. The payment lands in the same ledger as a Tebex purchase, so it is repaired, renewed and refunded the same way.

## Selling items, commands and money

Goods live in `store.yml`. The store is off and the catalog is empty by default:

```yaml
Store:
  Enabled: false
  Console_Only: true
  Command: "store"
  Command_Aliases:
    - "tienda"
    - "claimitems"
  Delivery: AUTO
  Join_Delay_Ticks: 40
  Retry_Interval_Seconds: 60
  Required_Free_Slots: 2
  Notify_Discord: true

Store_Catalog: {}
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | Turns the item store on |
| `Console_Only` | `true` | Only the console (Tebex) may run the delivery command |
| `Command` / `Command_Aliases` | `store` / `tienda`, `claimitems` | The player command. Registered only while the store is on; skipped with a warning if another plugin owns it. `/chatalyst:store` always works |
| `Delivery` | `AUTO` | `AUTO` hands goods over as soon as the buyer is online with room. `MANUAL` waits for `/store claim` |
| `Join_Delay_Ticks` | `40` | Wait after joining before an automatic delivery |
| `Retry_Interval_Seconds` | `60` | How often pending deliveries are retried |
| `Required_Free_Slots` | `2` | Free inventory slots needed before items are handed over |
| `Notify_Discord` | `true` | Announces deliveries in the private staff Discord channel |

The Tebex package runs:

```text
chatalyst store give {id} <product_id> {transaction}
```

Each product in `Store_Catalog` has a `Type`: `ITEMS` (materials with amount, name, lore, enchantments, model data and glow), `COMMANDS` (console commands with `{player}` and `{uuid}`, for crate keys, pets or anything another plugin owns) or `MONEY` (a Vault deposit). One product can combine several; they arrive together or not at all. Example from the file:

```yaml
Store_Catalog:
  starter-kit:
    Name: "<gradient:#B56CFF:#E4A8FF><bold>Starter kit</bold></gradient>"
    Type: ITEMS
    Items:
      - Material: DIAMOND_SWORD
        Amount: 1
        Enchantments:
          sharpness: 3
          unbreaking: 2
      - Material: GOLDEN_APPLE
        Amount: 8
  crate-keys:
    Name: "<#E4A8FF>5 crate keys"
    Type: COMMANDS
    Commands:
      - "crates give {player} legendary 5"
  coin-pouch:
    Name: "<#FFD166>5,000 coins"
    Type: MONEY
    Amount: 5000
```

How a delivery behaves:

- **Buyer online with room**: handed over immediately.
- **Buyer offline**: the claim waits, survives restarts and is delivered when they join. Money can be deposited offline if your economy supports it.
- **Inventory full**: nothing is dropped on the ground. The claim stays pending and retries.
- **Delivered**: the claim is used up. A resent transaction or a second server on the same database cannot deliver it again.

Only product IDs listed in the catalog can be delivered, so a mistyped or hostile command cannot invent a product.

Players use `/store` to open a menu of what they are owed, `/store claim` to take everything, and `/store list` for a chat list. Permission: `chatalyst.command.store` (everyone).

## The storefront: /ranks

One in-game shop lists your ranks and, optionally, your goods.

```yaml
Tebex:
  Shop:
    Enabled: false
    Command: "ranks"
    Command_Aliases:
      - "rangos"
    Sign:
      Enabled: true
      Tag: "[ranks]"
    Title: "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Rank Store</bold></gradient>"
    Rank_Url_Template: ""
    Home_Url: ""
    Close_After_Select: true
    Show_Raw_Link: false
```

| Option | Default | What it does |
| --- | --- | --- |
| `Enabled` | `false` | Turns the storefront on |
| `Command` / `Command_Aliases` | `ranks` / `rangos` | The player command. Not `shop` on purpose, since `/shop` usually belongs to a shop plugin. `/chatalyst ranks` always works |
| `Sign.Enabled` / `Sign.Tag` | `true` / `[ranks]` | Write the tag on the first line of a sign to turn it into a clickable shop entrance. Needs `chatalyst.sign.shop` |
| `Title` | see above | Menu title |
| `Rank_Url_Template` | `""` | Optional URL pattern using `{rank_id}`, `{player}`, `{uuid}` when a rank has no `Purchase_Url` |
| `Home_Url` | `""` | Link to your store's home page |
| `Show_Raw_Link` | `false` | Also prints the raw URL in chat |

Clicking a card opens a preview; the buy button is inside it, so a misclick never opens a link. Minecraft always asks the player before opening the browser. Only valid HTTPS links are published.

A good from `store.yml` appears in the same storefront when it has a `Shop:` block with `Visible: true` and a real `Purchase_Url`. With `Preview_Kit` it can show the real contents of an existing kit from AdvancedKits, PlayerKits2 or PlayerKits.

### Sections

`Store_Sections` in `store.yml` groups the storefront into sections (ranks, kits…). It is off by default, which keeps one flat list:

```yaml
Store_Sections:
  Enabled: false
  Categories: {}
```

Each entry names its section in `Shop.Category`. A section with nothing visible is hidden, and an entry naming a section that does not exist goes to a final "Other" section instead of disappearing.

## Purchase celebration

When a rank is delivered, Chatalyst can celebrate it with a chat line, a toast, a title, an action bar, a sound and cosmetic fireworks. Edit it in game with `/chatalyst tebex gui` or in `integrations.yml`:

```yaml
Tebex:
  Presentation:
    Enabled: true
    Audience: PURCHASER
    Preset: SUBTLE
    Offline_Delivery: true
```

| Option | Default | What it does |
| --- | --- | --- |
| `Audience` | `PURCHASER` | `PURCHASER` shows it to the buyer only; `GLOBAL` to every player on the server |
| `Preset` | `SUBTLE` | `SUBTLE`, `FANCY`, `SILENT` or `CUSTOM`. A preset only switches effects on or off; your texts stay |
| `Offline_Delivery` | `true` | If the buyer was offline, shows it when they next join |

Each rank can use the global design or its own (`Presentation.Use_Global: false`). Placeholders: `{player}`, `{rank_id}`, `{rank}`, `{group}`, `{duration}`, `{transaction}`. Every text has an `_es` version, chosen per viewer. Fireworks are cosmetic and never deal damage.

## Commands and permissions

| Command | Permission | What it does |
| --- | --- | --- |
| `/ranks` | `chatalyst.command.ranks` (everyone) | Opens the storefront |
| `/store` | `chatalyst.command.store` (everyone) | Shows and collects your purchases |
| `/chatalyst tebex gui` | `chatalyst.admin.tebex.presentation` or `chatalyst.admin` | Edit and preview the purchase celebration |
| `/chatalyst tebex status` | console | How many purchases are on record |
| `/chatalyst tebex grant …` and `revoke …` | console | Deliver or remove a rank |
| `/chatalyst store give …` | console | Deliver a good |
| — | `chatalyst.sign.shop` (op) | Create a shop entrance sign |

With `Console_Only: false`, players with `chatalyst.admin.tebex` (ranks) or `chatalyst.admin.store` (goods) may also run the console-only commands. Keep `Console_Only: true` unless you have a reason.

## Limits

- Chatalyst does not process payments. Tebex does (it already accepts PayPal and cards); Chatalyst delivers what was paid for.
- `COMMANDS` goods need the buyer online, so they wait until the buyer joins.
- A rank's perks live in LuckPerms and your other plugins. The storefront cannot know them, so write them in `Shop.Preview` yourself.
