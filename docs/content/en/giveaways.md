---
title: Giveaways
description: A live draw the whole server watches, with a warm-up, a spinning roulette of player heads, immunity while it spins and prizes from items to ranks and money.
---

A giveaway in Chatalyst is a live event. Staff start a draw, everyone gets a countdown, and then a roulette of player heads opens for every eligible player at the same moment. The wheel spins, slows down with rising ticks, and stops on the winner, who gets a title, a broadcast, a firework and the prize.

Staff never win by default, and nobody can be hurt while the wheel is on their screen.

## How to turn it on

Giveaways are on by default, with two example prizes. Settings live in `plugins/Chatalyst/giveaway.yml`:

```yaml
Giveaway:
  Enabled: true
  Command: "sorteo"
  Command_Aliases:
    - "giveaway"
    - "rifa"
    - "raffle"
  Warmup_Seconds: 20
  Countdown_Marks: [30, 20, 10, 5, 4, 3, 2, 1]
  Ready_Seconds: 3
  Spin_Seconds: 9
  Winner_Seconds: 6
  Reopen_If_Closed: true
```

Staff open `/sorteo`, pick a category, then a prize, then start. Or they type `/sorteo start <prize id> [seconds] [amount]`.

## Configuration

### How a draw runs

1. **Warm-up.** Everyone is told a draw is about to start, with a chat line, a title and a draining boss bar, and a countdown in the last seconds.
2. **Start.** The roulette opens for every eligible player at once and immunity switches on.
3. **Spin.** Heads scroll past and slow down; the tick sound rises in pitch.
4. **Winner.** Chosen at the very last step, at random among the players still online. Then the prize is handed over and immunity fades a few seconds later.

| Option | Default | What it does |
|---|---|---|
| `Enabled` | `true` | Turns giveaways on. |
| `Command` / `Command_Aliases` | `sorteo` / `giveaway`, `rifa`, `raffle` | Command names. A name another plugin owns is skipped; `/chatalyst:sorteo` always works. |
| `Warmup_Seconds` | `20` | Time between the announcement and the wheel opening. `0` starts at once. |
| `Countdown_Marks` | `30, 20, 10, 5, 4, 3, 2, 1` | Seconds announced during the warm-up. |
| `Ready_Seconds` | `3` | Pause after the menu opens, so everyone can read the prize. |
| `Spin_Seconds` | `9` | How long the wheel spins. |
| `Winner_Seconds` | `6` | How long the winner stays on screen. |
| `Reopen_If_Closed` | `true` | Reopens the roulette for anyone who closes it during the spin. |
| `Immunity.Enabled` | `true` | No damage, hunger or burning during the draw. |
| `Immunity.Extra_Seconds` | `5` | Extra protection after the winner is announced. |
| `Immunity.Cancel_Void` | `true` | Also cancels void damage. Set `false` if your spawn is over the void. |
| `Firework` | `true` | A harmless firework at the winner's feet. |
| `Amount_Presets` | `1000` … `100000` | Up to 6 money shortcuts in the menu. |
| `Notify_Discord` | `true` | Posts the result in the staff Discord channel (needs the [Discord bot](discord-bot) staff channel). |

`Sounds` sets the sound of each step (start, countdown, wheel ticks, final ticks, winner) and the pitch range of the wheel.

### Who takes part

| Option | Default | What it does |
|---|---|---|
| `Participants.Exempt_Permission` | `chatalyst.giveaway.exempt` | Players with this permission watch but are never drawn. |
| `Participants.Exclude_Staff` | `true` | Staff are never drawn. |
| `Participants.Staff_Permissions` | Chatalyst, vanilla, EssentialsX and LiteBans moderation nodes | Any player with one of these counts as staff. |
| `Participants.Staff_Groups` | `owner`, `admin`, `mod`, `helper`… | Primary LuckPerms groups that count as staff. |
| `Participants.Exclude_Vanished` | `true` | Vanished players are not entered. |
| `Participants.Include_Afk` | `false` | AFK players are not entered. |
| `Participants.Excluded_Worlds` | `[]` | Worlds whose players neither take part nor see the menu. |

Operators always count as staff. The staff permissions catch a staff rank whatever it is called, even in another language.

### Prizes

Each prize lives under `Giveaway_Prizes` and has a `Type`:

| Type | What it gives |
|---|---|
| `ITEMS` | Items written in the file (`Material`, `Amount`, `Name`, `Lore`, `Enchantments`, `Model_Data`, `Glow`). |
| `COMMANDS` | Console commands. `{player}` and `{uuid}` are the winner. |
| `MONEY` | Money through Vault. The amount is typed when the draw starts, as a figure (`50000`) or a band (`15000-35000`) rolled each time. |
| `STORE` | A product you already sell, by its `Store_Catalog` id in `store.yml`. See [Store, ranks & Tebex](store). |
| `RANK` | A rank. With Tebex enabled for that rank it goes through the same records as a purchase; otherwise it is written to LuckPerms directly. |
| `PERMISSION` | LuckPerms permission nodes under `Permissions`, for a perk that is not a whole rank. |

Any prize can also carry extra `Commands` and `Permissions`. `Duration` (`7d`, `30d`, `12h` or `permanent`) sets how long the rank and permissions last. This is one of the shipped prizes:

```yaml
Giveaway_Prizes:
  diamantes:
    Name: "<gradient:#54A6FF:#B6F7D4><bold>Diamond stash</bold></gradient>"
    Name_es: "<gradient:#54A6FF:#B6F7D4><bold>Alijo de diamantes</bold></gradient>"
    Type: ITEMS
    Icon: DIAMOND
    Glow: true
    Description:
      - "<#C0B3D9>32 diamonds and a netherite ingot"
    Description_es:
      - "<#C0B3D9>32 diamantes y un lingote de netherita"
    Items:
      - Material: DIAMOND
        Amount: 32
      - Material: NETHERITE_INGOT
        Amount: 1
```

The prize id (`diamantes`) is what you type in `/sorteo start <id>`. Keep it short, lowercase and permanent.

A prize's permission list can also be edited in game: `/sorteo`, right-click the prize, add with the book and shift-click to remove. Saving writes `giveaway.yml` and keeps its comments.

### Ranks as prizes

With `Ranks.Auto_Detect: true`, groups named in `Ranks.Names` (`vip`, `mvp++`, `legend`…) that exist in LuckPerms are offered as prizes automatically. Groups in `Ranks.Never` and any group that grants a staff permission are never offered. `Ranks.Duration` (default `permanent`) is how long a directly granted rank lasts.

### Menu layout

`Giveaway_Categories` groups prizes in the menu (kits, ranks, money, perks, items). `Giveaway_Menu` sets the menu height, the card grid and the button slots. Cards are centered automatically.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/sorteo` (`/giveaway`, `/rifa`, `/raffle`) | `chatalyst.command.giveaway` (true) | Players see the status or reopen a running wheel. Staff open the menu. |
| `/sorteo start <id> [seconds] [amount]` | `chatalyst.command.giveaway.admin` (op) | Starts a draw. The amount is for money prizes. |
| `/sorteo cancel` | `chatalyst.command.giveaway.admin` | Cancels the running draw. |
| `/sorteo list` | `chatalyst.command.giveaway.admin` | Lists prizes and any problem with them. |
| `/sorteo give <player> <id>` | `chatalyst.command.giveaway.admin` | Hands a prize over by hand. |
| `/sorteo status` | `chatalyst.command.giveaway.admin` | Shows the running draw or the last winner. |

`chatalyst.giveaway.exempt` (false) keeps a player out of the draw.

## Limits

- A winner who disconnects in the final second is paid when they return. If the server restarts first, the console tells staff to finish it with `/sorteo give`.
- Items that do not fit in the winner's inventory drop at their feet; the winner is standing still and immune when they land.
- `RANK` and `PERMISSION` prizes need LuckPerms. `MONEY` needs Vault.
