package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.CommandNames;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mandatory Discord account linking.
 *
 * <p>The gate holds a player who has not linked yet: chat, commands and — optionally —
 * movement and interaction are refused until {@code /discord link} completes, and an
 * administrator may additionally kick after a timeout.
 *
 * <p>It is deliberately <b>fail-open</b>. Enforcement only happens while the whole chain is
 * genuinely connected: the bot enabled and configured, linking enabled, the gateway session
 * READY and the configured guild resolvable. If Discord goes down — token revoked, outage,
 * network loss — nobody can be locked out of the game for something they cannot do, and any
 * player already held is released with an explanation on the next tick of the reminder task.
 */
public final class DiscordLinkGate implements Listener {

    private static final long TICK_INTERVAL_TICKS = 20L;
    /** How long the gate stays down after the code store refused a write. */
    private static final long STORAGE_FAILURE_GRACE_MILLIS = 120_000L;

    private final ChatalystPlugin plugin;
    private final DiscordLinkManager links;
    private final AuthHook auth;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    /** Joined but still at the auth plugin's login screen; arming them would fight it. */
    private final Set<UUID> awaitingAuth = ConcurrentHashMap.newKeySet();
    /** Only_Buyers: la respuesta del reconciliador, que recorre todas las compras. */
    private final OwedLinkCache owed = new OwedLinkCache();

    private volatile Settings settings = Settings.disabled();
    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask ticker;
    private final AtomicBoolean authBound = new AtomicBoolean();
    private volatile long storageFailedAt;

    public DiscordLinkGate(ChatalystPlugin plugin, DiscordLinkManager links) {
        this.plugin = plugin;
        this.links = links;
        this.auth = new AuthHook(plugin);
        links.observe(this::onLinkChanged);
    }

    // ---- lifecycle ----------------------------------------------------------

    public synchronized void start() {
        settings = Settings.load(plugin);
        if (settings.enabled() && settings.afterAuthLogin() && authBound.compareAndSet(false, true)) {
            // Registered once per plugin lifetime: Bukkit has no unregister for a single
            // dynamic handler, and re-binding on every /chatalyst reload would stack them.
            auth.bind(this, settings.authLoginEvent(), this::onAuthenticated);
        }
        startTicker();
    }

    public synchronized void reload() {
        settings = Settings.load(plugin);
        owed.clear();
        if (!settings.enabled()) releaseAll("discord.gate-disabled");
        startTicker();
    }

    public synchronized void stop() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask current = ticker;
        ticker = null;
        if (current != null) current.cancel();
        held.clear();
        awaitingAuth.clear();
        owed.clear();
    }

    private void startTicker() {
        if (ticker != null) return;
        try {
            ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin, task -> tick(), TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
        } catch (RuntimeException rejected) {
            // Plugin disabled between the check and the enqueue; nothing is held yet.
            ticker = null;
        }
    }

    // ---- enforcement conditions ---------------------------------------------

    /**
     * True only when a player can realistically complete the link right now. Every clause is
     * a reason NOT to hold anyone hostage: a disabled bot, a missing token, an outage or a
     * guild the bot was kicked from would otherwise leave the server unplayable.
     */
    public boolean enforceable() {
        Settings current = settings;
        if (!current.enabled()) return false;
        // A gateway that is up is not enough: if codes cannot be persisted, the player has no
        // way to satisfy the requirement either, and holding them would be just as unfair as
        // holding them through a Discord outage.
        if (System.currentTimeMillis() - storageFailedAt < STORAGE_FAILURE_GRACE_MILLIS) {
            return false;
        }
        DiscordBot bot = plugin.getDiscordBot();
        return bot != null && bot.linkingAvailable();
    }

    /** Records that the code store refused a write, so the gate stands down for a while. */
    private void noteStorageFailure(Throwable failure) {
        storageFailedAt = System.currentTimeMillis();
        plugin.getLogger().warning("Discord link codes cannot be stored ("
                + (failure == null ? "storage refused the write" : failure)
                + "); mandatory linking stands down until it recovers.");
    }

    public boolean isHeld(Player player) {
        return player != null && held.containsKey(player.getUniqueId());
    }

    /** True when this player must link before playing (used by /discord unlink). */
    public boolean isRequiredFor(Player player) {
        return enforceable() && player != null && !exempt(player) && owesALink(player);
    }

    /**
     * Whether THIS player is one the gate is meant for.
     *
     * <p>With {@code Only_Buyers} the answer is "only if something they paid for is stuck".
     * A purchase is never lost by not linking — the reconciler keeps retrying forever, and the
     * role lands the day they link — so holding the whole server to solve a problem only
     * buyers have is a toll on everyone to fix nobody's actual complaint. The buyer, though,
     * has a rank they paid for that cannot reach Discord until they link, and that IS worth
     * stopping for.
     */
    private boolean owesALink(Player player) {
        // El default es TRUE, igual que en la config de fabrica y en el doctor. Ausente, esta
        // clave decide entre retener a un comprador o retener el servidor entero, asi que no
        // puede significar cosas distintas segun quien la lea: con false aqui, una config a la
        // que le faltara la clave retenia a todo el mundo mientras el diagnostico informaba de
        // lo contrario — y el sitio donde se nota es la puerta de entrada al servidor.
        if (!plugin.getConfig().getBoolean(
                "Discord.Linking.Required.Only_Buyers", true)) {
            return true;
        }
        if (links.discordId(player.getUniqueId()) != null) return false; // already linked
        var reconciler = plugin.getTebexReconciler();
        return reconciler != null
                && owed.owes(player.getUniqueId(), reconciler::owesDiscordDelivery);
    }

    private boolean exempt(Player player) {
        String permission = settings.bypassPermission();
        return !permission.isBlank() && player.hasPermission(permission);
    }

    // ---- arming and releasing -----------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Settings current = settings;
        if (!current.enabled()) return;
        UUID id = event.getPlayer().getUniqueId();
        owed.forget(id); // lo que debia la ultima vez pudo cambiar mientras no estaba
        // With a bound auth plugin the player is still at the login screen: arming here would
        // fight its own restrictions. onAuthenticated takes over instead.
        if (auth.isActive()) {
            awaitingAuth.add(id);
            return;
        }
        // An auth plugin that could not be bound still deserves time for /login to happen.
        long delayTicks = current.afterAuthLogin() && anyAuthPluginInstalled()
                ? Math.max(1L, current.authFallbackSeconds() * 20L)
                : Math.max(1L, current.announceDelayTicks());
        awaitingAuth.add(id);
        FoliaTasks.globalLater(plugin, () -> {
            awaitingAuth.remove(id);
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) arm(online);
        }, delayTicks);
    }

    private void onAuthenticated(Player player) {
        Settings current = settings;
        UUID id = player.getUniqueId();
        if (!current.enabled()) {
            awaitingAuth.remove(id);
            return;
        }
        FoliaTasks.globalLater(plugin, () -> {
            awaitingAuth.remove(id);
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) arm(online);
        }, Math.max(1L, current.announceDelayTicks()));
    }

    private boolean anyAuthPluginInstalled() {
        var manager = plugin.getServer().getPluginManager();
        for (String name : List.of("AuthMe", "nLogin", "LibreLogin", "OpeNLogin", "JPremium")) {
            var candidate = manager.getPlugin(name);
            if (candidate != null && candidate.isEnabled()) return true;
        }
        return false;
    }

    private void arm(Player player) {
        if (!enforceable() || exempt(player)) return;
        // Never hold someone who is not allowed to run the command that would free them.
        if (!player.hasPermission("chatalyst.command.discord")) return;
        UUID id = player.getUniqueId();
        if (links.discordId(id) != null) return;
        if (!owesALink(player)) return;
        Settings current = settings;
        long deadline = current.kickAfterSeconds() > 0
                ? System.currentTimeMillis() + current.kickAfterSeconds() * 1_000L
                : 0L;
        // The first reminder is scheduled a full interval away: the banner below already
        // says everything, and repeating it one second later would just read as spam.
        Held state = new Held(deadline,
                System.currentTimeMillis() + current.reminderSeconds() * 1_000L);
        if (held.putIfAbsent(id, state) != null) return;
        // With Auto_Code the banner waits for the real code, so the player is not shown the
        // "ask for a code" variant one second before the code itself arrives.
        FoliaTasks.entity(plugin, player,
                current.autoCode() ? this::requestCode : this::announce);
    }

    /** Releases a held player, optionally telling them why. */
    private void release(UUID id, String reasonKey) {
        if (held.remove(id) == null) return;
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || reasonKey == null) return;
        FoliaTasks.entity(plugin, player, online -> {
            online.sendMessage(Lang.get(online, reasonKey));
            online.sendActionBar(Component.empty());
            online.clearTitle();
        });
    }

    private void releaseAll(String reasonKey) {
        for (UUID id : List.copyOf(held.keySet())) release(id, reasonKey);
    }

    /** Observer on the durable link store: fires on any backend of a shared network. */
    private void onLinkChanged(UUID minecraftId, String discordId) {
        // Unlinking (discordId == null) needs no action here: the reminder task re-arms the
        // player on its next pass, with the same fail-open checks as a fresh join.
        owed.forget(minecraftId);
        if (minecraftId != null && discordId != null) celebrateLink(minecraftId);
    }

    private void celebrateLink(UUID minecraftId) {
        if (held.remove(minecraftId) == null) return;
        Player player = plugin.getServer().getPlayer(minecraftId);
        if (player == null) return;
        FoliaTasks.entity(plugin, player, online -> {
            online.sendActionBar(Component.empty());
            online.clearTitle();
            online.sendMessage(Lang.get(online, "discord.gate-linked"));
            online.showTitle(Title.title(
                    Lang.get(online, "discord.gate-linked-title"),
                    Lang.get(online, "discord.gate-linked-subtitle"),
                    Title.Times.times(Duration.ofMillis(200),
                            Duration.ofSeconds(2), Duration.ofMillis(600))));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        held.remove(id);
        awaitingAuth.remove(id);
        owed.forget(id);
        links.forget(id);
    }

    // ---- periodic reminder / kick / fail-open release ------------------------

    private void tick() {
        if (!enforceable()) {
            // The bridge died while people were held. Never keep the server unplayable.
            if (!held.isEmpty()) releaseAll("discord.gate-released");
            return;
        }
        Settings current = settings;
        long now = System.currentTimeMillis();
        for (var entry : Map.copyOf(held).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                held.remove(entry.getKey());
                continue;
            }
            Held state = entry.getValue();
            if (links.discordId(entry.getKey()) != null) {
                celebrateLink(entry.getKey());
                continue;
            }
            if (state.deadline() > 0L && now >= state.deadline()) {
                held.remove(entry.getKey());
                FoliaTasks.entity(plugin, player, online -> online.kick(MM.full(
                        String.join("<newline>", Lang.rawList(online, "discord.gate-kick")))));
                continue;
            }
            if (now < state.nextReminder()) continue;
            state.scheduleReminder(now + current.reminderSeconds() * 1_000L);
            // A reminder is one compact line plus the action bar; re-sending the whole banner
            // every Reminder_Seconds would bury everything else the player is reading.
            FoliaTasks.entity(plugin, player, online -> {
                online.sendActionBar(Lang.get(online, "discord.gate-actionbar"));
                online.sendMessage(MM.full(codeLine(online)));
                if (state.deadline() > 0L) {
                    long left = Math.max(0L, (state.deadline() - System.currentTimeMillis())
                            / 1_000L);
                    online.sendMessage(Lang.get(online, "discord.gate-kick-warning",
                            "seconds", left));
                }
            });
        }
        armMissedPlayers();
    }

    /**
     * Arms players who joined while the gateway was still connecting. Without this, everyone
     * online during a slow bot login (or a {@code /chatalyst reload}) would stay unlinked and
     * unrestricted for the rest of their session.
     */
    private void armMissedPlayers() {
        if (!enforceable()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!held.containsKey(id) && !awaitingAuth.contains(id)) arm(player);
        }
    }

    // ---- presentation --------------------------------------------------------

    /** The "your code is X" line, or the "ask for one" fallback when none was issued yet. */
    private String codeLine(Player player) {
        DiscordBot bot = plugin.getDiscordBot();
        Held state = held.get(player.getUniqueId());
        String code = state == null ? "" : state.code();
        return code.isBlank()
                ? Lang.raw(player, "discord.gate-code-pending")
                : Lang.raw(player, "discord.gate-code", "code", code,
                        "command", bot == null ? "!link" : bot.linkCommand());
    }

    private void announce(Player player) {
        Settings current = settings;
        DiscordBot bot = plugin.getDiscordBot();
        String command = bot == null ? "!link" : bot.linkCommand();
        String codeLine = codeLine(player);
        for (String line : Lang.rawList(player, "discord.gate-lines",
                "player", player.getName(), "command", command,
                "minutes", Math.max(1L, links.codeExpirySeconds() / 60L))) {
            player.sendMessage(MM.full(line.replace("{code_line}", codeLine)));
        }
        player.showTitle(Title.title(
                Lang.get(player, "discord.gate-title"),
                Lang.get(player, "discord.gate-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4),
                        Duration.ofMillis(600))));
        if (current.sound() != null) {
            player.playSound(dev.chatalyst.util.SoundUtil.sound(
                    current.sound(), "minecraft:entity.experience_orb.pickup", 0.7f, 1.4f));
        }
    }

    /** Issues a code for a held player and re-announces it once storage answers. */
    private void requestCode(Player player) {
        UUID id = player.getUniqueId();
        links.createCode(id, player.getName()).whenComplete((created, failure) -> {
            Held state = held.get(id);
            if (state == null) return;
            if (failure == null && created != null
                    && created.status() == DiscordLinkManager.Status.SUCCESS) {
                state.code(created.code());
            } else if (failure != null) {
                // Rate limiting is normal and returns a status; the store being unable to
                // reserve a code fails the future, and leaves the player with no route out.
                noteStorageFailure(failure);
            }
            // Rate limited or a storage hiccup still gets the banner, pointing at
            // /discord link, so the player is never left staring at a silent block.
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) FoliaTasks.entity(plugin, online, this::announce);
        });
    }

    // ---- restrictions --------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!settings.restrictChat() || !isHeld(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Lang.get(event.getPlayer(), "discord.gate-chat-blocked"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Settings current = settings;
        if (!current.restrictCommands() || !isHeld(event.getPlayer())) return;
        if (CommandNames.matches(event.getMessage(), current.allowedCommands())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(
                Lang.get(event.getPlayer(), "discord.gate-command-blocked"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!settings.restrictMovement() || !isHeld(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // looking around stays free; only leaving the block is refused
        }
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        event.setTo(back);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (settings.restrictInteraction() && isHeld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventory(InventoryOpenEvent event) {
        if (settings.restrictInteraction() && event.getPlayer() instanceof Player player
                && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (settings.restrictInteraction() && isHeld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (settings.restrictInteraction() && isHeld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (settings.restrictInteraction() && isHeld(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Coger cosas del suelo es interaccion: un retenido no saquea mientras no vincula. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (settings.restrictInteraction() && event.getEntity() instanceof Player player
                && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * A held player who cannot flee must not be farmed while stuck. The protection only
     * applies while movement is restricted: a held player who may walk anywhere and cannot be
     * hurt was free to explore the Nether or a PvP zone with no risk, for as long as they liked.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Settings current = settings;
        if (!current.protectFromDamage()) return;
        if (current.restrictMovement()
                && event.getEntity() instanceof Player victim && isHeld(victim)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player attacker && isHeld(attacker)) {
            event.setCancelled(true);
        }
    }

    // ---- settings ------------------------------------------------------------

    private static final class Held {
        private final long deadline;
        private volatile String code = "";
        private volatile long nextReminder;

        Held(long deadline, long firstReminder) {
            this.deadline = deadline;
            this.nextReminder = firstReminder;
        }

        long deadline() {
            return deadline;
        }

        String code() {
            return code;
        }

        void code(String value) {
            this.code = value == null ? "" : value;
        }

        long nextReminder() {
            return nextReminder;
        }

        void scheduleReminder(long at) {
            this.nextReminder = at;
        }
    }

    record Settings(
            boolean enabled,
            String bypassPermission,
            boolean afterAuthLogin,
            String authLoginEvent,
            long authFallbackSeconds,
            long announceDelayTicks,
            long reminderSeconds,
            long kickAfterSeconds,
            boolean autoCode,
            boolean restrictChat,
            boolean restrictCommands,
            boolean restrictMovement,
            boolean restrictInteraction,
            boolean protectFromDamage,
            List<String> allowedCommands,
            String sound) {

        static Settings disabled() {
            return new Settings(false, "chatalyst.discord.exempt", true, "", 20L, 20L, 45L,
                    0L, true, true, true, false, true, true, List.of("/discord"), null);
        }

        static Settings load(ChatalystPlugin plugin) {
            var config = plugin.getConfig();
            String base = "Discord.Linking.Required.";
            String sound = config.getString(base + "Sound", "").trim();
            return new Settings(
                    config.getBoolean(base + "Enabled", false)
                            && config.getBoolean("Discord.Enabled", false)
                            && config.getBoolean("Discord.Linking.Enabled", true),
                    config.getString(base + "Bypass_Permission",
                            "chatalyst.discord.exempt").trim(),
                    config.getBoolean(base + "After_Auth_Login", true),
                    config.getString(base + "Auth_Login_Event", "").trim(),
                    clamp(config.getLong(base + "Auth_Fallback_Seconds", 20L), 1L, 600L),
                    clamp(config.getLong(base + "Announce_Delay_Ticks", 20L), 1L, 1_200L),
                    clamp(config.getLong(base + "Reminder_Seconds", 45L), 5L, 3_600L),
                    clamp(config.getLong(base + "Kick_After_Seconds", 0L), 0L, 86_400L),
                    config.getBoolean(base + "Auto_Code", true),
                    config.getBoolean(base + "Restrict.Chat", true),
                    config.getBoolean(base + "Restrict.Commands", true),
                    config.getBoolean(base + "Restrict.Movement", false),
                    config.getBoolean(base + "Restrict.Interaction", true),
                    config.getBoolean(base + "Restrict.Damage", true),
                    normalizeCommands(config.getStringList(base + "Allowed_Commands")),
                    sound.isEmpty() ? null : sound);
        }

        /**
         * {@code /discord} is always allowed: forbidding it would make the requirement
         * impossible to satisfy no matter how the administrator edits the list.
         */
        static List<String> normalizeCommands(List<String> configured) {
            java.util.LinkedHashSet<String> commands = new java.util.LinkedHashSet<>();
            commands.add("/discord");
            for (String value : configured) {
                if (value == null || value.isBlank()) continue;
                String label = value.trim().toLowerCase(Locale.ROOT);
                commands.add(label.startsWith("/") ? label : "/" + label);
            }
            return List.copyOf(commands);
        }

        private static long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
