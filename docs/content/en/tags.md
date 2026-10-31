---
title: Tags, titles & effects
description: A catalog of unlockable titles with requirements, achievements, seasons and rewards, personal prefixes, and animated rank tags for tab and scoreboards.
---

Titles give players something to earn. You build a catalog of titles (for example `[Hunter]` for killing 100 mobs), each with requirements, rarity, points and rewards. Players browse it with `/tag`, see their progress, and equip any title they have unlocked. The equipped title shows next to their name in chat.

This page also covers the older personal prefix/suffix (`/tag prefix`) and animated rank tags (`tagfx.yml`) for the tab list and scoreboards.

## How to turn it on

The catalog is always available. Two things decide who can use it:

- `chatalyst.tag` (op by default) lets a player open `/tag` and equip titles. Grant it to everyone who should collect titles.
- `chatalyst.tag.admin` (op) opens the editor with `/tag admin`.

Titles appear in chat through the `{tag_prefix}` and `{tag_suffix}` placeholders, which the shipped `formats/chat.yml` already includes.

## Configuration

### The catalog

Titles live in `plugins/Chatalyst/tags.yml`. You can edit everything in game with `/tag admin` (or `/cht gui` → Content → Titles), so you rarely need to touch the file. This is the example title that ships with the plugin:

```yaml
Settings:
  Notify_Unlock: true
  Auto_Equip_First: false

Titles:
  hunter:
    Display_Name: Cazador
    Text: <gradient:#FFD166:#FF4D6D><bold>[Cazador]</bold></gradient>
    Description: Demuestra tu experiencia derrotando criaturas.
    Material: IRON_SWORD
    Position: LEFT
    Spacing: 1
    Enabled: true
    Hidden_Until_Unlocked: false
    Category: Cacería
    Rarity: RARE
    Order: 10
    Points: 25
    Secret: false
    Season:
      Id: ''
      Starts_At: ''
      Ends_At: ''
    Requirements:
      Mode: ALL
      Rules:
      - Type: MOB_KILLS
        Target: ANY
        Amount: 100
    Rewards:
      Commands: []
```

| Setting | Default | What it does |
|---|---|---|
| `Settings.Notify_Unlock` | `true` | Shows the unlock notice (toast, chat and sound). Master switch for all of them. |
| `Settings.Auto_Equip_First` | `false` | Equips a player's first unlocked title automatically. |

| Title field | What it does |
|---|---|
| `Display_Name` / `Text` | Name in menus and the MiniMessage text shown in chat. |
| `Position` | `LEFT` (before the name) or `RIGHT` (after it). |
| `Spacing` | `0` to `8` spaces between the title and the name. Default `1`. |
| `Category`, `Rarity`, `Order` | How the title is grouped and sorted. Each category becomes a collection. |
| `Points` | Achievement points added to the player's profile. |
| `Secret` | Shows `???` until the title is earned. |
| `Hidden_Until_Unlocked` | Removes the title from the catalog until it is earned. |
| `Season` | A season id plus optional start and end. Changing the id starts a new season. |
| `Requirements` | Up to 8 rules, combined with `Mode: ALL` or `ANY`. |
| `Rewards.Commands` | Up to 8 console commands run when the title is unlocked automatically. |
| `Achievement` | Toast, chat and sound shown on unlock. |

### Requirement types

| Type | Meaning |
|---|---|
| `NONE` | Available to everyone. |
| `MOB_KILLS` | Kill `Amount` mobs. `Target` is `ANY` or an entity type. |
| `PLAYER_KILLS` | Kill `Amount` players. |
| `PLAYTIME_MINUTES` | Play `Amount` minutes. |
| `BLOCKS_MINED` | Mine `Amount` blocks of the material in `Target`. |
| `ITEMS_CRAFTED` | Craft `Amount` items of the material in `Target`. |
| `ITEMS_PICKED_UP` | Pick up `Amount` items of the material in `Target`. |
| `ITEMS_OWNED` | Have `Amount` of the material in the inventory right now. |
| `FISH_CAUGHT` | Catch `Amount` fish. |
| `ANIMALS_BRED` | Breed `Amount` animals. |
| `ADVANCEMENT` | Complete the advancement in `Target` (`namespace:path`). |
| `PERMISSION` | Own the permission in `Target`. |
| `TITLE_UNLOCKED` | Unlock another title first. Cycles are rejected. |
| `MANUAL` | Only an administrator can grant it. |

Progress is checked when the related statistic changes and in a safety sweep about once a minute. Once earned, a title stays earned even if you change its requirements later.

Reward commands accept `{player}`, `{uuid}`, `{title_id}`, `{title_name}`, `{points}` and `{season}`. Titles granted by hand from the editor do not run their rewards, so a grant and revoke loop cannot farm them.

### Collections and ranking

Every category becomes a collection automatically. From the editor you can give a collection its own name, description, icon and bonus points for completing it. Players see collections and a global ranking by points from their profile in `/tag`.

### Personal prefix and suffix

The older personal tag still works. `/tag prefix <text>` and `/tag suffix <text>` set free text shown through `{tag_prefix}` and `{tag_suffix}`. An equipped catalog title takes priority; the personal tag comes back when the title is unequipped. The maximum length is set in `players.yml`:

```yaml
Tag:
  Max_Length: 16
```

### Animated rank tags

`tagfx.yml` animates rank tags made of ItemsAdder font glyphs. Each effect lists its frames either as real font characters (`Chars`, one per frame) or with the `Glyph` + `Frames` shorthand. The shipped file has a 12-frame `quasar` effect written with `Chars` and this commented shorthand example:

```yaml
  #owner:
  #  Glyph: 'tag_owner'
  #  Frames: 12
  #  Interval_Ms: 70
```

| Placeholder | Value |
|---|---|
| `%chatalyst_fx_<name>%` | The current frame of that effect. |
| `%chatalyst_fx_auto%` | The current frame for the viewer's own rank (effect named like the LuckPerms group). Falls back to the normal prefix. |

`Interval_Ms` is milliseconds per frame (minimum 20). Use `Chars` with the real font characters: they work everywhere. The `Glyph` + `Frames` shorthand only renders where ItemsAdder filters text (chat and its menus). Every player sees the same frame at the same time.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/tag` | `chatalyst.tag` (op) | Opens the title catalog. |
| `/tag <id>` | `chatalyst.tag` (op) | Equips an unlocked title. |
| `/tag off` | `chatalyst.tag` (op) | Unequips the title. Unlocks are kept. |
| `/tag admin` | `chatalyst.tag.admin` (op) | Opens the catalog editor. |
| `/tag prefix\|suffix <text\|off>` | `chatalyst.tag` (op) | Sets your personal prefix or suffix. |
| `/tag prefix\|suffix <player> <text\|off>` | `chatalyst.tag.others` (op) | Changes another player's personal tag. |

`chatalyst.tag.color` (op) allows MiniMessage colors in the personal prefix and suffix. Title placeholders for scoreboards (`%chatalyst_title_name%`, `%chatalyst_achievement_points%`…) are listed in [Placeholders](placeholders).

## Limits

- Animated tags do not animate in chat: a chat line is drawn once. They animate in the tab list, above the head, scoreboards, boss bars and menus.
- ItemsAdder can reassign font characters when you add content. If tags turn into empty boxes, check `plugins/ItemsAdder/storage/font_images_unicode_cache.yml`.
- The catalog holds up to 500 titles, each with up to 8 rules and 8 reward commands.
- Reward commands are not retried if the server stops between the unlock and the command.
- During [anonymous mode](anonymous), `{tag_prefix}` and `{tag_suffix}` are blank.
