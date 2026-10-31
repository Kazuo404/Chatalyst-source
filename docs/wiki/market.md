# Market and Direct Trade

Chatalyst includes two optional economy features. Both ship disabled:

- `Market.Enabled` controls the persistent sale and auction market.
- `Trade.Enabled` controls direct exchanges between two online players.

The `trade` and `auction` chat channels have independent `Enabled` switches and both ship disabled. With `Auto_Enable_With_Market_Plugins: true`, a detected external auction plugin exposes `auction`, while a detected shop plugin exposes `trade`. This runtime activation enables its announcements and router destination without enabling Chatalyst's native market. Set the auto option to `false` to opt out, or `Enabled: true` to keep the channel permanent.

A channel that is neither manually nor automatically enabled is removed from `/channel`, aliases, tab-completion, routing and the GUI. Remaining buttons are centered automatically. Detection currently covers AuctionHouse, zAuctionHouse (v4), zAuctionHouseV3, CrazyAuctions, PlayerAuctions, AuctionMaster, QuickShop, QuickShop-Hikari, ChestShop, ShopGUIPlus, EconomyShopGUI, DynamicShop and BossShopPro.

Vault and an economy provider are required for money operations. Item-only direct trades can remain available with `Trade.Allow_Money: false`.

## Built-in Market

`/market` opens a 36-listing browser with views for all listings, direct sales, auctions and the player's own catalogue. Listings can be ordered by newest, lowest price, highest price or ending time. The header summarizes active listings and pending claims; the footer provides publishing, history, claim and page controls.

`/market publish` opens a chest-style publishing vault where a player can add several items. Clicking an item opens its listing atelier with precise `±1/10/100/1000` valuation controls, exact chat input and an independent description. The collection then chooses sale or auction, duration and an optional scheduled launch. `/market sell <price>` and `/market auction <price> [minutes]` remain the quick path for the held item.

The item leaves the player's inventory and enters escrow before the listing is announced. `Market.Max_Listings` limits active listings unless the seller has `chatalyst.market.bypasslimit`.

### Sales

`/market buy <id>` starts a contract with the online seller. The buyer can offer money, items or both when `Market.Allow_Item_Offers` is enabled. Both identities, contributions and acceptance states remain visible. Any edit resets both confirmations and starts a short review window; delivery happens only after both sides accept the same stable offer.

### Auctions

`/market bid <id> <amount>` places a bid subject to the configured minimum increment. At closing, the winner is charged and receives the item. If payment cannot complete, the item safely returns to the seller.

### Recovery and Administration

- `/market cancel <id>` returns escrow; staff with `chatalyst.market.manage` can cancel another seller's listing.
- `/market claim` recovers items that could not fit in an inventory or belonged to an offline player.
- `/market history` shows recent sale, auction, cancellation and expiry activity.
- `/market credits list|retry <id>|paid <id>` reconciles queued Vault refunds.
- `/market settlements list|paid <id>|unpaid <id>` resolves a payment left in the ambiguous
  `PROCESSING` state after a crash. Check the economy provider's ledger before choosing.
- Rank-highlight permissions can color listing product names according to `Market.Highlight.Ranks`.

Market data is intentionally isolated per backend, including listings, claim stashes, history and announcements. It remains local even with shared MySQL so one item cannot be bought independently from two servers. With `Market.Defer_To_Plugins: true`, the built-in market steps aside when a supported dedicated marketplace plugin is detected.

## Direct Trade

`/trade <player>` sends a request. The recipient uses `/trade accept [player]` or `/trade deny`. Both players must be online on the same backend. Each side has an editable contribution, a read-only counterparty column and a precise treasury screen. Any item or money change clears both confirmations and starts a review window; the exchange completes only after both confirm the same stable contract.

Disconnecting or cancelling returns deposited items to their owners. Delivery first enters the durable claim stash, so a full inventory never causes a world drop. Staff with `chatalyst.market.manage` can inspect and reconcile crash-interrupted money trades through `/trade settlements list|paid <id>|unpaid <id>`.
