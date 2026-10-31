---
title: Gifts
description: Gift boxes that players open with a right click for one weighted random prize, with honest odds printed on the item and export into other plugins' kits.
---

A gift box is a decorated head that players open with a right click to get one random prize. Each box type (copper, iron, gold, emerald, diamond) has its own prize table. Boxes can come from anywhere: a command, a kit, a giveaway, a store purchase.

The item tells the truth: its lore lists every possible prize with its real percentage, calculated by the plugin.

## How to turn it on

Gift boxes are on by default, with five box types ready to use. Settings live in `plugins/Chatalyst/gifts.yml`:

```yaml
Gifts:
  Enabled: true
  Command: "caja"
  Command_Aliases:
    - "cajas"
    - "giftbox"

  # Escribir en el lore lo que puede caer, con su probabilidad real.
  Show_Contents: true

  Max_Listed: 8

  Announce: true

  # Sonido al abrir. Vacio = en silencio.
  Sound: ENTITY_PLAYER_LEVELUP
```

Hand out a box with `/caja dar <player> <type> [amount]`.

## Configuration

### General

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns gift boxes on. |
| `Command` | `caja` | The command name. |
| `Command_Aliases` | `cajas`, `giftbox` | Extra names. A name another plugin already owns is skipped. |
| `Show_Contents` | `true` | Lists the possible prizes and their odds in the lore. `false` keeps it a surprise. |
| `Max_Listed` | `8` | Prizes listed before summarizing as "and N more". |
| `Announce` | `true` | Tells the player in chat what they got. |
| `Sound` | `ENTITY_PLAYER_LEVELUP` | Sound when a box is opened. Empty = silent. |

### Box types

Each type lives under `Gift_Tiers`. This is the shipped copper box:

```yaml
Gift_Tiers:

  cobre:
    Name: "Copper Gift"
    Name_es: "Regalo de Cobre"
    Order: 1
    Color: "#CD7F32"
    Glow: true
    Texture: "74300a5f8111753fb9e224d60e46d46fbb03cf3f0974b3dee43887ea5ff01fcb"
    Description:
      - "<gray>The first rung. Supplies, not treasure."
    Description_es:
      - "<gray>El primer escalon. Suministros, no tesoro."
    Rewards:
      - { Weight: 25, Material: COOKED_BEEF, Amount: 8-16, Label: "Cooked beef", Label_es: "Filetes" }
      - { Weight: 25, Material: COAL, Amount: 8-24, Label: "Coal", Label_es: "Carbon" }
      - { Weight: 18, Material: IRON_INGOT, Amount: 2-5, Label: "Iron", Label_es: "Hierro" }
      - { Weight: 15, Material: COPPER_INGOT, Amount: 8-16, Label: "Copper", Label_es: "Cobre" }
      - { Weight: 10, Material: EXPERIENCE_BOTTLE, Amount: 4-8, Label: "Bottles o' Enchanting", Label_es: "Botellas de experiencia" }
      - { Weight: 7, Material: GOLD_INGOT, Amount: 1-3, Label: "Gold", Label_es: "Oro" }
```

| Field | What it does |
|---|---|
| `Name` / `Name_es` | Box name per language. |
| `Order` | Position in the list. |
| `Color` | Color of the name and lore headings. |
| `Glow` | Enchanted glint. |
| `Texture` | Head texture: the full base64 value or just the `textures.minecraft.net` hash. |
| `Description` / `Description_es` | Lines in the lore. |
| `Rewards` | The prize table. |

Each reward can have `Weight`, `Material`, `Amount` (a number or a range like `8-16`), `Label` / `Label_es` (how it is named in the lore), and optionally `Name`, `Lore`, `Enchantments` and `Glow` for a special item.

### Weights are not percentages

`Weight: 25` does not mean 25%. Each prize is drawn against the sum of the weights of its box, so you can add a prize without recalculating the others. The real percentage is calculated for you and printed on the item. `Weight: 0` keeps a prize written but never drawn, handy for a seasonal prize.

The prize is rolled when the box is opened, not when it is made. Editing a table therefore also changes what boxes already sitting in players' chests will give.

### Putting boxes inside other plugins

A box is a head with a profile and an internal mark, which you cannot write by hand into another plugin's config. `/caja exportar <type> [amount]` saves it, serialized by Bukkit, to `plugins/Chatalyst/exports/gift-<type>.yml`, ready to paste into a kit (for example AdvancedKits) so it also shows in that plugin's preview.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/caja` (`/cajas`, `/giftbox`) | `chatalyst.command.gift` (true) | Lists the box types and how many prizes each has. |
| `/caja dar <player> <type> [amount]` | `chatalyst.command.gift.admin` (op) | Gives boxes to an online player (1 to 64). |
| `/caja exportar <type> [amount]` | `chatalyst.command.gift.admin` (op) | Exports a box to a file. |

The English subcommands `give` and `export` work too.

## Limits

- Prizes are items only. For commands, ranks or money use [Giveaways](giveaways) or your store.
- A box is made in the language of the player who receives it, since the text travels inside the item.
- If the player's inventory is full, the box drops at their feet.
