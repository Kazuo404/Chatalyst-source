---
title: Rentals
description: Let players rent ranks and perks such as /fly with in-game money for a limited time, granted through LuckPerms and refunded automatically if anything fails.
---

Rentals give players a way to earn perks by playing. Instead of a rank or `/fly` being something only money from your store can buy, players rent it for a day or a week with in-game money. Paying customers keep the convenience; players who grind get a path of their own.

Every rental is granted through LuckPerms with an expiry. There are no permanent rentals: a rental that never expires would be a sale.

## How to turn it on

Rentals need [Vault](https://www.spigotmc.org/resources/vault.34315/) with an economy plugin, and [LuckPerms](https://luckperms.net/) for ranks and permissions.

1. Write at least one offer under `Rental_Offers` in `plugins/Chatalyst/rentals.yml` (see below).
2. Set `Rentals.Enabled: true` and run `/cht reload`.
3. Grant `chatalyst.command.rental` to the groups that may rent.

> [!WARNING]
> `chatalyst.command.rental` is not declared with a default in `plugin.yml`, so only operators have it until you grant it. Give it to your default group with LuckPerms, for example `/lp group default permission set chatalyst.command.rental true`.

```yaml
Rentals:
  Enabled: false
  Command: "alquilar"
  Command_Aliases:
    - "rent"
    - "alquiler"
  Flight_Permissions:
    - "essentials.fly"
```

## Configuration

### General

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `false` | Turns the rental menu and command on. It ships off because there are no offers until you write them. |
| `Command` | `alquilar` | The command name. |
| `Command_Aliases` | `rent`, `alquiler` | Extra names. A name another plugin already owns is skipped. |
| `Flight_Permissions` | `essentials.fly` | Nodes that give flight. When such a rental expires, flight is turned off on the spot, with a warning a minute before and a soft landing. |

The command is registered while the server runs, not in `plugin.yml`, so a server that does not use rentals keeps those names free.

### Categories

```yaml
Rental_Categories:
  rangos:
    Name: "Ranks"
    Name_es: "Rangos"
    Icon: PAPER
    Order: 1
  cosmeticos:
    Name: "Cosmetics"
    Name_es: "Cosméticos"
    Icon: ENDER_EYE
    Order: 2
  comandos:
    Name: "Commands"
    Name_es: "Comandos"
    Icon: FEATHER
    Order: 3
```

An empty category is not shown. An offer that points to a category that does not exist appears under "Uncategorised".

### Offers

`Rental_Offers` is empty by default. The file includes this commented example:

```yaml
Rental_Offers: {}
#  vuelo-1d:
#    Name: "<#7BFFB0><bold>Flight for a day</bold>"
#    Name_es: "<#7BFFB0><bold>Vuelo por un dia</bold>"
#    Icon: FEATHER
#    Glow: true
#    Description:
#      - "<#C0B3D9>Use /fly wherever you are allowed to"
#    Description_es:
#      - "<#C0B3D9>Usa /fly donde tengas permitido"
#    Permissions:
#      - essentials.fly
#    Duration: 1d
#    Price: 5000
```

| Field | What it does |
|---|---|
| `Name` / `Name_es` | Name in the menu, per language. |
| `Icon`, `Glow` | Menu item and enchanted glint. |
| `Description` / `Description_es` | Lines under the offer. |
| `Category` | The category id it belongs to. |
| `Permissions` | LuckPerms nodes granted for the duration. |
| `Rank` | A LuckPerms group, to rent a whole rank. |
| `Duration` | Required and never permanent: `12h`, `1d`, `7d`, `1w`. |
| `Price` | Price in in-game money, through Vault. |

An offer with no duration, no price or nothing to grant is not shown.

### Selling rentals from another shop

Other shops can sell rentals without knowing anything about Chatalyst:

- **By command.** A shop, sign or NPC runs `alquilar <offer id>` as the player. Charging, expiry and refund still happen in Chatalyst.
- **By voucher.** Some shops can only sell items. Sell a paper whose lore contains a line `vale: <offer id>`. Right-clicking it redeems the rental without charging again. Vouchers keep working even if the rental menu is disabled, and players cannot forge them because lore cannot be written in survival.

### Safety

The player is charged first and the rank or permission is granted after. If LuckPerms cannot grant it (for example the group was deleted), the money is refunded and the player and console are told.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/alquilar` (`/rent`, `/alquiler`) | `chatalyst.command.rental` (op until granted) | Opens the rental menu. |
| `/alquilar <offer id>` | `chatalyst.command.rental` | Rents an offer directly. |

## Limits

- A rental is a temporary LuckPerms node. Chatalyst does not add time to a rental that is still running: renting the same offer again while it is active may charge without extending it. Check the player's nodes in LuckPerms to see what they have.
- Without Vault and an economy plugin nothing can be rented, including vouchers.
- The same offer ids are used in commands, vouchers and the menu. Keep them short, lowercase and permanent.
