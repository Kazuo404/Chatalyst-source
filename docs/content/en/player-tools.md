---
title: Player tools
description: The everyday tools players get with Chatalyst. AFK, personal toggles, ignore, /me, chat colors, item sharing in chat, item renaming, colored signs and the player list.
---

Besides chat itself, Chatalyst gives players a set of small tools that make the server nicer to use: marking themselves as away, turning off what they do not want to see, ignoring someone, choosing a chat color, showing an item or their inventory in chat, and more. Most of them work for everyone out of the box; a few are meant as rank perks and need a permission.

Bigger player features have their own pages: [private messages](private-messages), [mail](mail), [nicknames & colors](nicknames), [books](books), [market](market) and [marriage](marriage).

## AFK

Players are marked as away after a while without moving, chatting or using commands. Only the player is told, privately; nothing is broadcast. `/afk` toggles it by hand.

```yaml
Afk:
  Enabled: true
  Timeout_Seconds: 300
  Tab_Suffix: " <#C0B3D9>[AFK]"
  Notify: true
  Ignore_Vanished: true
```

| Option | Default | What it does |
| --- | --- | --- |
| `Timeout_Seconds` | `300` | Idle time before a player is marked AFK |
| `Tab_Suffix` | `" <#C0B3D9>[AFK]"` | What `{afk_tag}` shows in the tab list |
| `Notify` | `true` | Tells the player when they go AFK and come back |
| `Ignore_Vanished` | `true` | Vanished staff are never marked AFK automatically (manual `/afk` still works) |

The status is available as `{afk_tag}` in formats and as `%chatalyst_afk%` for PlaceholderAPI and TAB.

## Personal toggles

`/toggle` opens a menu where each player turns features on or off for themselves. It also works directly: `/toggle <name>`.

| Toggle | Default | What it controls |
| --- | --- | --- |
| `pm` | on | Receiving private messages |
| `announcements` | on | Seeing automatic announcements |
| `mentions` | on | The sound when someone mentions you |
| `joinquit` | on | Seeing other players' join and leave messages |
| `translate` | off | [Live translation](translation) of chat into your language (only shown when the server has it set up) |

The choice is saved and survives reconnects.

## Ignoring players

`/ignore <player>` stops that player's private messages and mail from reaching you. Run it again to stop ignoring them. By default it also hides their public chat:

```yaml
Chat:
  Ignore:
    Hide_In_Chat: true
```

With `Hide_In_Chat: true`, an ignored player's messages collapse to a small "[ignored] name · [show]" marker. Clicking **[show]** reveals just that one message to you. Set it to `false` to make `/ignore` affect private messages only.

## /me

A third-person action in chat: `/me waves` shows as "* Steve waves". The look is in `chat.yml`:

```yaml
Me:
  Format: "<#C0B3D9>* <#FBF2FF>{player_name} <#C0B3D9>{message}"
```

Muted players cannot use `/me` (see [punishments](punishments#mutes)).

## Chat color

`/chatcolor` opens a menu to pick the color of your chat messages. The 16 Minecraft colors are always available; the extra colors and gradients in `players.yml` each need their own permission, so you can give them as rank perks.

```yaml
Chat_Color:
  Colors:
    blush: "<#FF6B9B>"
    coral: "<#FF7A59>"
    # ...
  Gradients:
    sunset: "<gradient:#FF512F:#F09819>"
    ocean: "<gradient:#2E3192:#1BFFFF>"
    # ...
```

| Permission | What it unlocks |
| --- | --- |
| `chatalyst.command.chatcolor` (everyone) | Open the menu and use the 16 basic colors |
| `chatalyst.color.<id>` / `chatalyst.color.*` | One extra color / all of them |
| `chatalyst.gradient.<id>` / `chatalyst.gradient.*` | One gradient / all of them |
| `chatalyst.chat.style.bold`, `.italic`, `.underlined`, `.strikethrough` (everyone) | Text styles |
| `chatalyst.chat.style.obfuscated` (op) | The scrambled "magic" style |
| `chatalyst.chat.color.others` (op) | Change another player's color: `/chatcolor <player> <color>` |

`/chatcolor reset` goes back to the default. The same colors are used for nickname colors (see [nicknames](nicknames)).

## Showing things in chat

Players can type these tokens in any chat message:

| Token | What appears | Permission |
| --- | --- | --- |
| `[item]` or `[i]` | The item in your hand, with its full tooltip on hover | `chatalyst.chat.item` (everyone) |
| `[inv]`, `[inventory]`, `[inventario]` | An **Open** button showing your inventory | `chatalyst.chat.shareinv` (everyone) |
| `[ec]`, `[ender]`, `[enderchest]` | An **Open** button showing your ender chest | `chatalyst.chat.shareinv` (everyone) |
| `:heart:`, `[creeper]`… | Emojis and small pixel-style labels | `chatalyst.chat.emoji`, `chatalyst.chat.pixel` (everyone) |

Shared inventories are live and read-only, anyone can open them for 5 minutes, and shulker boxes inside open with a click. The button never contains your real name, so sharing does not reveal an [anonymous](anonymous) player. These switches are in `chat.yml`:

```yaml
Chat:
  Item_Placeholder: true
  Item_Custom_Ids: true
  Inventory_Share: true
```

`Item_Custom_Ids` adds the ItemsAdder or Nexo id of a custom item to the `[item]` tag.

## Renaming items

`/itemname <name>` renames the item in your hand with colors (`&4&lDARK SWORD` or MiniMessage). It is a rank perk with a limited number of uses per player.

```yaml
Item_Name:
  Enabled: true
  Uses: 1
  Max_Length: 40
```

| Option | Default | What it does |
| --- | --- | --- |
| `Uses` | `1` | Renames each player gets. `0` or less = unlimited |
| `Max_Length` | `40` | Longest name, not counting color codes |

`/itemname reset` removes the custom name without using up a rename. Permissions: `chatalyst.command.itemname` (op) to use it, `chatalyst.bypass.itemname` (op) for unlimited uses. Named items appear in death messages.

## Colored signs

Players with the right rank can write colors, gradients and rainbow text on real signs.

```yaml
Fancy_Signs:
  Enabled: true
  Permission: "chatalyst.sign.colors"
  Styles:
    galaxy:
      Format: "<gradient:#6A00FF:#00D4FF:#FF4FD8><bold>{text}</bold></gradient>"
    sunset:
      Format: "<gradient:#FF512F:#F09819>{text}</gradient>"
```

One permission, `chatalyst.sign.colors`, unlocks every color, gradient, rainbow and named style. Players can write `&4&lImportant`, `<gradient:#ff0000:#0000ff>Hello</gradient>`, `<rainbow>Hello</rainbow>` or a named style such as `<galaxy>Hello`. Only visual tags are allowed; clicks, hovers and commands are always blocked. Style names use lowercase letters, numbers, `_` or `-`, and their `Format` must contain `{text}`.

## Player list

`/players` (also `/list`, `/jugadores`) opens a menu of everyone online. Clicking a head lets you message, ignore, mail or trade with that player. Staff see extra moderation buttons there (see [staff suite](staff-suite)).

With `Nick.Hide_Real_Names_In_List: true` in `players.yml`, the vanilla `/list` answer also shows nicknames instead of real account names.

## Welcome message

`/motd` shows the join message again; `/motd first` shows the first-join version. The lines are in `messages.yml` under `Motd` and, by default, in each player's language from the lang files.

## Commands and permissions

| Command | Permission | Default |
| --- | --- | --- |
| `/afk` | `chatalyst.command.afk` | everyone |
| `/toggle [name]` | `chatalyst.command.toggle` | everyone |
| `/ignore <player>` | `chatalyst.command.ignore` | everyone |
| `/me <action>` | `chatalyst.command.me` | everyone |
| `/chatcolor [color]`, `/chatcolor reset` | `chatalyst.command.chatcolor` | everyone |
| `/itemname <name>`, `/itemname reset` | `chatalyst.command.itemname` | op |
| `/players` | `chatalyst.command.players` | everyone |
| `/motd [first]` | `chatalyst.command.motd` | everyone |

## Limits

- AFK detection is per server; it does not follow a player across a network.
- Shared inventories can be opened for 5 minutes, then the button expires.
- Emojis are Unicode symbols drawn by the Minecraft font. Full-color emoji pictures need ItemsAdder.
