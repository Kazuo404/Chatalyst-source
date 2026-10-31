# Conditional formats, books and rule API

## Conditional format parts

Every entry under `Parts` still requires only `Message`. The following optional gates now control who creates or receives that part:

```yaml
Parts:
  staff-badge:
    Message: "<red>[STAFF] "
    Sender_Permission: chatalyst.staff

  moderation-button:
    Message: "<dark_gray>[<red>x<dark_gray>] "
    Receiver_Permission: chatalyst.deletemessages
    Receiver_Variable: "{world} !event_world"
    Run_Command: "/chatalyst removemessage {message_uuid}"
```

- `Sender_Permission` and `Receiver_Permission` support `!permission.node` negation.
- `Sender_Variable` and `Receiver_Variable` use `placeholder expected`.
- A part whose command uses `{message_uuid}` is hidden automatically when Paper did not provide a usable signature; no additional condition is required. Premium/offline account status and `enforce-secure-profile` are not Chatalyst requirements.
- Values support equality plus `!=`, `>`, `>=`, `<`, `<=`, `contains:`, `starts:`, `ends:`, `in:` and bounded `regex:`.
- `Sender_Condition` and `Receiver_Condition` accept trusted-administrator JavaScript and reuse the restricted, compiled script cache.
- `Insertion` inserts text on shift-click.
- `View_Permission` remains a compatible alias for `Receiver_Permission`.

Sender conditions and the part component are evaluated once. Only receiver conditions run per viewer, preserving Chatalyst's prepared-render performance model.

## In-game server books

Players can still use `/book <name>`. Administrators with `chatalyst.book.admin` can use:

- `/book list`
- `/book new`
- `/book edit <name>`
- `/book save <name> [permission]`
- `/book delete <name>`
- `/book open <name> [player]`

The administration panel now opens a paginated library. Click opens a book, shift-click creates an editable copy, and the center control creates a draft. Saves and deletes write `books.yml` asynchronously with atomic file replacement and publish the new definition immediately.

## Rule integration events

Plugins can listen for:

- `RuleMatchEvent`: cancellable after all declarative conditions pass and before cooldown/state acquisition.
- `RuleReplaceEvent`: cancellable and mutable replacement result.
- Existing `ChannelMessageEvent` and `ChannelSwitchEvent` remain available.

Handlers must respect Bukkit's event-thread rules. An event reports itself as asynchronous whenever the rule context is processed outside the primary thread.

Titles and achievements now have their own versioned service, durable lifecycle events and
compile-time jar. See [Titles and achievements API](titles-api.md).
