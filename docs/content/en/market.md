---
title: Market & trading
description: A built-in market with escrow, auctions and barter, face-to-face trades where nothing moves until both accept, and announcements for external shop plugins.
---

Chatalyst covers trading between players in three ways:

- **Market** (`/market`): players list items for sale or auction. The item goes into escrow at once, the listing is announced in the trade channel with clickable buy and bid buttons, and sales are negotiated in a window where the buyer can offer money, items or both.
- **Direct trade** (`/trade`): two players open a shared window, add items and money, and nothing moves until both confirm.
- **External shop announcements**: if you use AuctionHouse, QuickShop or a similar plugin, Chatalyst announces their listings in the trade and auction channels.

The market and direct trade both ship **disabled**. Money needs [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin.

## How to turn it on

In `plugins/Chatalyst/economy.yml`, set `Market.Enabled: true` and/or `Trade.Enabled: true`, then run `/cht reload`. Enable the `trade` and `auction` [channels](channels) too if you want the announcements to be visible (they are also turned on automatically when a supported market plugin is detected).

## Configuration

### Market

```yaml
Market:
  Enabled: false
  Defer_To_Plugins: true
  # Channels that receive the announcements (local only, never proxied).
  Sale_Channel: trade
  Auction_Channel: auction
  Max_Listings: 10       # active listings per player (chatalyst.market.bypasslimit ignores it)
  Listing_Days: 7        # sales expire after this many days (item returns to the seller)
  Max_Stash: 54

  # Barter: buyers may offer items (instead of, or on top of, money) in the negotiation.
  Allow_Item_Offers: true
  Auction:
    Default_Minutes: 60      # duration when the seller doesn't say one
    Max_Minutes: 1440        # hard cap for auction duration
    Min_Increment_Percent: 5 # each bid must beat the previous one by at least this %
```

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns the built-in market on. |
| `Defer_To_Plugins` | `true` | Steps aside when a dedicated market plugin is installed; Chatalyst then only announces that plugin's listings. |
| `Sale_Channel` / `Auction_Channel` | `trade` / `auction` | Channels that receive the announcements. |
| `Max_Listings` | `10` | Active listings per player. |
| `Listing_Days` | `7` | Days before a sale expires and the item returns to the seller. |
| `Max_Stash` | `54` | Item stacks that can wait in the claim stash. When full, new purchases and trades are refused until the player claims some. |
| `Allow_Item_Offers` | `true` | Buyers may offer items instead of, or on top of, money. |
| `Auction.Default_Minutes` | `60` | Auction length when the seller does not give one. |
| `Auction.Max_Minutes` | `1440` | Maximum auction length. |
| `Auction.Min_Increment_Percent` | `5` | Each bid must beat the previous one by at least this percentage. |
| `Highlight.Enabled` | `false` | Colors the product name of listings by sellers with a highlight permission. |
| `Sound.Enabled` | `true` | Plays a chime to players who receive market announcements. |

`Highlight.Ranks` pairs a permission with a color. The first permission the seller has wins:

```yaml
  Highlight:
    Enabled: false
    Ranks:
      - Permission: chatalyst.market.highlight.mvp
        Color: "<#C44FE0>"
      - Permission: chatalyst.market.highlight.vip
        Color: "<#D8B4FE>"
```

### How a sale works

1. The seller lists the held item with `/market sell <price>` or `/market auction <price> [minutes]`, or several items at once with `/market publish`, which opens a publishing window with exact price controls, a description and an optional scheduled launch.
2. The item leaves the seller's inventory and waits in escrow. The listing is announced with the item tooltip and buttons.
3. `/market buy <id>` opens a negotiation with the online seller. The buyer can offer money, items or both. Any change resets both confirmations; the deal completes only when both accept the same offer.
4. Auctions take bids with `/market bid <id> <amount>`. At the end the winner is charged and receives the item. If payment fails, the item returns to the seller.
5. Anything that does not fit in an inventory, or belongs to an offline player, waits in `/market claim`.

`/market` opens a browser with all listings, direct sales, auctions and your own listings, sortable by newest, price or ending time.

### Direct trade

```yaml
Trade:
  Enabled: false
  Allow_Money: true          # let each side add money (needs Vault); false = items only
  Request_Timeout_Seconds: 60
```

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns `/trade` on. |
| `Allow_Money` | `true` | Each side can add money. `false` = items only (no Vault needed). |
| `Request_Timeout_Seconds` | `60` | How long a trade request stays open. |

Both players must be online on the same server. Any change clears both confirmations. If someone disconnects or cancels, every item goes back to its owner. Delivery goes through the claim stash, so a full inventory never drops items on the ground.

### External shop plugins

```yaml
Trade_Plugins:
  Announce:
    Enabled: true
    Cooldown_Seconds: 60
    # "<command without slash>": <channel that receives the announcement>
    Commands:
      ah sell: auction
      auc sell: auction
      auction sell: auction
      auctionhouse sell: auction
      crazyauctions sell: auction
      qs create: trade
      quickshop create: trade
```

When a player runs one of these commands, Chatalyst announces it in the given channel, in each reader's language, with a per-player cooldown. Detected plugins include AuctionHouse, zAuctionHouse, CrazyAuctions, PlayerAuctions, AuctionMaster, QuickShop, ChestShop, ShopGUI+, EconomyShopGUI, DynamicShop and BossShopPro.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/market` (`/mercado`, `/mkt`) | `chatalyst.command.market` (true) | Opens the market browser. |
| `/market publish` | `chatalyst.command.market` | Opens the publishing window. |
| `/market sell <price>` | `chatalyst.command.market` | Sells the held item. |
| `/market auction <price> [minutes]` | `chatalyst.command.market` | Auctions the held item. |
| `/market buy <id>` / `/market bid <id> <amount>` | `chatalyst.command.market` | Buys or bids. |
| `/market cancel <id>` | `chatalyst.command.market` | Cancels your listing and returns the item. |
| `/market claim` | `chatalyst.command.market` | Collects items waiting for you. |
| `/market history` | `chatalyst.command.market` | Shows recent market activity. |
| `/market credits list\|retry <id>\|paid <id>` | `chatalyst.market.reconcile` (op) | Reconciles queued refunds. |
| `/market settlements list\|paid <id>\|unpaid <id>` | `chatalyst.market.reconcile` (op) | Resolves a payment interrupted by a crash. Check your economy's records first. |
| `/trade <player>` (`/intercambio`) | `chatalyst.command.trade` (true) | Requests a trade. |
| `/trade accept [player]` / `/trade deny` | `chatalyst.command.trade` | Answers a request. |
| `/trade settlements list\|paid <id>\|unpaid <id>` | `chatalyst.market.reconcile` (op) | Resolves a money trade interrupted by a crash. |

| Permission | Default | What it allows |
|---|---|---|
| `chatalyst.market.bypasslimit` | op | No limit on active listings. |
| `chatalyst.market.manage` | op | Cancel anyone's listing. |
| `chatalyst.market.reconcile` | op | Use the reconciliation tools (credits, settlements): they move money by hand, so keep them for owners. |
| `chatalyst.market.highlight.vip` / `.mvp` | false | Highlighted product names. |

## Limits

- The market is per server on purpose, even with a shared database: listings, stashes, history and announcements never cross the proxy, so one item cannot be bought from two servers at once.
- Buying a direct sale needs the seller to be online, because both sides accept the deal.
- External shop announcements fire when the command runs. A listing the other plugin rejects may still be announced; raise `Cooldown_Seconds` to keep the channel calm.
