# Changelog

## Unreleased — safer channels, a stronger anti-exploit and less waiting on storage (2026-10-31)

- **Private channels stay private.** A PARTY channel needs the Parties plugin and is not loaded
  without it. A mistyped `Type` rejects the channel instead of making it public. Only GLOBAL
  channels cross servers, so `Proxy` is ignored on every other type.
- **New `GROUP` channel type** (`Group_Placeholder`): clan chat for any clan plugin, with a
  placeholder. Staff can read party and clan channels with `chatalyst.spy.channel`.
- **Typed colours follow the same permissions as `/chatcolor`.** Gradients and rainbow need
  `chatalyst.gradient.*` or `chatalyst.gradient.custom`, and hex needs `chatalyst.color.*`.
  Named colours still need only `chatalyst.chat.color`.
- **Player text never reaches PlaceholderAPI**, in messages and nicknames alike.
- **Discord:** URLs arrive intact, colour tags are removed, `Discord.Emoji_Replacements` maps
  symbols to emojis (also by colour), and `Discord.Mentions` lets players notify members.
- **Anti-exploit:** a total limit per connection that also covers login and configuration,
  deep bracket nesting in commands, book limits, and one burst now counts as one kick.
- **Storage:** a slow database no longer blocks the main thread on `put`, and the shared refresh
  now reads one namespace at a time (`Shared_Refresh_Seconds` defaults to 30).
- **Performance:** tab list, nametags, anonymous guard, nick cleaning, title placeholders and
  `/unban` completion no longer repeat work that did not change.
- **New:** `/chatalyst sendformat`, `{player_uuid}`, `{receiver_uuid}`, `{killer_uuid}`, `{health}`.
- **Also:** bulk database reads use their own connection, scripts run on up to 4 engines at once,
  a large market sweep is spread across ticks, expired bans are saved once, and connection data
  is deleted after `Staff_Moderation.Alts_Retention_Days` (365 by default).
- **Licensing:** GNU Trove is now downloaded by the server, and the jar includes
  `META-INF/THIRD-PARTY-NOTICES.md`. The optional LiteBans integration was removed.

## Unreleased — vanish that only watches, and walking away that finally counts (2026-08-04)

- **Vanish is for watching, not for touching** (`Staff_Moderation.Vanish.Observer_Only`, four
  switches, all on). Invisible you could break, place, open chests, hit and pick things off the
  floor. Each of those gives the vanish away as fast as being seen does — a block that breaks
  itself, a chest that opens with nobody in front of it — but the real reason is the advantage:
  invisible you can mine inside somebody's base, empty a chest and finish off a fight, and none
  of it has an answer. Damage RECEIVED is deliberately untouched: an invulnerable vanish turns
  lava, the void and every trap into a free viewing platform, so walking in where you should not
  costs what it costs anyone — death and dropped items included. Staff tools are never blocked;
  the kit, the menus and the invsee editor are how the watching is done. Since you cannot eat,
  hunger stops draining too: that is not an advantage — there is nothing to gain from a bar you
  could not refill anyway — it just stops a forty-minute watch from ending in death by
  starvation, with a grave marking the exact spot you were watching from.
- **Coming back and being announced are now the same instant.** The alias reappeared and the
  line saying they joined arrived afterwards — its own chain of tasks, plus the fancy join's
  `Delay_Ticks` on top, which is half a second by default. In that gap you could watch somebody
  appear out of nowhere before anything said they had come back, which is the same tell as being
  seen morphing. The reappearance now travels as a callback all the way to the moment the line
  actually reaches the chat.
- **A disguise group LuckPerms does not have no longer fails in silence.** `Disguise_Group`
  pointing at a group that does not exist gave the disguise no rank at all, and nothing said so —
  it only showed up as a bare name, which is exactly what the setting exists to avoid. It now
  warns in console, naming the group, and falls back to `default`.
- **The name is now covered in what OTHER plugins write** (`Anonymous.Guard_Other_Plugins`, on).
  A grave with your name floating over it is as good a clue as anything the server prints — it
  is read by whoever walks past, it stays for hours, and it stands exactly where the alias just
  died. Same for an anticheat alert or another chat plugin's death line. They cannot be fixed
  one by one, and there is no knowing what will be installed tomorrow, so it is corrected at the
  end of the road: right before the text reaches the client, the real name becomes the alias
  that player is wearing. Not censorship — translation: the message arrives whole, naming the
  person as the reader knows them. Floating entity names are caught when they appear and again a
  tick later, because most plugins name the entity after creating it; messages need PacketEvents
  installed. Staff who may use `/realname` are never rewritten. Matching is by whole word: a name
  inside a longer name is left alone.
- **Chat was not the only loud place.** The guard above only watched system chat, and a plugin
  that wants something NOTICED does not send it to chat: it sends it as a full-screen title, a
  subtitle, an action bar or a boss bar, which is exactly where people look. A "DevKazuo wins!"
  filling half the screen gives far more away than any chat line does. Those four paths now go
  through the same filter, and so does the title of a menu another plugin opens ("DevKazuo's
  chest"). Scoreboards are deliberately left out: there the name is not text being read, it is
  the key the client matches each line by, and changing it would break the scoreboard instead of
  hiding anything.
- **And the name survived whole inside clicks.** A `[Claim the grave]` with its text already
  covered could carry a `/msg DevKazuo` inside, ready to appear in the chat box the moment
  somebody clicked: text replacement walks children, hovers and translation arguments, but it
  does not reach a click's action. That click is now REMOVED rather than translated. A command
  with the alias inside is no longer the same command — at best it finds nobody, at worst it
  finds another account genuinely named that — and the same goes for a link or a clipboard copy.
  Without the click the message still reads whole; all that is lost is a shortcut to a person
  who does not exist right now. It is cleaned inside children, hovers and translation arguments
  too.
- **An advancement announced the real name, and announced it by itself.** The same hole `/list`
  had — the server builds the line from Minecraft's display name, which is the account name —
  but worse in character: `/list` has to be typed, and an advancement goes out to the whole chat
  with nobody asking for anything. Someone playing anonymous for an afternoon chains
  advancements, and the first one is enough to know who they are. It is rewritten rather than
  silenced, because the advancement is theirs and deserves telling: the line is a translatable
  message whose first argument is the name, so only that argument changes and everything else
  survives — the client keeps reading it in its own language and the advancement keeps its
  hover.
- **`/list` was telling everyone the real name** (`Nick.Hide_Real_Names_In_List`, on). Chat, the
  tab list, nametags, deaths, joins and quits all showed the nickname — and then any player
  typed `/list` and read the account name of every one of them. It is not a formatting oversight
  that could be styled away: the vanilla command builds its line from Minecraft's own display
  name, which is the account name with the team colour on it, and never looks at the name a
  nickname sets. `/list uuids` handed out the UUID next to it, which finds the account even when
  the name means nothing to you. Chatalyst answers `/list` itself now, with the names people
  actually see and the same visibility rule vanilla used — whoever cannot see you does not count
  you. Staff who may use `/realname` are not intercepted: resolving identities is already theirs.
- **The disguise gap now also stops mail.** Same reasoning as private messages, one door further:
  the mail arrives under the other name and pings the recipient the moment it lands. It is
  checked in `MailManager.send`, the single point every path goes through — command, reply,
  menus, chat compose and the book.
- **`Max_Wait_Seconds` is sanity-checked** (1 second to 1 hour), the last unbounded delay left in
  the transition. A digit too many did not error: it scheduled the ceiling hours out and left the
  player waiting on a disguise that never came — the exact symptom the ceiling exists to prevent.
- **The gap is closed on every door, not just the loud ones.** A kick or a ban landing inside the
  transition still printed its line after everyone had watched you leave — and taking the
  disguise off, that line carries your REAL name directly under the alias's goodbye, which is the
  one correlation all of this exists to break. An advancement did the same through the vanilla
  door nobody was watching: `Kf3v9x has made the advancement` shouted at the whole server by
  somebody who had just "disconnected". And a private message walked out the side door — one
  reader instead of the whole server, but the same clue. All three now hold their tongue while
  the world believes you gone. The punishment still lands, the advancement is still granted; only
  the line naming you is dropped.
- **Nobody leaves the server twice.** Between the simulated quit and the simulated join there is
  a gap where, for everyone but you, you are not on the server. Disconnecting for real inside
  that gap announced the departure a SECOND time — and with the other name, which is the one clue
  needed to tie the alias to the person: `DevKazuo left`, then `Kf3v9x left`. Now a real quit is
  silent while the world already believes you gone, in game and on the Discord bridge alike. The
  mark is dropped the moment you are announced back in, so an ordinary quit is never swallowed.
- **Taking the disguise off waits too, not just putting it on.** Turning anonymous mode off gave
  your real face back on the spot, in front of whoever was there — and that is not half the
  problem, it is the whole problem backwards: anyone who watches the alias turn into you learns
  in one second WHO the anonymous player was, which is the single thing everything else here
  exists to prevent. The only way to switch it off without giving yourself away was to hide by
  hand with `/vanish` first. Now both directions wait for the same unwitnessed moment, and the
  notices follow the direction of the change — while the wait lasts you are told your real name
  is coming back, not that a disguise is going on.
- **Walking away from people now brings the anonymous alias in.** Proximity was checked ONCE, at
  the moment of the swap, and that single snapshot decided both the arrival delay and whether you
  were hidden. Pressing `/anonymous` with somebody within 64 blocks bought the long tier — up to
  five minutes — and moving away afterwards changed nothing, because nobody asked again: you
  stayed hidden, waiting on a witness who had already left. Which is exactly the ghost time the
  feature exists to avoid. The long wait is now a CEILING: the question is repeated every second
  and the arrival is brought forward as soon as nobody is looking — never before the short tier,
  because leaving and arriving in the same breath gives the disguise away just as well.
- **The player list gave the change away on its own.** Whether you were hidden for the gap was
  decided by the same proximity check as everything else: nobody within `Observer_Radius`, so
  nothing to hide from. But hiding is what makes your row in the player list LEAVE and come
  back, and that list has no distance. Left visible, the row is the same one all along and only
  its text changes — `DevKazuo` becoming `Kf3v9x`, in place, for the whole server at once,
  however far away anyone is standing. The exact change everything here exists to keep from
  being seen, happening in the one place with no range limit at all — and on the ordinary,
  nobody-was-looking path, which is the one the feature is built around. Proximity still decides
  the WAIT and how long the absence lasts; the absence itself is now always spent out of sight,
  which when nobody saw the swap is just the few seconds of `Rejoin_*_Seconds`.
- **Nothing is faked if nobody was told you left.** The "player has gone" mark went up before
  the goodbye was broadcast — and it is not broadcast when you are vanished or when quit
  messages are off. So a swap nobody could have noticed still cost you the whole illusion: no
  chat, no private messages, no death, kick or advancement line, for up to five minutes, hiding
  a correlation that never existed, with a notice claiming the server had just seen you leave.
  The simulated quit now reports whether it reached anyone, and without that there is no gap, no
  hiding and no silence — the disguise simply changes.
- **Nobody the server has waved goodbye to can be heard.** The gap between the simulated quit
  and the simulated join hid the body and both messages, and then let you talk straight through
  it. One sentence undid all of it: the chat had just said `DevKazuo left` and there was
  `Kf3v9x` speaking before ever joining — and backwards it is worse, because taking the disguise
  off says goodbye to the alias and the next line anyone reads is your REAL name, right under it.
  A player who has left does not talk, so now they do not: the message is stopped before the
  format, the channels and the Discord bridge, and you are told why. Dying is silenced the same
  way — the death itself happens whole, drop and respawn included, only the line naming you is
  dropped. And it is not a punishment with no way out: walking away from people closes the gap
  early, which is exactly what the notice tells you to do.
- **Switching it off no longer flashes the old face in the player list.** Asking for the
  disguise and then changing your mind before the alias had arrived cancelled the pending
  arrival — and put you back in everyone's tab list wearing the identity that was about to
  change, a moment before it changed. Anyone with the list open saw the entry morph from alias
  to real name: the exact change-in-place that the hiding, the waiting and the delay all exist
  to prevent, coming in through the back door. You now stay out of sight until the arrival that
  follows, which is when the new face is already on.
- **The disguise cooldown never started counting** (`Anonymous.Switch_Cooldown_Seconds`, `0` out
  of the box, so no server was silently ungated). It was armed from the command, right after
  asking for the disguise — but since the swap started waiting for a moment nobody was watching,
  at that instant there was nothing active yet, so it always looked like the activation had
  failed. It is now armed where an identity actually comes into being, which also means a swap
  that fails on storage no longer leaves a phantom cooldown behind.
- **Changing your mind halfway through the wait counts, in both directions.** Asking for the
  disguise while waiting to take it OFF answered "you are already anonymous" and stripped you a
  minute later anyway — the same trap that was just fixed for the other direction, mirrored.
  Asking again for what is already coming no longer opens a second wait either; it just repeats
  the notice. And picking a saved face with `/anon set` cancels a pending switch-off, because
  choosing a character is the opposite of asking to stop being one.
- **A lost callback can no longer strand the disguise.** `Max_Wait_Seconds` was only checked at
  the start of each polling round, and each round was scheduled by the previous one, so a single
  dropped callback broke the chain and left the player waiting forever with nothing in console.
  The ceiling is now its own task and does not depend on the poll.

## 1.0.0 — first release (2026-08-04)

Everything below ships in 1.0.0. The sections keep the date they were written on, newest
first: each one tells the story of a feature, and they read better whole than melted into a
single list.

### Purchases that cannot be lost, mandatory linking, and text that reads right (2026-07-28)

- **One ban, both sides** (`Discord.Ban_Sync`, off by default). A player banned in Minecraft who
  keeps their Discord seat is only half-banned — that is where they argue the ban and arrange the
  alt that comes back. Which account is which comes from the LINK, never from a name: an unlinked
  player is not mirrored, because guessing a Discord account from a username is how you ban the
  wrong person. A temporary ban is SHOWN but never enforced on Discord — a guild ban has no
  expiry, so mirroring a one-day ban would outlive the punishment by years since nothing comes
  back to lift it. Only a permanent ban removes the account, and the person is told why in a DM
  BEFORE they are removed, while the bot can still reach them. `Direction` picks one way or both.
- **Punishments are visible on Discord** (`Discord.Punishment_Roles`, off by default). A banned,
  frozen or muted player who looks identical to everyone else in the member list is a gap staff
  fill from memory. The bot creates and maintains the roles itself. They are expressed in TIERS,
  not exact days, and that is not a shortcut: a role is one string shared by everyone wearing it,
  so it cannot say "30 days" to one member and "12" to another, and a role per day would need one
  per day against a cap of 250 per guild. A background pass moves people down the tiers as their
  time runs out and undresses a punishment that simply expired — and only contacts Discord when
  something actually changed.
- **`/chatalyst doctor discord` asks Discord itself.** The config doctor can only read files, and
  what actually breaks here lives on the other side: a permission granted in a menu, a role
  dragged above another, an intent ticked in a portal. Get one wrong and nothing crashes — the
  bot connects, the config looks right, and features quietly do nothing. This reports the
  gateway, whether every configured channel is visible AND writable, Manage Roles and Ban Members
  when the features needing them are on, the linked role's position, and whether the staff
  allowlist authorises anybody at all. It only reads: nothing to clean up afterwards.
- **The Discord door runs the command it checked.** The label was validated against Chatalyst's
  catalog and then dispatched through the server's global command map, where `ban`, `ci`, `note`
  and `check` also belong to EssentialsX and half a dozen others — so the gate inspected one
  command and executed another, as console, unreviewed. Worse, the catalog is a static list that
  does not know whether the staff suite is even registered, so with `Staff_Moderation` off the
  door still opened. Requests now go by the namespaced form, which does not exist when the module
  is off. `/clearinventory` is also refused from Discord now: it is the only tool in the suite
  that cannot be undone — worn armour and an ender chest do not come back — and asked for by
  chat, against somebody you are not looking at, a mistyped name stops costing an apology.
- **Punishment tiers are yours.** Removing the tier you do not want (no role for short
  punishments, which the config documents as valid) came back on the next start, so the
  documented option could not even be kept. They are now protected like every other owner-owned
  collection, with a one-time seed so a server updating still receives the factory tiers —
  without them the feature does not fail, it goes MUTE.
- **A rejected delivery cannot forge log lines.** The rejection detail carries what arrived in the
  command — buyer name, product or rank id, payment reference — and lands in two places where an
  extra line is a false line: the console trail explaining why a paid purchase was not delivered,
  and a staff alert in Discord, which also renders markdown.
- **Mandatory linking held everyone when the key was missing.** `Only_Buyers` ships as `true` and
  the config doctor assumes `true`, but the gate itself defaulted to `false` — so a config missing
  that key held the whole server at the door while the diagnosis reported the opposite.
- **Moderation from Discord** (`Discord.Staff.Commands_From_Discord`, off by default).
  `!ban`, `!kick`, `!freeze`, `!mute`, `!check`, `!seen` and the rest of the staff suite run
  without being in the game. They execute as the CONSOLE — not as the person typing, who is not
  even connected — behind the same channel and user/role allowlist as the report commands, and
  the command's own answer comes back to the channel so `!check` is worth typing. Tools that
  need you inside (`/invsee`, `/echest`, `/tpto`, `/tphere`, `/vanish`, `/staff`) are refused
  with the reason: they open something in front of you or move you, so they mean nothing from
  outside. Its own switch on purpose — closing a report and banning someone are not the same
  authority.
- **Report cards carry buttons.** Claim, Reply, Resolve and Reject, with a text window for the
  message or the closing note. Same gate as the text commands; with the gate closed the card is
  sent without buttons instead of showing buttons that answer "no".
- **The bot status shows the server.** `Discord.Activity` takes `{online}` and `{max}` and
  refreshes on a timer; vanished staff are not counted, so the member list cannot be the crack
  through which someone notices a moderator is watching.
- **A channel of its own for account linking** (`Discord.Linking.Channel_Id`). The DM with the
  bot always works — it is the most discreet place to send a code.
- **The anonymous transition no longer gives itself away.** The disguise applied instantly
  while the arrival was announced minutes later, so anyone watching saw the player morph in
  place right as chat said they had left. Now they disappear when their name leaves and come
  back wearing the alias exactly when chat announces the arrival
  (`Realistic_Transition.Hide_During_Transition`).
- **An IP ban now knows *which account* it was for.** It only stored the banned player's name
  and compared it to whoever connected, so a banned player who changed their name stopped
  matching themselves: they came through the alt door, got a second ban "for being their own
  alt", and staff got an alert that was not true. The record now carries the account UUID and
  matches on it; rows written before it keep matching by name until they are re-banned. The
  name shown to staff is resolved from the UUID, so it is the one they will be asked about.
- **Names written down are no longer frozen.** Market Discord cards took the seller's name as
  it was when the listing was published; a card stays written down, so a renamed player turned
  into a stranger. Stored names are now a fallback for display only, resolved from the UUID
  when the server still knows the account.
- **Saying "hola" twice is no longer spam.** The previous message was remembered until the
  player disconnected, so greeting someone and greeting them again twenty minutes later counted
  as a repeat — and a repeat does not just block the line, it adds a warning towards the
  automatic mute, so four greetings cost 15 minutes of silence. The repeat check now has a
  memory (`Anti_Spam.Repeat_Seconds`, 30s; 0 restores the old behaviour) and a length floor
  (`Anti_Spam.Min_Repeat_Length`, 5) so short everyday messages never count. Real spam — ads,
  links, walls of text — is long and arrives back to back, so it is still caught.
- **A closed market deal now posts a card in Discord** (`Discord.Market`, off by default).
  The in-game announcement is a chat line the next message carries away; the embed stays
  written down, with the whole operation on it — seller, buyer, item, and what was paid,
  including a mixed barter of money *and* items. Sales, auctions and returns toggle
  separately; returns are off because they are the noisiest and the least newsworthy. Card
  text is English on purpose: a Discord channel is read by the whole server at once and there
  is no viewer to take a language from.
- **Each game channel can look different inside one Discord channel** (`Discord.Channel_Formats`).
  Trade and auctions usually share one Discord room, where a sale and a bid used to read as
  the same line. A channel with no format of its own still inherits the general one.
- **The purchase announcement speaks both languages.** Its texts were the one owner-written
  surface still read from a single key, which meant a `GLOBAL` announcement — the one the whole
  server reads at once — was in one language for everybody. Chat, toast, title, subtitle and
  action bar now take a parallel `_es` key resolved **per viewer**. Rewriting a text without
  translating it keeps your wording in both languages rather than pairing it with the factory
  translation, and the merge on upgrade no longer drops a factory `_es` next to a text you
  already rewrote. The GUI editor writes the language of the client editing it.
- **A delivery is no longer thrown away over a name, and never rejected in silence.** Purchases
  were already stored by UUID — a rename cannot cost anyone their rank — but the endpoint
  accepted nothing else, so a package configured with `{username}`, or a payment taken outside
  the store and delivered by hand, was lost outright. A name is now resolved against the real
  account (online player, then the server's name cache) and only its UUID is stored; an
  offline-style UUID is still never derived from a name. Every rejected delivery now logs
  SEVERE and alerts staff, because Tebex considers the command executed and never resends it.
- **Mandatory Discord linking** (`Discord.Linking.Required`, off by default). An unlinked
  player is held on arrival with their code issued automatically. Deliberately fail-open: it
  is enforced only while the bot is READY, its guild is reachable and link codes can actually
  be stored, so an outage never locks anyone out. With AuthMe/nLogin installed the prompt
  waits for their login event instead of fighting the login screen.
- **Purchases are now entitlements, not deliveries.** What a player owns is the source of
  truth; the LuckPerms group and the Discord role are derived from it and repaired on join,
  after every purchase, on linking and on a background sweep. That covers drift no retry queue
  could see: a rank removed by hand, a Discord sync overwriting a paid group, a restored
  backup, an expiry coming due. Discord can no longer revoke a rank that was paid for.
- **New item store** (`store.yml`): sell items, console commands and money. Goods use
  at-most-once delivery — the claim is written before anything is given, waits through an
  offline buyer or a full inventory, and is consumed on handover. Nothing is dropped on the
  ground. `/store` shows what is owed and claims it.
- `Nametag`, `Tab_List` and the `TAB` hook moved to their own `nametag.yml` — they were half
  of `integrations.yml` and are not integrations. Existing configs are migrated with backups.
- Fixes from an independent audit: a Tebex `{transaction}` identifies the payment, not the
  package, so multi-package carts silently delivered only the first item; refunds could revoke
  a later purchase of the same rank; the reconciler could delete a context-scoped group an
  administrator had granted by hand; `claim all` could drop the last purchase on the floor;
  account linking was not one-to-one across a shared database; and a player without
  `chatalyst.command.discord` could be held with no way out.
- **Text already written to disk is repaired on startup.** The closing tags fixed inside the
  jar helped nobody who already had Chatalyst installed: a file on disk always wins over the
  jar's copy, so an existing server kept printing `</white>` and `</aqua>` at players — the
  wedding countdown read "the ceremony begins in 30s`</white>`". The repair is deliberately
  narrow because it edits text the owner may have written: named colours only, and only when
  that colour is never opened anywhere in the same file, so a deliberate `<white>x</white>` is
  untouched. A `.bak-tags` copy is written first and every change is logged.
- **`/store` opens the store.** It used to open the pending-deliveries tray — the least likely
  thing to expect from a command called "store", with the actual shop under `/ranks`. The tray
  is now a button inside the storefront showing how many items are waiting, plus
  `/store pending` for anyone who wants it directly.
- **The anonymous disguise stops giving itself away.** Leaving and arriving in the same second
  is something nobody does. The disguise still applies instantly, but the alias's arrival is
  announced 1 to 5 minutes later, drawn fresh each time — you "leave", keep playing disguised,
  and later a different player "joins". A face chosen with `/anon set` now also survives
  reconnects until it is changed or the mode is switched off, so a saved slot is a character
  rather than something that lasted until the next restart.
- **An edited command alias applies on reload.** `Command_Aliases` were registered once
  at startup and never again, so editing one and running `/chatalyst reload` changed nothing
  and only a full restart helped — with nothing saying so. The manager now withdraws its own
  aliases before re-registering them, using the same key-by-key removal the other registrars
  use, because Paper's known-commands map is a view whose iterator refuses `removeIf`.
- **Four defects that only show up in front of a player.** The staff dashboard read
  "Frozen players ({count})" — the counter was being passed to the lore, which has no counter,
  instead of to the title. `/effect` feedback read "Applied effect Speed to ." because the
  message asked for a third argument from a message that has two, and a missing index renders
  as nothing at all rather than as visible text. A sound written the way every Minecraft page
  writes it (`BLOCK_BEACON_ACTIVATE`) turned into a key that does not exist, so it simply never
  played — and the freeze notice was the one sound field that rejected the `minecraft:` key
  form used everywhere else. Both forms now work in every field. Finally, `Mail.Require_Friend`
  existed in the code but in no file, so the only way to find it was to read the source.
- **One palette, and closing tags that were being printed on screen.** A MiniMessage close
  with no matching open renders as literal text, so `<#FBF2FF>{player}</white>` was putting the
  characters `</white>` in front of players — 182 places, left over from the old named-colour
  palette, which cannot close a hex colour. The convention is to re-declare the next colour
  rather than close the previous one. The same drift had reached the colours themselves: a
  fourth gold, a second danger red, a second Discord purple, and two ranking menus with
  different podium medals. Both are now enumerated and enforced by tests.
- **Owner-written config text is bilingual too.** Storefront names, descriptions, buy buttons
  and section titles read a parallel `_es` key for a Spanish client, the same convention
  announcements and auto-replies already used. That convention had been reimplemented by hand
  in every class that needed it, which is how a new feature ends up single-language by
  accident; there is now one implementation of it, and a test that fails the build if anyone
  writes the lookup by hand again.
- `/chatalyst features` now points at the file that really owns each toggle, and lists the
  store and Tebex modules. `Mail.Enabled` was honoured by the code but missing from
  `players.yml`; it now ships there.

### Built-in two-way Discord bot (2026-07-26)

- Replaced DiscordSRV and the public webhook relay with a bot owned and lifecycle-managed by
  Chatalyst. Token, guild, main channel, formats and per-game-channel routes live in
  `integrations.yml`; `/chatalyst reload` reconnects it.
- Minecraft and Discord messages now travel in both directions through one connection, with
  bot/webhook loop prevention, disabled mentions, bounded input and Folia-safe delivery.
- Reports, staff chat and security alerts use private channels on the same bot. Staff report
  commands retain fail-closed channel plus user/role authorization.
- `/discord link` creates persistent, single-use account links for DMs and identity-aware rules;
  `Linked_Accounts` remains as a manual fallback.
- Event-driven, debounced synchronization now maps LuckPerms groups to Discord roles through an
  explicit allow-list.
- Tebex can grant and revoke ranks through idempotent `{id}`/`{transaction}` commands; temporary
  grants retain their absolute expiry across retries.
- Tebex delivery now has a complete GUI and global or per-Rank-ID YAML configuration, private or
  server-wide audiences, subtle/fancy presets, MiniMessage text, chat, toast, title, action bar,
  sound, safe fireworks, and deferred presentation when an offline buyer rejoins.
- `/cht ranks` (`shop`, `shops`, `tienda`, `rangos`) opens a paginated player store defined
  entirely in YAML, with per-Rank-ID cards, price, icon, description, order, featured state,
  visibility permission, and an exact HTTPS link delivered as a safe chat button.
  Icons accept vanilla materials or ItemsAdder IDs, and store text resolves font images.
- `Rank_Catalog` centralizes the stable ID, LuckPerms group, Discord role/priority, and Tebex
  duration; v32 rank syntax remains readable for compatibility.
- Discord staff can inspect report threads with `!info` and answer reporters live with `!reply`.

### Message-retraction compatibility correction (2026-07-26)

- Corrected the compatibility contract: Chatalyst can retract every line for which Paper
  supplies a usable `SignedMessage.Signature`; premium status and
  `enforce-secure-profile=true` are not Chatalyst requirements. This was verified with
  both premium and non-premium nLogin sessions. Unsigned/system lines retain the
  `/clearchat` fallback.

### Complete titles and achievements system (2026-07-26)

> Full documentation in [Titles and achievements](titles.md).

- Paginated player catalog with progress, preview, category and status filters, and sorting by
  priority, rarity, points or name; `LEFT`/`RIGHT` placement with one default space.
- Fully GUI-driven administration studio for creating, duplicating, enabling, hiding and deleting
  titles, editing every field, managing access and safely publishing disabled drafts.
- `ALL`/`ANY` groups of up to eight rules: mobs, players, playtime, blocks, crafted/picked-up/owned
  items, fishing, breeding, advancements, permissions, dependency chains and manual grants.
- Reactive event evaluation plus a 1,200-tick safety sweep for permissions, external inventory
  changes, time and other updates without a direct event.
- Achievement profiles with configurable points, completion percentage and rarity defaults.
- Cascading `TITLE_UNLOCKED` chains with ID validation, cycle rejection and protection against
  deleting active dependencies.
- Secret achievements, entries hidden until unlocked, and GUI-managed seasons with versioned IDs,
  start/end bounds and non-destructive resets.
- Automatic category collections with configurable name, description, icon and bonus; global
  ranking derived from points, titles and collections.
- Fully editable native presentation: MiniMessage, icon, frame, chat, sound, variables and a
  private preview that grants and rewards nothing.
- Client-scoped achievement toasts through PacketEvents without mutating vanilla advancements,
  plus a bounded reusable hidden fallback for servers without that integration.
- Up to eight ordered console-command rewards with player, title, point and season variables;
  durable selections and unlocks in YAML, SQLite or MySQL.
- Compatibility with `{tag_prefix}`/`{tag_suffix}`, legacy personal tags and anonymous-mode
  concealment.
- Versioned `ChatalystApi`/`TitlesApi` Bukkit service plus an independent compile-time API jar;
  immutable catalog/profile/progress models and typed durable mutations.
- Post-persistence title unlock/equip/unequip/revoke, collection-completion and catalog-change
  events, with actor/cause context and Folia-safe asynchronous progress evaluation.
- Public title, achievement-point and collection PlaceholderAPI values, all blanked in anonymous
  mode; full bilingual integration guide in [Titles and achievements API](titles-api.md).

### Audited premium interface and platform hardening (2026-07-22)

> Documentation-to-diff evidence: [Release audit — 2026-07-22](release-audit-2026-07-22.md).

#### Premium interface system
- All inventory interfaces now share the purple-space title frame, lore rhythm and semantic
  navigation vocabulary (home, back, previous, next, page, preview, refresh and close).
- Administration, staff, market and mail use explicit category hubs instead of presenting every
  action in one dense screen. Runtime/configurable collections paginate and clamp stale pages.
- Configuration version 30 migrates factory cyan/magenta/gold UI accents and punishment templates
  without overwriting owner-customized text or player-selectable colour palettes.

#### Security and reliability audit
- **Bug #19 — sensitive command disclosure:** rule audits, rule Discord alerts and staff `Notify`
  templates now redact every argument of `/login`, `/register`, `/reg`, `/otp` and configured
  sensitive commands. Even `{original_message}` resolves to `/login <redacted>`; the sender-only
  warning remains unchanged. Configurable staff-security mirroring uses the same boundary.
- Rule snapshots, names, regex work, report/mail input, HTTP bodies and network buffers receive
  explicit bounds. Reload clears stale rule-delay state and storage pool changes use a guarded
  candidate handoff.
- **Rules v2.1 audit:** `Strip_Accents` and UUID masking now remain search-only and project matches
  onto the original Unicode message; `@prolong` accepts one full Unicode code point; strict imports
  deduplicate rule/file names; linked online DiscordSRV authors reach the `DISCORD` rule context;
  and `Rewrite_In_World` fires the public replace event.
- Player commands run through the sender's entity scheduler and permissions. Remote rule-console
  packets now require an exact backend target plus sender/receiver opt-in and label allowlists;
  timestamp/event deduplication prevents double execution in `both` mode, controls are rejected and
  accepted work is audited with credential redaction.
- The cumulative whole-plugin review closed 25 defects. Regression coverage spans storage,
  market/escrow, proxy/Redis, Folia ownership, anonymity, localization and GUI authorization.

### Floating nametags: title, icons and animation (2026-07-21)

> Full documentation in [Floating Nametags](nametags.md).

#### Fancy nametags
- **Title line** above the tag (e.g. "REY DE TODO"): a second floating, background-less
  `TextDisplay`. Set per player with `/nametagtitle title <text|off>` (aliases `/ntitle`,
  `/nametagicon`), or per rank via `Title.Default_Format` pointed at a LuckPerms/PAPI value.
  An empty title removes the line entirely.
- **Side icons** `{nametag_icon_left}` / `{nametag_icon_right}` flanking the tag (e.g. a
  guardian shield), per player, ItemsAdder-friendly: `/nametagtitle iconleft :shield:`.
- **Animation** (`Nametag.Fancy.Animation`): `frames` cycles a list of frames (alternating
  colours/text/icons); `wave` slides a gradient across the text. It now runs continuously
  on the floating tag and TAB (built-in or TAB API), while chat safely captures the current
  frame per new message. Separate `Interval_Ticks` / `Tab_Interval_Ticks` control the cost.
- The same frame-cycling idea is now available as the `Frames` preset in `/announce`, with
  its own `Announcer.Animation.Frames` templates (full MiniMessage gradients supported),
  tick interval, private preview and network delivery.
- New placeholders `{nametag_title}`, `{nametag_icon_left}`, `{nametag_icon_right}`, blanked
  while a player is in `/anonymous` (a title or icon would unmask the streamer).
- Command text passes the same defences as `/nick` and `/tag` (invisible-char stripping,
  colours behind `chatalyst.nametagtitle.color`, swear/rules, `<click>`/`<hover>` blocked).

### Container surveillance, shared inventories and anonymous mode (2026-07-20)

> Still unpublished: the plugin remains 1.0.0. Full documentation in
> [Container Log, Inventory Sharing and Anonymous Mode](spy-anonymous.md).

#### Container and mechanism log
- New granular spy type `container`: chest/shulker/ender chest opens, container
  place/break, mechanism use, crafting, enchanting and villager trades, live and with
  exact coordinates.
- Repeat anti-spam (`Repeat_Seconds`, 60s): the same action on the same target groups
  into one line reporting how many were hidden ("+19 more"). Moving targets (villagers,
  minecarts) group by entity instead of coordinates.
- **[View]** button on every open: shows the container's contents read-only without
  travelling (current contents, clickable shulkers; for an ender chest, the player's).
  Permission `chatalyst.containerlog.peek`.
- `/containerlog` (`/contlog`, `/cofres`): recent or per-date history with **[Go]**
  (exact block) and **[TP]** (player) buttons. Its own button on the `/staff` dashboard.
- Persisted as type `CONTAINER` in the daily logs (`Log.Containers`), also visible via
  `/chatlog container`. Config under `Spy.Containers` (players.yml).

#### Spy
- The spy supports **per-player** scope: `/spy <player>` watches only them (stackable,
  offline players included), `/spy all` returns to global. It applies to every type (PMs,
  commands, signs, books, anvils, containers) and the spy menu shows who you watch.
- Removing the LAST watched player no longer silently switches you to global spy: if that
  target turned the spy on, it turns off; if the spy was already global before, it returns
  to global. The message says which happened.
- Wired into the GUIs: a **Watch with spy** button in `/players` → manage, and in the
  `/staff` dashboard the spy button shows state and scope, with right-click to toggle
  without leaving.

#### Inventories
- Read-only `/invsee` and `/echest` views open shulkers with a click, and edit sessions
  with a **right-click** (left-click keeps moving items).
- Bottom-row guide in the invsee editor (equipped armor and off hand are editable).
- `/clearinventory <player> [all|ender]` (`/clearinv`, `/ci`, `/limpiarinv`): clears
  storage, equipped armor, off hand, cursor and crafting grid; optionally the ender
  chest. Folia-safe and audited.
- Chat tokens `[inv]` and `[ec]`: any player shares a live read-only view with an "Open"
  button valid for 5 minutes (opaque token, never the UUID).

#### Vanilla messages
- New `Vanilla_Messages` (chat.yml): Minecraft's plain command feedback (`/tp`, `/xp`,
  `/gamemode`, `/give`, `/kill`, `/effect`, `/time`...) is restyled with the plugin's look
  in each client's own language. 28 keys covered, templates editable in
  `lang/<locale>.yml` under `vanilla:` (empty = keep the vanilla one). Requires
  PacketEvents.

#### Streamer anonymous mode
- `/anonymous` (`/anon`, `/anonimo`): generated alias that looks like a real player
  (`ShadowWolf42`, `ItzPanda`...; `confusing` style for the unreadable ones) that never
  impersonates a server member, a real-skin pool (`Anonymous.Skins`) with a different one
  per session, rank/tags/party blanked in every format (including PlaceholderAPI ones
  like `%luckperms_prefix%`) and GUI heads disguised with the session skin. Persists
  the personal chat color (`/chatcolor`) is blanked too. The identity is STABLE within a
  connection (off + on returns the same alias and skin) and renews on reconnect
  (`Rotate_Name_On_Join`); `/nick` is locked while disguised.
- Anonymity also covers death messages, market announcements, the `/players` list, the
  social menu and the Discord webhook bridge. Staff keeps `/realname`, `/seen`, logs and
  the audit trail with the real name.

#### Fixes
- The QUIT message announced the real name of anyone using `/anonymous` (or `/nick`): the
  nickname cache was released too soon, before the goodbye (which is composed a couple of
  ticks *after* the disconnect, for per-viewer language) was built. The cache is now held
  60 ticks — past the compose window and a same-second reconnect — with a guard so a fresh
  session's alias is never wiped by the old session's cleanup. The server, Discord and any
  other plugin's goodbye now see the alias.
- The join alias flickered through 2–3 names: `loadPlayer` briefly applied the *previous*
  session's alias before the anonymous rotation replaced it — which also visually linked
  the two identities. Rotating players now skip that step and get their new alias directly.
- The rotated alias could randomly land on the **same name as last session**; it now
  excludes the previous alias from the draw.
- The marriage hover row (`Marriage.Tag`) was the one line that kept a fixed purple `»`
  while rank/party/ping moved to the rank colour, because it lives in `players.yml`, which
  the rank-hover migration never touched. It now tints by rank (heart removed) and has its
  own migration — correctly gated so it reaches configs already sealed at the previous
  version, and idempotent, leaving customised tags alone. Covered by a regression test.
- The **anonymous audit trail** (`Log.Anonymous`, on) records the real name → alias
  mapping in the daily log only — never console or chat — so staff can trace a disguise
  after the fact without unmasking it live.
- The type filter of `/chatlog <date>` now applies while reading the file (a minority
  type could previously come back empty on chat-heavy days).
- The `/players` list no longer shows the real name behind a nickname to viewers without
  the `/realname` permission.
- Worlds with spaces in their name no longer break the container history or its teleport.
- Unified GUI styling (brand-styled titles, consistent bold on punish actions, yellow
  navigation).

### Staff suite overhaul (2026-07-16)

> Not yet published anywhere: the plugin still ships as 1.0.0. This section becomes the
> next version's notes on release.

**Breaking for new installs:** the staff suite now ships **disabled**
(`Staff_Moderation.Enabled: false`) so Chatalyst never competes with another staff plugin.
Existing installs keep their saved value. With the suite enabled,
`Command_Priority: true` makes Chatalyst's staff commands answer even if another plugin
registered the same labels (the displaced command is restored if you disable the module).

#### Staff suite
- Staff commands moved out of `plugin.yml` into runtime registration (only while enabled).
- New tools with bilingual aliases: `/invsee`, `/echest`, `/seen`, `/check`, `/tpto`,
  `/tphere` (`/verinv`, `/verender`, `/visto`, `/checar`, `/irhacia`, `/traer`).
- GUI-first moderation: reason presets (`Reason_Presets`) or a chat-typed reason that never
  reaches public chat; ban composer (duration, reason, style, exact preview); offline
  moderation menu; **Moderate** button on report details; staff dashboard player list goes
  straight to the manage menu.
- Freeze v2: works offline (review starts on next login), full immobilization (no walking,
  jumping, camera or mounts), notice shown even to OPs when `Exempt_Ops: false`, chat
  restricted to the freezing staff member (`Freeze.Restrict_Chat`), staff alerts with a
  clickable **[GO TO THEM]**, frozen-players list in the dashboard, left-click a frozen
  player to see the reason, permanent ban on any disconnect (grace default now 3s).
- Live inventory/ender chest editing with `chatalyst.command.invsee.edit` / `echest.edit`
  (includes armor and offhand; changes apply on close, everything audited).
- Punishment history per player (last 20: ban, tempban, kick, freeze, mute, FreezeGuard
  ban) shown by `/check` and the offline menu.
- Possible-alt hints in `/check` (SHA-256 hashed connections only; `Track_Alts`,
  permission `chatalyst.staff.alts`), plus an alts menu: one head per account sharing the
  connection, click opens that alt's offline moderation menu. Shared connection is a hint,
  never proof — the menu only inspects, it never punishes in bulk.
- `/warn` (aliases `/amonestar`, `/advertir`): manual warnings that add configurable
  warning points (`Moderation.Manual_Warn_Points`) and land in the punishment history.
  `chatalyst.bypass.warn` protects staff from non-operator warns.
- Automatic escalation (`Escalation`): crossing `Kicks_For_Ban` recent kicks (within
  `Kick_Window`) or `Warn_Points_For_Ban` warning points escalates to an automatic ban
  with a localized default reason, announced to staff and recorded in the history. The ban
  duration steps up a `Ban_Durations` ladder (`[7d, 30d, perm]`) — each repeat offender
  gets a harsher ban. Applying a ban clears the kick counter (kicks before it no longer
  count), so an unban is a clean slate and the next ban comes only from new kicks/points.
- Staff notes: `/note` (alias `/nota`) attaches shared staff memory to a player
  ("verbally warned, next one is a mute") — works offline, capped per player, with a
  read/add/delete menu (permission `chatalyst.staff.notes`). Notes are not punishments
  and never show to the player.
- Mute composer: the same experience as the ban composer — duration presets
  (`Mute.Gui_Durations`), preset or chat-typed reasons with styles, exact preview — plus a
  shadow-mute toggle. Mutes without a typed reason get a localized default reason.
- Punishment history menu: the full read-only history per player (the chat version in
  `/check` only shows the last 3), newest first.

- Connection (IP) bans: `/banip` + `/unbanip` (aliases `/banearip`, `/desbanearip`) ban the
  account and its SHA-256-hashed connection, kick everyone sharing it, auto-ban new
  accounts joining through it (`Ip_Ban.Ban_New_Accounts`) and alert staff (IpGuard). The
  composer gets an "Account + connection" scope toggle. No raw IP is ever stored.
- Ban screen v2: `{remaining}` (full localized breakdown — years, months, days, hours,
  minutes, recalculated on every login attempt) and `{expires}` (exact date and time)
  placeholders, with a new default screen design.

#### Swear filter
- Anti-evasion v2: on top of repetitions, separators and leet speak, the filter now folds
  accents (`pútà`), Cyrillic homoglyphs, common phonetic substitutions (`k` for `c`, `v`
  for `u`/`b`: `pvto`, `kabron`) and fancy Unicode alphabets via NFKC (ｆｕｌｌｗｉｄｔｈ,
  𝓼𝓬𝓻𝓲𝓹𝓽, ⓒⓘⓡⓒⓛⓔⓓ). camelCase transitions count as word boundaries
  ("LaPutaDeTuMadre" detects "Puta" without flagging "Computadora"), and a new `Contains`
  list catches unambiguous glued phrases ("laputadetumadre"), censoring the whole token.
  The whitelist is folded the same way, so legitimate words stay safe.
- Account-name alerts: when a joining player's account name trips the filter, staff with
  `chatalyst.notify.security` get an alert. Nothing is blocked automatically — a false
  positive in a name would ban a legitimate player, so the call stays with staff.
- Old `swear.yml` files keep receiving the packaged defaults for keys they don't have yet
  (no need to regenerate the file).

#### Changes
- Audit logging now ships **enabled by default**, with new switches: `Swear` records what
  the filter censored or blocked (with the original text), `Discord` records messages
  relayed from Discord into the game chat.
- A frozen player's only channel is now the private review chat (plus `/support` if the
  reviewer is offline): `msg` and `reply` left `Freeze.Allowed_Commands` so nothing leaves
  the review.

#### Fixes
- Plain `/staff` now opens the clickable dashboard directly instead of silently entering
  staff mode (which swapped the inventory for the kit and showed no GUI). Enter/leave staff
  mode from the dashboard's mode button, or with `/staff on` / `/staff off`.
- Entering staff mode from the dashboard's mode button now closes the menu first, so the
  kit is placed and rendered fully — before, the tools were written behind the open menu
  and the client sometimes showed an incomplete kit until relog.
- Market and trade now deliver and announce a completed purchase or trade to online
  players instead of leaving the goods silently in the `/market claim` stash: the buyer
  (and either side of a barter/trade) is told what they picked up, or that their inventory
  is full and items are waiting.
- The claim stash now has a cap (`Market.Max_Stash`, default 54 item stacks). Purchases go
  to the inventory first and overflow to the stash; once the stash is at the cap, new
  buys and trades are refused with a "claim your items first" message, so goods are never
  silently dropped. The stash itself never hard-rejects a delivery (items already committed
  are always kept) — the cap is a gate at buy/trade time.
- `Ops_See_Vanished: false` now really works: the `chatalyst.seevanished` node default is
  flipped at runtime (Bukkit's `isPermissionSet` counts `default: op` for OPs).
- Vanished players no longer appear in the server-list ping counter or hover sample
  (`Vanish.Hide_From_Ping`).
- Mute buttons in the manage menu now pick preset/custom reasons like kick/ban/freeze.
