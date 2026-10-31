# Integrations

Chatalyst degrades cleanly: optional integrations are detected at startup and skipped if absent.

## Supported Hooks

- LuckPerms: ranks, prefixes and permission data.
- Vault: fallback ranks/prefixes and economy for `/baltop`.
- PlaceholderAPI: external placeholders.
- Built-in Discord bot: two-way bridge, channel routing, per-channel formats, market cards,
  and report commands.
- WorldGuard: region channels.
- Parties: party channels.
- ItemsAdder: font images, chat tokens and custom item ids in `[item]`.
- Nexo: custom item ids in `[item]` and content-pack detection.
- TAB: native placeholders and nick sync.
- dynmap: report markers for open tickets.
- Simple Voice Chat / Plasmo Voice: mute/unmute command bridge.
- PacketEvents: packet-level command blocking before Bukkit sees the command.
- Staff security: command audit trail for sensitive moderator actions.
- LibreTranslate (or any compatible endpoint): live per-viewer chat translation.

## Discord, ranks, and Tebex

Chatalyst owns the Discord bot and starts and stops it with the plugin; DiscordSRV and
webhooks are not required. The `Rank_Catalog` in `integrations.yml` is the single source
of truth connecting a stable ID to its LuckPerms group, Discord role, and Tebex duration.
See the [complete bot, synchronization, and Tebex tutorial](discord-staff.md).

Rank delivery also supports a global or per-`Rank ID` presentation, editable through
`/chatalyst tebex gui` or `integrations.yml`. It can be private or server-wide, subtle,
fancy, or silent, and combine chat, toast, title, action bar, sound, and cosmetic fireworks.
It does not require the Discord bot to be enabled.

Every text is **bilingual** through the usual `_es` convention (`Message`/`Message_es`,
`Title`/`Title_es`…), and with `Audience: GLOBAL` that matters more than anywhere else: the
announcement is read by the whole server at once, so the language is chosen **per viewer** and
the same announcement lands in English for some and in Spanish for others. Rewrite a text
without translating it and your wording covers both languages — your sentence is never mixed
with the factory translation. Editing through `/chatalyst tebex gui` writes **your** client's
language: a Spanish client edits `_es` and leaves the English text untouched.

For players the command is **`/ranks`** (alias `/rangos`), renameable with
`Tebex.Shop.Command`. Never make them type the plugin's own admin command. Chatalyst **never
takes** a label another plugin owns: if the one you pick is taken it is skipped with a console
warning naming the owner, so your shop plugin keeps `/shop`; the `/chatalyst ranks` fallback
always works. Each card owns its exact Tebex HTTPS link and sends a
safe chat button so the client can confirm opening the browser. Icons accept vanilla materials
or ItemsAdder IDs, and store text supports its font images.

### Nothing paid for is ever lost

Tebex treats a command as executed the moment it dispatches it and never resends it, so a
delivery that failed on our side would silently destroy a purchase. Chatalyst therefore does
not model delivery as an order to execute, but as a **desired state to converge on**.

A purchase is recorded as an *entitlement* — what that player owns: the rank, its absolute
expiry, and the transaction. That record is the source of truth. The LuckPerms group and the
Discord role are derived from it, and a reconciler compares the derived state with reality and
repairs the difference on join, after every purchase, when a buyer links their Discord account,
and on a slow rotating background sweep.

The practical consequence is that a failed delivery is not a special case, it is just drift —
and so is drift a retry queue could never have noticed: a rank removed by hand, a Discord role
sync overwriting a paid group, a restored backup, LuckPerms losing a node, or an expiry coming
due. All of it converges. A purchase that arrives while LuckPerms has not loaded yet is
recorded and applied moments later instead of being rejected, and a Discord role is only
considered delivered once it really exists on the member, so an offline bot or a buyer who
links their account days later still ends up with the perk.

**A rank ends when its Tebex time runs out**, not because someone takes it away. The duration
you set in `Tebex_Duration` becomes an absolute expiry that is written onto the LuckPerms node
itself, so the permissions stop applying at the exact second with no plugin involved at all.
The sweep then serves expired buyers before anything else, so the Discord role and the stored
record follow within a sweep interval instead of waiting for their turn in the rotation.
`/chatalyst tebex revoke` exists for refunds and chargebacks — Tebex sends it — not as the
everyday way to remove a rank.

The reconciler only ever touches groups Chatalyst itself delivered to that player, so a rank
an administrator granted manually is never mistaken for drift and removed. The flip side is
that pulling a paid rank out of LuckPerms by hand does not stick: the purchase still says the
player owns it, so it is restored on the next pass. Let the duration end it.
`/chatalyst tebex status` shows how many purchases are on record, and `Tebex.Reconcile` tunes
the sweep.

**A buyer is always their UUID.** Use `{id}` in the Tebex panel: a player's name changes and
their UUID does not, so only the UUID still identifies them after a rename — nothing they
bought is lost by changing it. Delivery does **also accept a name**, resolving it against the
real account (the online player, or the server's name cache) before storing that UUID, because
a package configured with `{username}` or a payment taken outside the store must not be lost
over it: Tebex considers the command executed the moment it dispatches it and **never resends
it**. For the same reason a rejected delivery — the integration switched off, a rank ID that
does not exist, a group that is not allowed — is logged as SEVERE and reported to staff,
instead of answering a console nobody is watching.

## Selling items and anything else (`store.yml`)

Ranks live in `integrations.yml` and one-shot goods in **`store.yml`**, and the split is not
cosmetic: they are opposite guarantees.

A rank is **state**. You hold it, it can expire, and if it goes missing Chatalyst can put it
back by comparing what you own with what you have. That is why it is reconciled forever.

An item is an **event**. It is handed over once and cannot be repaired the same way — the
buyer may have used it, dropped it or died with it, and reconciling it would hand them a fresh
copy every few minutes. So goods use the opposite guarantee: **at-most-once** delivery.

Each `Store_Catalog` product supports three things, combinable in one product: `Items` (with
name, lore, enchantments and model data), `Commands` (console commands with `{player}` and
`{uuid}`, for anything another plugin owns — crate keys, pets, cosmetics) and `Money` (a Vault
deposit). The Tebex package runs:

```
chatalyst store give {id} <product_id> {transaction}
```

Only IDs declared in the catalog can be delivered, so a mistyped command cannot invent a
product — and the command line never carries materials, amounts or commands to run, only an ID.

The purchase is written to disk **before** anything is given. If the buyer is offline it waits
and is delivered when they join; if their inventory is full **nothing is dropped on the
ground**: the claim stays pending and retries, so goods cannot be lost to lava or to whoever
was standing nearby. Once handed over the claim is consumed, and neither a resent Tebex
transaction nor a second backend on the same database can repeat it.

`/store` opens a menu of what is owed: each card uses the product's own first item as its icon,
lists what it contains, and is taken with one click; the button at the bottom takes everything.
`/store list` keeps the chat output and `/store claim` takes everything without opening a menu.

### The sectioned storefront

A product with a `Shop:` block appears **in the same storefront as ranks** (`/ranks`), not in a
separate one. It needs `Visible: true` and a real HTTPS `Purchase_Url`: a card that cannot be
bought is worse than no card, so without a URL it is not listed. Without a `Shop:` block the
product stays deliverable, it is simply not advertised.

`Store_Sections` groups the storefront into sections. It is **off by default**: with
`Enabled: false` the shop is the flat list it always was. Turn it on and the store opens on the
sections, each holding the entries — ranks and goods, mixed — that name it in `Shop.Category`.

Sections live in their own block rather than as a loose field on each product, so the order,
icon and name of the shop are edited in one place. A section with nothing visible is hidden, and
an entry naming a section that does not exist is **not lost**: it lands in a final "Other"
section, so a typo never quietly removes something you are selling.

## Mandatory account linking

`Discord.Linking.Required` turns linking into a condition for playing. A player who has not
linked is held on arrival: their code is issued automatically and chat, commands and —
optionally — movement, interaction and combat are refused until `/discord link` completes.
`Kick_After_Seconds` can disconnect a player who never links; `0` only restricts them.

The requirement is **fail-open by design**. It is enforced only while the whole chain is
genuinely working: the bot enabled and configured, linking enabled, the gateway session READY
and the configured `Guild_Id` reachable. During an outage, a reset token or a bot removed from
the guild nobody is held, and anyone already waiting is released with an explanation. Players
can never be locked out of the server for something outside their control.

With AuthMe, nLogin or a similar login plugin installed, the prompt waits for that plugin's
own login event so it never fights the login screen. When the event cannot be bound, the
prompt appears `Auth_Fallback_Seconds` after joining instead, and `Auth_Login_Event` can name
the event class of an unlisted auth plugin. `/discord` is always allowed regardless of
`Allowed_Commands`; keep your auth plugin's commands in that list so a held player can still
log in. `chatalyst.discord.exempt` (op by default) skips the requirement entirely.

## Live chat translation

With a [LibreTranslate](https://github.com/LibreTranslate/LibreTranslate)-compatible backend,
Chatalyst can translate each eligible public-chat line into the viewer's Minecraft client language: a player
whose client is Spanish reads an English message in Spanish, and vice-versa. The sender and anyone
sharing the sender's language see the original, so no translation is wasted.

Configure it under `Translation` in `integrations.yml`:

- `Enabled` — off by default; needs a backend URL to do anything.
- `Provider_Url` — the `/translate` endpoint (self-host LibreTranslate for a free, keyless server).
- `Api_Key` — optional, for hosted endpoints.
- `Timeout_Ms` / `Min_Length` / `Cache_Size` — bounded request and cache tuning.

Design notes:

- **Fail-open.** If the backend is down, slow, or disabled, every viewer simply sees the original
  text — chat never breaks or blocks the server thread.
- **Async + cached.** Translation runs on the JDK `HttpClient`; the message is fetched once per
  target language and cached (LRU), so repeated phrases are instant. A small delay (~0.2–0.5s) is
  normal on the first occurrence.
- **Explicit opt-in at both levels.** The owner must enable the provider, and each player must then
  choose `/toggle translate` (also available in the `/toggle` GUI). Player consent defaults to off
  and persists as an explicit preference.
- **Only when it helps.** Chatalyst requests a translation only for opted-in viewers whose client
  language differs from the sender's. A monolingual audience therefore makes no translation call.
- **Signed-chat limitation.** A translated line is delivered as a per-viewer system message. Mutes,
  rules, anti-spam, logging, Discord and mentions still run, but Minecraft cannot retract that line
  with the staff delete-message X after it has been displayed.
- **Privacy.** The configured provider receives the plain chat text and target language. Prefer a
  self-hosted endpoint, use HTTPS for a remote provider and review its data-retention policy.

## Current Scope

ItemsAdder is actively used for font-image replacement. The Discord bot, WorldGuard, Parties, Vault, LuckPerms and TAB have active code paths. Nexo, dynmap and voice bridges now provide concrete behavior; PacketEvents now adds a packet-level fast path for command blocking when enabled. Staff security now audits sensitive moderator commands to console and the private bot channel.
