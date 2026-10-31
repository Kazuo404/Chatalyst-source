---
title: Nicknames & colors
description: Nicknames that become the player's identity everywhere, with anti-impersonation, plus nick and chat colors, gradients and styles from a menu.
---

A nickname in Chatalyst is more than decoration. It becomes the name other players see in chat, the tab list, above the head, in the mailbox and in menus, and Chatalyst's own commands accept it as if it were the real name.

Nicknames pass the same defenses as chat: invisible characters are removed, the rules and swear filter apply, and nobody can take another player's name. Players then color their nickname and their chat from two menus.

## How to turn it on

Nicknames and colors are always available. Access is controlled by permissions: `/nick` needs `chatalyst.nick` and colors need `chatalyst.nick.color`, both op by default. Grant them to the ranks that should have them. `/chatcolor` is open to everyone.

The settings live in `plugins/Chatalyst/players.yml`:

```yaml
Nick:
  Max_Length: 16
  Set_Display_Name: true
  Sync_Essentials: true
  Tab_Complete_Nicks: true
  Tab_Complete_Bypass: chatalyst.bypass.nicktab
  Hide_Real_Names_In_List: true
  Protected_Names:
    - DevKazuo
    - Chatalyst
```

## Configuration

### Nicknames

| Option | Default | What it does |
|---|---|---|
| `Max_Length` | `16` | Maximum length, not counting color codes. |
| `Set_Display_Name` | `true` | Also sets the Bukkit display name, so other plugins print the nickname. |
| `Sync_Essentials` | `true` | Mirrors the nickname into EssentialsX, so `/tpa <nick>` and `/whois` work. |
| `Tab_Complete_Nicks` | `true` | Replaces real names with nicknames in tab completion of every command. |
| `Tab_Complete_Bypass` | `chatalyst.bypass.nicktab` | Players with this permission keep seeing real names. |
| `Hide_Real_Names_In_List` | `true` | `/list` answers with the names players see, not account names. |
| `Protected_Names` | `DevKazuo`, `Chatalyst` | Names nobody may use as a nickname or nametag title. |

### Anti-impersonation

Without `chatalyst.nick.impersonate`, a player cannot choose a nickname that matches:

- the name of any player who has joined your server, online or not;
- another player's current nickname;
- a name in `Protected_Names` or in `protected-names.txt` (a shared list the plugin keeps up to date).

Matching ignores case, spaces and look-alike characters, so "I b a i" or full-width letters do not get around it. A name never blocks its real owner.

### Where the nickname shows

| Place | How |
|---|---|
| Chat | `{player_nick}` and `{rank_nick}` in every format. |
| Tab list | Chatalyst's tab list, or TAB through `TAB.Sync_Nick` / `%chatalyst_nick%`. |
| Above the head | One solid color with the classic nametag, full style with the [floating nametag](nametags). |
| Mail and menus | Senders, friends, pickers, balance top and the player list. |
| Commands | `/msg`, `/inbox send`, `/report`, `/mute` and other Chatalyst commands accept the nickname. |
| Other plugins | The Bukkit display name and, with `Sync_Essentials`, EssentialsX. |

> [!TIP]
> ItemsAdder font images work in nicknames: `/nick Pepo:crown:` stores the image inside the nickname (respecting ItemsAdder's per-emoji permissions), so it shows wherever the nickname does.

### Nick colors and chat colors

`/nickcolor` opens a hub with solid colors (split into strong and pastel), gradients, a gradient builder, text styles and, when enabled, animated presets. `/chatcolor` offers the same palette for the player's chat messages.

The 16 vanilla colors are free. Extra colors and gradients come from `Chat_Color` in `players.yml`:

```yaml
Chat_Color:
  Colors:
    blush: "<#FF6B9B>"
    coral: "<#FF7A59>"
    honey: "<#FFC857>"
  Gradients:
    sunset: "<gradient:#FF512F:#F09819>"
    ocean: "<gradient:#2E3192:#1BFFFF>"
```

The shipped file has 9 colors and 37 gradients. Each extra color needs `chatalyst.color.<id>` and each gradient needs `chatalyst.gradient.<id>`, so you can sell them as rank perks. `chatalyst.color.*` and `chatalyst.gradient.*` unlock all of them.

To force a color into the strong or pastel group of the nick menu, list it under `Chat_Color.Categories.strong` or `Chat_Color.Categories.pastel`. Unlisted colors are sorted by brightness.

### Animated nicknames

With `Nametag.Fancy.Animation.Per_Player: true` in `nametag.yml`, players pick an animated preset (rainbow, fire, ocean…) from `/nickcolor`. The animation plays on the floating nametag. See [Floating nametags](nametags#animation).

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/nick <nickname>` | `chatalyst.nick` (op) | Sets your nickname. Spaces are allowed. |
| `/nick off` | `chatalyst.nick` (op) | Removes your nickname. |
| `/nick <player> <nickname>` | `chatalyst.nick.others` (op) | Changes another player's nickname. |
| `/realname <nickname>` (`/rn`) | `chatalyst.command.realname` (op) | Shows the real name behind a nickname. |
| `/nickcolor` | `chatalyst.nick.color` (op) | Opens the nick color hub. |
| `/nickcolor <player> <color\|reset>` | `chatalyst.nick.color.others` (op) | Colors another player's nickname. |
| `/chatcolor [color\|reset]` | `chatalyst.command.chatcolor` (true) | Opens the chat color menu or sets a color by id. |
| `/chatcolor <player> <color\|reset>` | `chatalyst.chat.color.others` (op) | Sets another player's chat color. |

| Permission | Default | What it allows |
|---|---|---|
| `chatalyst.nick.color` | op | Colors and MiniMessage in the nickname. |
| `chatalyst.nick.impersonate` | op | A nickname that matches another player's name. |
| `chatalyst.gradient.custom` | op | The two-color gradient builder in `/nickcolor`. |
| `chatalyst.nick.style.*` | op | Bold, italic, underlined, strikethrough and obfuscated nick styles. |
| `chatalyst.chat.style.*` | op | Chat styles. Bold, italic, underlined and strikethrough are `true` for everyone; obfuscated is op. |
| `chatalyst.bypass.nicktab` | op | See real names in tab completion. |

## Limits

- Vanilla tab completion of other plugins' commands may still build suggestions from profile names. Typing the nickname works.
- The classic nametag above the head can only show one solid color. Use the [floating nametag](nametags) for gradients.
- A player in [anonymous mode](anonymous) cannot change their nickname: it is their disguise.
