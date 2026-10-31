package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LuckPerms/Discord rank synchronization.
 *
 * <p>Every rank in {@code Rank_Catalog} is mirrored on its own: a player holds the Discord role
 * of each catalog group they hold directly in LuckPerms (with {@code Exclusive_Discord_Roles}
 * only the highest one). Minecraft is the source of truth: LuckPerms events push changes as
 * they happen, and a periodic pass over every linked account catches what no event reports —
 * a rank changed from the proxy or another server, an offline player, a temporary rank that
 * expired.
 *
 * <p>Discord to Minecraft works on the roles that CHANGED in each gateway event, never on the
 * member's whole role list, and only for ranks whose {@code Discord_Control} allows it. Role
 * writes made by this bot are announced beforehand and ignored when their event comes back.
 */
public final class DiscordRankSync {

    /** How long a role write by this bot is expected to come back as a gateway event. */
    private static final long BOT_CHANGE_WINDOW_MILLIS = 30_000L;
    private static final long LOOP_SUPPRESSION_MILLIS = 10_000L;

    private final ChatalystPlugin plugin;
    private final DiscordLinkManager links;
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> suppressedMinecraft = new ConcurrentHashMap<>();
    private final Map<String, Long> expectedBotChanges = new ConcurrentHashMap<>();

    private volatile DiscordBot bot;
    private volatile Settings settings = Settings.disabled();
    private volatile AutoCloseable luckPermsSubscription;
    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask reconcileTask;
    private volatile java.util.function.Function<UUID, Set<String>> paidGroups =
            ignored -> Set.of();

    /**
     * Declares which LuckPerms groups a player holds because they PAID for them, so Discord
     * can never take those away.
     *
     * <p>Without this, losing a Discord role (or leaving the guild) stripped a purchased rank
     * and the Tebex reconciler put it straight back — two systems writing opposite things to
     * LuckPerms over the same group. Excluding paid groups here removes the disagreement at
     * its source instead of relying on one side to undo the other.
     */
    public void protectPaidGroups(java.util.function.Function<UUID, Set<String>> supplier) {
        if (supplier != null) this.paidGroups = supplier;
    }

    public DiscordRankSync(ChatalystPlugin plugin, DiscordLinkManager links) {
        this.plugin = plugin;
        this.links = links;
        links.observeUnlink(this::onUnlinked);
    }

    /**
     * Una cuenta de Discord que deja de estar vinculada (desvinculada, o sustituida por otra
     * al re-vincular) pierde los roles de rango gestionados. Si no, cada cuenta por la que
     * pasara el jugador se quedaba con su VIP para siempre: la sincronia solo mira la actual.
     */
    private void onUnlinked(UUID minecraftId, String discordId) {
        Settings current = settings;
        DiscordBot active = bot;
        if (!current.enabled() || !current.direction().toDiscord() || active == null) return;
        active.applyDiscordRoles(minecraftId, discordId, Set.of(), current.managedRoleIds())
                .exceptionally(failure -> false);
    }

    public void bindBot(DiscordBot bot) {
        this.bot = bot;
    }

    public synchronized void start() {
        reload();
    }

    public synchronized void reload() {
        closeSubscription();
        cancelReconcile();
        settings = Settings.load(plugin);
        if (!settings.enabled()) return;
        if (!LuckPermsHook.isAvailable()) {
            plugin.getLogger().warning("Discord rank sync is enabled, but LuckPerms is not"
                    + " installed. Chat, reports and the Discord bot remain available.");
            return;
        }
        if (settings.groupsToRoles().isEmpty()) {
            plugin.getLogger().warning("Discord rank sync is enabled without any"
                    + " valid Rank_Catalog role mappings; no ranks will be changed.");
            return;
        }
        if (settings.direction().toDiscord()) {
            luckPermsSubscription = LuckPermsHook.subscribeUserChanges(
                    plugin, this::scheduleMinecraftToDiscord);
            long minutes = settings.reconcileMinutes();
            if (minutes > 0) {
                try {
                    reconcileTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                            plugin, task -> reconcileAll(), minutes, minutes, TimeUnit.MINUTES);
                } catch (RuntimeException rejected) {
                    reconcileTask = null;
                }
            }
        }
    }

    public synchronized void stop() {
        closeSubscription();
        cancelReconcile();
        generations.clear();
        suppressedMinecraft.clear();
        expectedBotChanges.clear();
    }

    public boolean requiresMemberIntent() {
        Settings current = settings;
        return current.enabled() && current.direction().toMinecraft();
    }

    public void onBotReady() {
        Settings current = settings;
        if (!current.enabled() || !current.syncOnReady()) return;
        int limit = current.readyLimit();
        int count = 0;
        for (var link : links.allLinks().entrySet()) {
            if (count++ >= limit) break;
            if (current.initialSource() == InitialSource.DISCORD
                    && current.direction().toMinecraft()) {
                DiscordBot active = bot;
                if (active != null) active.readDiscordRoles(link.getValue());
            } else {
                scheduleMinecraftToDiscord(link.getKey());
            }
        }
    }

    /**
     * Pasada completa Minecraft -&gt; Discord sobre todas las cuentas vinculadas. Cubre lo que
     * LuckPerms no avisa en este servidor: cambios hechos desde Velocity ({@code lpv}) o desde
     * otro backend, jugadores desconectados y rangos temporales que caducaron. Escribir solo
     * cuando algo difiere hace que una pasada sin cambios no cueste ninguna peticion de rol.
     */
    void reconcileAll() {
        Settings current = settings;
        if (!current.enabled() || !current.direction().toDiscord()) return;
        DiscordBot active = bot;
        if (active == null || !active.isReady()) return;
        int limit = current.readyLimit();
        int count = 0;
        for (UUID minecraftId : links.allLinks().keySet()) {
            if (count++ >= limit) break;
            scheduleMinecraftToDiscord(minecraftId);
        }
    }

    public void onLinked(UUID minecraftId, String discordId) {
        Settings current = settings;
        if (!current.enabled()) return;
        if (current.initialSource() == InitialSource.DISCORD
                && current.direction().toMinecraft()) {
            DiscordBot active = bot;
            if (active != null) active.readDiscordRoles(discordId);
        } else if (current.direction().toDiscord()) {
            scheduleMinecraftToDiscord(minecraftId);
        }
    }

    public void scheduleMinecraftToDiscord(UUID minecraftId) {
        Settings current = settings;
        if (minecraftId == null || !current.enabled() || !current.direction().toDiscord()) return;
        if (isSuppressed(suppressedMinecraft, minecraftId)) return;
        AtomicLong generation = generations.computeIfAbsent(
                minecraftId, ignored -> new AtomicLong());
        long expected = generation.incrementAndGet();
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> {
            if (generation.get() != expected) return;
            generations.remove(minecraftId, generation);
            deliverToDiscordNow(minecraftId);
        }, current.debounceMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Pushes this player's current LuckPerms ranks to Discord immediately and reports whether
     * the roles really landed. Durable retry queues need that answer: a paid rank must keep
     * being retried until the role exists, not be assumed delivered because a call was made.
     *
     * @return false when the bot is down, the account is not linked yet, LuckPerms could not
     *         be read, or Discord refused the role write.
     */
    public CompletableFuture<Boolean> deliverToDiscordNow(UUID minecraftId) {
        Settings current = settings;
        if (minecraftId == null || !current.enabled() || !current.direction().toDiscord()) {
            return CompletableFuture.completedFuture(false);
        }
        String discordId = links.discordId(minecraftId);
        DiscordBot active = bot;
        if (discordId == null || active == null || !active.isReady()) {
            return CompletableFuture.completedFuture(false);
        }
        return LuckPermsHook.heldGroupsAsync(minecraftId).thenCompose(held -> {
            Set<String> desired = desiredRoles(current, held);
            return active.applyDiscordRoles(minecraftId, discordId, desired,
                    current.managedRoleIds());
        }).exceptionally(failure -> {
            plugin.getLogger().warning("Could not read LuckPerms ranks for " + minecraftId
                    + ": " + safeFailure(failure));
            return false;
        });
    }

    /**
     * Roles de Discord que corresponden a los grupos que tiene el jugador, en el orden de
     * prioridad del catalogo. En modo exclusivo, solo el primero (el de mas prioridad).
     */
    static Set<String> desiredRoles(Settings current, Set<String> heldGroups) {
        Set<String> desired = new LinkedHashSet<>();
        for (var entry : current.groupsToRoles().entrySet()) {
            if (!heldGroups.contains(entry.getKey())) continue;
            desired.add(entry.getValue());
            if (current.exclusiveDiscordRoles()) break;
        }
        return desired;
    }

    /**
     * A staff member added or removed rank roles in Discord. Only the roles in this event are
     * considered, so a role the member never had cannot be read as "removed".
     */
    public void onDiscordRoleChange(String discordId, Set<String> roleIds, boolean added) {
        Settings current = settings;
        if (!current.enabled() || !current.direction().toMinecraft() || discordId == null) return;
        UUID minecraftId = links.minecraftId(discordId);
        for (String roleId : roleIds) {
            String group = current.rolesToGroups().get(roleId);
            if (group == null) continue; // not a rank role
            if (consumeBotChange(discordId, roleId, added)) continue; // our own write
            if (minecraftId == null) continue; // not linked: nothing to mirror
            Control control = current.controls().getOrDefault(group, Control.FULL);
            if (added) {
                if (control != Control.FULL) continue;
                LuckPermsHook.addGroupIfAbsentAsync(minecraftId, group).whenComplete(
                        (changed, failure) -> report(failure, "give", group, minecraftId));
            } else {
                if (control == Control.NONE) continue;
                Set<String> paid = paidGroups.apply(minecraftId);
                if (paid != null && paid.contains(group)) {
                    // Bought ranks are not Discord's to revoke; the next pass puts the role back.
                    scheduleMinecraftToDiscord(minecraftId);
                    continue;
                }
                LuckPermsHook.removeGroupAsync(minecraftId, group).whenComplete(
                        (changed, failure) -> report(failure, "remove", group, minecraftId));
            }
        }
    }

    private void report(Throwable failure, String action, String group, UUID minecraftId) {
        if (failure == null) return;
        plugin.getLogger().warning("Discord rank sync could not " + action + " group '"
                + group + "' for " + minecraftId + ": " + safeFailure(failure));
    }

    /**
     * Called by an explicit initial Discord-side read ({@code Initial_Source: DISCORD}): the
     * member's whole role list decides their rank, as it did before per-role events.
     */
    public void onDiscordRoles(String discordId, Set<String> roleIds) {
        Settings current = settings;
        if (!current.enabled() || !current.direction().toMinecraft()) return;
        UUID minecraftId = links.minecraftId(discordId);
        if (minecraftId == null) return;

        String desired = "";
        // YAML insertion order is the priority order when a member has multiple mapped roles.
        for (var entry : current.groupsToRoles().entrySet()) {
            if (roleIds.contains(entry.getValue())) {
                desired = entry.getKey();
                break;
            }
        }
        if (desired.isBlank()) {
            if (!current.removeOnDiscordRoleLoss()) return;
            desired = current.fallbackGroup();
        }
        if (!desired.isBlank() && !LuckPermsHook.groupExists(desired)) {
            plugin.getLogger().warning("Discord rank sync ignored unknown LuckPerms group '"
                    + desired + "'.");
            return;
        }

        // Groups the player bought are off limits to Discord: it may add and remove the ranks
        // it manages, but it does not get to revoke something that was paid for.
        Set<String> removable = new LinkedHashSet<>(current.groupsToRoles().keySet());
        Set<String> paid = paidGroups.apply(minecraftId);
        if (paid != null && !paid.isEmpty()) {
            removable.removeAll(paid);
            if (desired.isBlank() || paid.contains(desired)) {
                // Nothing left for Discord to say: the paid rank already decides this.
                return;
            }
        }

        suppressedMinecraft.put(minecraftId,
                System.currentTimeMillis() + LOOP_SUPPRESSION_MILLIS);
        String selected = desired;
        LuckPermsHook.replaceMappedGroupAsync(
                minecraftId, removable, selected)
                .whenComplete((changed, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(changed)) {
                        plugin.getLogger().warning("Could not apply Discord rank to "
                                + minecraftId + ": " + safeFailure(failure));
                    }
                });
    }

    /** The bot is about to add or remove this role; its gateway event must not loop back. */
    public void expectBotChange(String discordId, String roleId, boolean added) {
        if (discordId == null || roleId == null) return;
        expectedBotChanges.put(changeKey(discordId, roleId, added),
                System.currentTimeMillis() + BOT_CHANGE_WINDOW_MILLIS);
    }

    boolean consumeBotChange(String discordId, String roleId, boolean added) {
        Long until = expectedBotChanges.remove(changeKey(discordId, roleId, added));
        if (expectedBotChanges.size() > 1_000) {
            long now = System.currentTimeMillis();
            expectedBotChanges.values().removeIf(deadline -> deadline < now);
        }
        return until != null && until >= System.currentTimeMillis();
    }

    private static String changeKey(String discordId, String roleId, boolean added) {
        return discordId + (added ? "+" : "-") + roleId;
    }

    public void onRankGranted(UUID minecraftId) {
        // Tebex/LuckPerms event delivery normally triggers this too. Scheduling explicitly
        // makes the Discord role converge even if an older LuckPerms build omits the event.
        scheduleMinecraftToDiscord(minecraftId);
    }

    private synchronized void closeSubscription() {
        AutoCloseable old = luckPermsSubscription;
        luckPermsSubscription = null;
        if (old == null) return;
        try {
            old.close();
        } catch (Exception ignored) {
            // LuckPerms may already be shutting down.
        }
    }

    private synchronized void cancelReconcile() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask old = reconcileTask;
        reconcileTask = null;
        if (old != null) old.cancel();
    }

    private static <K> boolean isSuppressed(Map<K, Long> map, K key) {
        if (key == null) return false;
        Long until = map.get(key);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        map.remove(key, until);
        return false;
    }

    private static String normalizeGroup(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeFailure(Throwable failure) {
        if (failure == null) return "operation returned false";
        Throwable shown = failure;
        while (shown.getCause() != null && shown.getCause() != shown) shown = shown.getCause();
        String message = shown.getMessage();
        return message == null || message.isBlank()
                ? shown.getClass().getSimpleName() : message;
    }

    enum Direction {
        MINECRAFT_TO_DISCORD, DISCORD_TO_MINECRAFT, BOTH;

        boolean toDiscord() {
            return this == MINECRAFT_TO_DISCORD || this == BOTH;
        }

        boolean toMinecraft() {
            return this == DISCORD_TO_MINECRAFT || this == BOTH;
        }

        static Direction parse(String value) {
            if (value == null) return BOTH;
            return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "MINECRAFT_TO_DISCORD", "LUCKPERMS_TO_DISCORD" ->
                        MINECRAFT_TO_DISCORD;
                case "DISCORD_TO_MINECRAFT", "DISCORD_TO_LUCKPERMS" ->
                        DISCORD_TO_MINECRAFT;
                default -> BOTH;
            };
        }
    }

    /**
     * What a Discord role edit may do to the LuckPerms group of one rank. Minecraft always
     * pushes to Discord regardless; this only limits the way back.
     */
    enum Control {
        /** Adding the role gives the rank, removing it takes the rank away. */
        FULL,
        /** Removing the role takes the rank away; adding it does nothing in Minecraft. */
        REMOVE,
        /** Discord never changes this rank (owner-level groups). */
        NONE;

        static Control parse(String value) {
            if (value == null) return FULL;
            return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "REMOVE", "REMOVE_ONLY", "QUITAR" -> REMOVE;
                case "NONE", "OFF", "NINGUNO", "NO" -> NONE;
                default -> FULL;
            };
        }
    }

    enum InitialSource {
        MINECRAFT, DISCORD;

        static InitialSource parse(String value) {
            return value != null && value.trim().equalsIgnoreCase("discord")
                    ? DISCORD : MINECRAFT;
        }
    }

    record Settings(
            boolean enabled,
            Direction direction,
            InitialSource initialSource,
            Map<String, String> groupsToRoles,
            Map<String, String> rolesToGroups,
            Map<String, Control> controls,
            Set<String> managedRoleIds,
            boolean exclusiveDiscordRoles,
            boolean removeOnDiscordRoleLoss,
            String fallbackGroup,
            boolean syncOnReady,
            int readyLimit,
            long debounceMillis,
            long reconcileMinutes) {

        static Settings disabled() {
            return new Settings(false, Direction.BOTH, InitialSource.MINECRAFT,
                    Map.of(), Map.of(), Map.of(), Set.of(), true, true, "default", true, 500,
                    350L, 5L);
        }

        static Settings load(ChatalystPlugin plugin) {
            var config = plugin.getConfig();
            // v33 central catalog: one Rank ID owns its LuckPerms group, Discord role and
            // Tebex delivery settings. This prevents the same group being repeated in
            // several unrelated YAML trees.
            ConfigurationSection section =
                    config.getConfigurationSection("Rank_Catalog");
            boolean centralCatalog = section != null && !section.getKeys(false).isEmpty();
            Map<String, String> mappings = new LinkedHashMap<>();
            Map<String, Control> controls = new LinkedHashMap<>();
            Set<String> roles = new LinkedHashSet<>();
            if (section != null) {
                List<RankMapping> configured = new ArrayList<>();
                for (String rankId : section.getKeys(false)) {
                    ConfigurationSection rank = section.getConfigurationSection(rankId);
                    if (rank == null) continue;
                    String group = normalizeGroup(rank.getString(
                            "LuckPerms_Group", rankId));
                    String role = rank.getString("Discord_Role_Id", "").trim();
                    int priority = Math.max(-1_000_000, Math.min(1_000_000,
                            rank.getInt("Discord_Priority", 0)));
                    if (rankId.matches("[a-z0-9_\\-]{1,64}")
                            && group.matches("[a-z0-9_\\-]{1,64}")
                            && DiscordBot.isSnowflake(role)) {
                        configured.add(new RankMapping(
                                rankId.toLowerCase(Locale.ROOT), group, role, priority,
                                Control.parse(rank.getString("Discord_Control", "FULL"))));
                    }
                }
                configured.sort(java.util.Comparator
                        .comparingInt(RankMapping::priority).reversed()
                        .thenComparing(RankMapping::rankId));
                for (RankMapping mapping : configured) {
                    if (!mappings.containsKey(mapping.group())
                            && roles.add(mapping.roleId())) {
                        mappings.put(mapping.group(), mapping.roleId());
                        controls.put(mapping.group(), mapping.control());
                    }
                }
            }
            // Compatibility with the short-lived structured v33 preview and with v32's
            // concise group: role map. Never fall through when a central catalog exists:
            // an invalid/blank catalog must fail closed instead of reviving legacy grants.
            if (!centralCatalog && mappings.isEmpty()) {
                ConfigurationSection structured =
                        config.getConfigurationSection("Discord.Rank_Sync.Mappings");
                if (structured != null) {
                    List<RankMapping> configured = new ArrayList<>();
                    for (String rankId : structured.getKeys(false)) {
                        ConfigurationSection rank =
                                structured.getConfigurationSection(rankId);
                        if (rank == null) continue;
                        String group = normalizeGroup(rank.getString(
                                "LuckPerms_Group", rankId));
                        String role = rank.getString("Discord_Role_Id", "").trim();
                        int priority = Math.max(-1_000_000, Math.min(1_000_000,
                                rank.getInt("Priority", 0)));
                        if (rankId.matches("[A-Za-z0-9_\\-]{1,64}")
                                && group.matches("[a-z0-9_\\-]{1,64}")
                                && DiscordBot.isSnowflake(role)) {
                            configured.add(new RankMapping(
                                    rankId.toLowerCase(Locale.ROOT),
                                    group, role, priority, Control.FULL));
                        }
                    }
                    configured.sort(java.util.Comparator
                            .comparingInt(RankMapping::priority).reversed()
                            .thenComparing(RankMapping::rankId));
                    for (RankMapping mapping : configured) {
                        if (!mappings.containsKey(mapping.group())
                                && roles.add(mapping.roleId())) {
                            mappings.put(mapping.group(), mapping.roleId());
                        }
                    }
                }
            }
            if (!centralCatalog && mappings.isEmpty()) {
                ConfigurationSection legacy =
                        config.getConfigurationSection("Discord.Rank_Sync.Groups");
                if (legacy != null) {
                    for (String rawGroup : legacy.getKeys(false)) {
                        String group = normalizeGroup(rawGroup);
                        String role = legacy.getString(rawGroup, "").trim();
                        if (group.matches("[a-z0-9_\\-]{1,64}")
                                && DiscordBot.isSnowflake(role) && roles.add(role)) {
                            mappings.put(group, role);
                        }
                    }
                }
            }
            Map<String, String> reverse = new LinkedHashMap<>();
            mappings.forEach((group, role) -> reverse.put(role, group));
            return new Settings(
                    config.getBoolean("Discord.Rank_Sync.Enabled", false),
                    Direction.parse(config.getString(
                            "Discord.Rank_Sync.Direction", "BOTH")),
                    InitialSource.parse(config.getString(
                            "Discord.Rank_Sync.Initial_Source", "MINECRAFT")),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mappings)),
                    java.util.Collections.unmodifiableMap(reverse),
                    java.util.Collections.unmodifiableMap(controls),
                    java.util.Collections.unmodifiableSet(new LinkedHashSet<>(roles)),
                    config.getBoolean(
                            "Discord.Rank_Sync.Exclusive_Discord_Roles", true),
                    config.getBoolean(
                            "Discord.Rank_Sync.Remove_On_Discord_Role_Loss", true),
                    normalizeGroup(config.getString(
                            "Discord.Rank_Sync.Fallback_Group", "default")),
                    config.getBoolean("Discord.Rank_Sync.Sync_On_Ready", true),
                    Math.max(1, Math.min(5_000, config.getInt(
                            "Discord.Rank_Sync.Ready_Sync_Limit", 500))),
                    Math.max(100L, Math.min(10_000L, config.getLong(
                            "Discord.Rank_Sync.Debounce_Milliseconds", 350L))),
                    Math.max(0L, Math.min(1_440L, config.getLong(
                            "Discord.Rank_Sync.Reconcile_Minutes", 5L))));
        }
    }

    private record RankMapping(String rankId, String group, String roleId, int priority,
                               Control control) {
    }
}
