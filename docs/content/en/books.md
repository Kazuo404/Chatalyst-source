---
title: Books & rules
description: Server books that open as a real Minecraft book. Write your rules or a help guide once, open it with a command, and edit it in game without touching files.
---

Books are the nicest way to show longer text: your server rules, a guide for new players, a list of commands. Chatalyst opens them as a real written book, with colors, clickable text and placeholders such as the player's name. Players open a book with `/book <name>`; staff can write and edit books in game by writing in a normal book and saving it.

Two books come ready to edit: `rules` and `help`.

## Configuration

Books live in `books.yml`:

```yaml
Books:

  rules:
    Title: "<#D8B4FE>Rules"
    Author: "Server"
    Permission: ""
    Pages:
      - "<bold><dark_red>Server rules</dark_red></bold>\n\n<black>1. Respect all players.\n\n2. No spam or advertising.\n\n3. No hacks or bug abuse."
      - "<black>4. Watch your language.\n\n5. Have fun and be kind.\n\n<#C0B3D9><i>Thanks for reading, {player_name}.</i>"

  help:
    Title: "<#B56CFF>Help"
    Author: "Server"
    Permission: ""
    Pages:
      - "<bold><dark_aqua>Useful commands</dark_aqua></bold>\n\n<black>/msg <player> - private message\n/r - reply\n/channel - switch channel\n/mail - mailbox\n/nick - nickname"
      - "<black>/chatcolor - chat color\n/ignore - ignore someone\n\n<#C0B3D9><i>Type /book to see the books.</i>"
```

| Key | What it does |
| --- | --- |
| `Title` | The book's title (MiniMessage) |
| `Author` | The author shown on the book |
| `Permission` | Permission needed to open it. Empty = everyone |
| `Pages` | One entry per page. Use `\n` for a line break |

Pages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html), so you can add colors, hover text and clickable commands. Placeholders work too: `{player_name}`, your [custom variables](integrations#custom-variables) and PlaceholderAPI values.

> [!TIP]
> A book page is small. If text runs off the page, split it into two pages rather than shrinking it.

## Writing books in game

You do not have to edit YAML. Staff with `chatalyst.book.admin` can:

1. Run `/book new` to get an empty book and quill.
2. Write the pages as in any Minecraft book. Colors and formatting are kept.
3. Hold it and run `/book save <name> [permission]` to save it. Use `-` as the permission to make it public. The title and author are taken from the book if you signed it.

To change an existing book, `/book edit <name>` gives you an editable copy with its current pages; change it and save it again with the same name. `/book delete <name>` removes it.

## Opening books

| Command | Who | What it does |
| --- | --- | --- |
| `/book <name>` | everyone | Opens a book for yourself |
| `/book list` | everyone | Lists the books you can open |
| `/book open <name> <player>` | staff | Opens a book on another player's screen |
| `/book new` | staff | Gives you an empty book to write |
| `/book edit <name>` | staff | Gives you an editable copy of a book |
| `/book save <name> [permission]` | staff | Saves the book in your hand |
| `/book delete <name>` | staff | Deletes a book |

`/books` is an alias of `/book`. Permissions: `chatalyst.command.book` (everyone) to open books, `chatalyst.book.admin` (op) to write, edit, delete and open books for others. Staff can open every book, whatever its `Permission`.

## Showing the rules at the right moment

- A [rule](rules-engine) can open a book automatically with `Actions.Open_Book`, for example the rules book when someone breaks one of them.
- The [auto-response bot](auto-responses) already has an answer for "where are the rules".
- You can open the rules book to a player who just joined from a [newcomer](newcomers) command: `book open rules {player_name}`.

## Limits

- Books are the same for every language. If you want a Spanish rules book, create a second book (for example `normas`).
- Books written by players in survival are not books from this system. Those are covered by the [spy](spying) and the [rules engine](rules-engine) instead, and muted players cannot sign them.
