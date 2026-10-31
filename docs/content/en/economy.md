---
title: Balance top
description: A menu ranking of the richest players, calculated in the background from Vault so opening it is instant, with real skins and current nicknames.
---

`/baltop` opens a ranking of the richest players on your server. It is a menu, not a wall of chat lines: each player appears with their head, current nickname, balance and position, with medals for the podium.

The ranking is calculated in the background and cached, so opening it is instant even with thousands of players who have ever joined.

## How to turn it on

Balance top needs [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin (for example EssentialsX). It is on by default in `plugins/Chatalyst/economy.yml`:

```yaml
Balance_Top:
  Enabled: true
  Size: 45
  Update_Interval: 300
```

If no compatible economy is found, the module stays inactive.

## Configuration

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns `/baltop` on. |
| `Size` | `45` | How many players are ranked. Maximum 54. |
| `Update_Interval` | `300` | Seconds between background recalculations. |

Opening `/baltop` also asks for a fresh calculation, so a recent change in someone's balance shows up as soon as it finishes, without waiting for the next cycle. Repeated requests close together share one calculation.

The balance line and rank line of each entry come from the language files (`baltop.gui-balance` and `baltop.gui-rank`), so you can restyle them without touching the plugin. See [Languages & messages](localization).

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/baltop` (`/moneytop`, `/balancetop`, `/richest`) | `chatalyst.command.baltop` (true) | Opens the ranking. |

## Limits

- The ranking reads balances through Vault. Players whose balance the economy plugin cannot look up while offline are skipped.
- It shows the ranking of this server's economy. On a network, each backend ranks its own economy unless your economy plugin shares balances.
- Balance top is only a ranking. Chatalyst does not provide an economy of its own; for trading see [Market & trading](market).
