package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shows a punishment on the Discord side, as a role the bot creates and maintains itself.
 *
 * <p>A banned or muted player who looks identical to everyone else in the member list is a
 * gap the staff have to fill from memory. A role closes it — and, because the role says
 * roughly how long is left, the person serving it can see it too instead of asking.
 *
 * <p>Roles are created on demand and reused by name, so nothing has to be prepared by hand;
 * see {@link PunishmentTier} for why the time is expressed in tiers rather than exact days.
 */
public final class DiscordPunishmentRoles {

    /** The punishments that get a role, each with its own tiers and its own config branch. */
    public enum State {
        BAN, FREEZE, MUTE;

        String path() {
            return "Discord.Punishment_Roles." + name().charAt(0)
                    + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    private final ChatalystPlugin plugin;
    private final DiscordBot bot;
    private final DiscordLinkManager links;

    public DiscordPunishmentRoles(ChatalystPlugin plugin, DiscordBot bot,
                                  DiscordLinkManager links) {
        this.plugin = plugin;
        this.bot = bot;
        this.links = links;
    }

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask sweep;

    /**
     * Starts the pass that keeps the roles true as time goes by.
     *
     * <p>Runs even while the feature is off: the switch is read on every pass, so turning it on
     * with {@code /chatalyst reload} starts working without a restart.
     */
    public synchronized void start() {
        if (sweep != null) return;
        long seconds = Math.max(30L, plugin.getConfig()
                .getLong("Discord.Punishment_Roles.Refresh_Seconds", 300L));
        sweep = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> reconcileAll(), 20L * 30L, seconds * 20L);
    }

    public synchronized void stop() {
        if (sweep == null) return;
        sweep.cancel();
        sweep = null;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("Discord.Enabled", false)
                && plugin.getConfig().getBoolean("Discord.Punishment_Roles.Enabled", false);
    }

    /**
     * Marks this player as serving {@code state}, in the tier their remaining time falls into.
     *
     * @param remainingMillis time still to serve, or a negative value for permanent.
     */
    public void mark(UUID minecraftId, State state, long remainingMillis) {
        if (!enabled() || minecraftId == null) return;
        String discordId = links.discordId(minecraftId);
        if (discordId == null) return; // not linked: nothing to mark, and nothing to guess
        List<PunishmentTier> tiers = tiersFor(state);
        PunishmentTier tier = PunishmentTier.match(tiers, remainingMillis);
        // Written down here and not only by the sweep: otherwise the first pass after every
        // punishment would ask Discord to set a role that was set a second earlier.
        worn.put(memo(minecraftId, state), tier == null ? "" : tier.name());
        bot.applyPunishmentRole(discordId, tier, PunishmentTier.names(tiers));
    }

    /** Takes the punishment's roles away — it ended, or it was lifted. */
    public void clear(UUID minecraftId, State state) {
        if (!enabled() || minecraftId == null) return;
        // Only THIS punishment is forgotten: a frozen player who is also muted must keep the
        // mute in the memo, or the next sweep re-sends it as if it were new.
        worn.remove(memo(minecraftId, state));
        String discordId = links.discordId(minecraftId);
        if (discordId == null) return;
        bot.applyPunishmentRole(discordId, null, PunishmentTier.names(tiersFor(state)));
    }

    /**
     * The message someone gets before being removed from Discord for good.
     *
     * <p>Sent while they can still be reached — after the ban the bot may share no guild with
     * them — and it says WHY, because being removed with no explanation is how a mistake turns
     * into someone who never finds out they can appeal.
     */
    public String permanentBanMessage(String reason, String appeal) {
        String template = plugin.getConfig().getString("Discord.Punishment_Roles.Ban.Dm",
                "You were permanently banned from **{server}**.\nReason: {reason}");
        String text = template
                .replace("{reason}", reason == null || reason.isBlank()
                        ? "no reason given" : reason)
                .replace("{server}", plugin.getConfig()
                        .getString("Discord.Punishment_Roles.Server_Name", "our server"));
        if (appeal != null && !appeal.isBlank()) {
            text = text + "\n" + appeal;
        }
        return text;
    }

    /** Whether a permanent ban should also remove the account from the Discord server. */
    public boolean removeOnPermanent() {
        return plugin.getConfig()
                .getBoolean("Discord.Punishment_Roles.Ban.Remove_On_Permanent", true);
    }

    List<PunishmentTier> tiersFor(State state) {
        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection(state.path() + ".Tiers");
        return PunishmentTier.read(section);
    }

    /** Human-readable remaining time for the DM, e.g. "6d 4h". */
    static String remaining(long untilMillis) {
        return untilMillis == TimeUtil.PERMANENT || untilMillis <= 0L
                ? "permanent" : TimeUtil.formatRemaining(untilMillis);
    }

    // ---- keeping the roles true over time ------------------------------------

    /**
     * What each player is currently wearing, as far as we know.
     *
     * <p>It exists to keep the sweep quiet: without it, every pass would ask Discord to set a
     * role that is already set, for every punished player, for ever. With it, Discord only
     * hears from us when something actually changed.
     */
    private final java.util.Map<String, String> worn =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Re-reads every live punishment and fixes what its role should be now.
     *
     * <p>This is the half that was missing, and it is the half that makes tiers mean anything:
     * a punishment is marked once when it is handed out, but time keeps moving afterwards. A
     * ban of 31 days would wear "1+ month" for all 31 days, and — much worse — a ban or mute
     * that simply ran out would keep its role for ever, leaving somebody flagged as banned in
     * Discord long after they were free in game.
     */
    public void reconcileAll() {
        if (!enabled()) return;
        var plugin = this.plugin;
        long now = System.currentTimeMillis();
        java.util.Set<String> stillPunished = new java.util.HashSet<>();
        java.util.Set<String> removedForGood = new java.util.HashSet<>();

        var staff = plugin.getStaffModeration();
        if (staff != null) {
            staff.bans().snapshot().forEach((id, ban) -> {
                boolean permanent = ban.until() == TimeUtil.PERMANENT;
                if (!permanent && ban.until() <= now) return; // served: undressed below
                // Somebody removed from the guild for good is not there to be dressed. Left
                // in, every sweep would ask Discord about an account that is gone — for ever,
                // for every permanent ban, at a steady drip against the rate limit. Skipping
                // them also lets the pass below forget them after a single attempt.
                if (permanent && removeOnPermanent()) {
                    removedForGood.add(memo(id, State.BAN)); // el repaso no se lo quita
                    return;
                }
                stillPunished.add(memo(id, State.BAN));
                reconcileOne(id, State.BAN, permanent ? -1L : ban.until() - now);
            });
            staff.freezes().snapshot().keySet().forEach(id -> {
                stillPunished.add(memo(id, State.FREEZE));
                reconcileOne(id, State.FREEZE, -1L);
            });
        }
        var mutes = plugin.getMuteManager();
        if (mutes != null) {
            mutes.snapshot().forEach((id, mute) -> {
                long until = mute.until;
                if (until != TimeUtil.PERMANENT && until <= now) return;
                stillPunished.add(memo(id, State.MUTE));
                reconcileOne(id, State.MUTE, until == TimeUtil.PERMANENT ? -1L : until - now);
            });
        }

        java.util.Set<String> serving = new java.util.HashSet<>(stillPunished);
        serving.addAll(removedForGood);
        auditAfterConnect(serving);

        // Anything we were still dressing that no longer appears above has finished being
        // served — by expiry, by an unban we never saw, or by a restart. Undress it. Note
        // this is per PUNISHMENT, not per player: someone muted and frozen who stops being
        // frozen keeps their mute role.
        for (String key : java.util.List.copyOf(worn.keySet())) {
            if (stillPunished.contains(key)) continue;
            worn.remove(key);
            UUID id = ownerOf(key);
            State state = stateOf(key);
            if (id != null && state != null) clear(id, state);
        }
    }

    /** La sesion del bot que ya se repaso; otra distinta significa arranque o reconexion. */
    private long auditedSession = -1L;

    /**
     * El repaso que {@code worn} no puede hacer: el primero tras arrancar o reconectar.
     *
     * <p>{@code worn} vive en memoria. Un mute que caduco con el servidor apagado, o un
     * desbaneo hecho con el bot caido, ya no sale en el barrido ni en la memoria, y su rol se
     * quedaba en Discord para siempre. Una vez por sesion se mira cada cuenta vinculada (los
     * roles solo se ponen a cuentas vinculadas) y se le quitan los roles gestionados de los
     * castigos que ya no cumple.
     */
    private void auditAfterConnect(java.util.Set<String> stillPunished) {
        if (!bot.isReady()) return;
        long session = bot.sessions();
        if (session == auditedSession) return;
        auditedSession = session;
        java.util.Map<State, List<String>> namesByState = new java.util.EnumMap<>(State.class);
        for (State state : State.values()) {
            namesByState.put(state, PunishmentTier.names(tiersFor(state)));
        }
        links.allLinks().forEach((minecraftId, discordId) -> {
            java.util.Set<State> serving = java.util.EnumSet.noneOf(State.class);
            for (State state : State.values()) {
                if (stillPunished.contains(memo(minecraftId, state))) serving.add(state);
            }
            java.util.Set<String> stale = staleNames(namesByState, serving);
            if (!stale.isEmpty()) bot.stripPunishmentRoles(discordId, stale);
        });
    }

    /**
     * Los nombres de rol que alguien no deberia llevar: los de los castigos que no cumple,
     * menos los que comparta con uno que si (un mismo nombre en dos ramas no se le quita).
     */
    static java.util.Set<String> staleNames(java.util.Map<State, List<String>> namesByState,
                                            java.util.Set<State> serving) {
        java.util.Set<String> stale = new java.util.LinkedHashSet<>();
        java.util.Set<String> kept = new java.util.HashSet<>();
        namesByState.forEach((state, names) -> (serving.contains(state) ? kept : stale)
                .addAll(names));
        stale.removeAll(kept);
        return stale;
    }

    private void reconcileOne(UUID minecraftId, State state, long remainingMillis) {
        PunishmentTier tier = PunishmentTier.match(tiersFor(state), remainingMillis);
        String key = memo(minecraftId, state);
        String wanted = tier == null ? "" : tier.name();
        if (wanted.equals(worn.get(key))) return; // already right; say nothing
        if (tier == null) {
            clear(minecraftId, state);
            // Y se apunta que ya esta desnudo. clear() borra el memo, que es lo correcto
            // cuando un castigo TERMINA — pero este se sigue cumpliendo, solo que no cae en
            // ningun tramo configurado (algo que la propia config admite). Sin volver a
            // escribir el estado vacio, "" nunca coincide con un memo ausente: cada barrido
            // le pediria a Discord quitar un rol que ya quito, para siempre, que es justo el
            // goteo que este memo existe para evitar.
            worn.put(key, "");
        } else {
            mark(minecraftId, state, remainingMillis);
        }
    }

    /**
     * The memo key: one per PLAYER AND PUNISHMENT, never one per player.
     *
     * <p>Being muted and frozen at the same time is ordinary — you freeze someone for review
     * while they are already muted. With a single entry per player the two states overwrote
     * each other's memo, so every sweep found both of them "wrong", corrected both, and
     * overwrote it again: two Discord calls per player, every pass, for ever. Clearing one
     * punishment also forgot the other, which then came back as a fresh write.
     */
    private static String memo(UUID minecraftId, State state) {
        return minecraftId + ":" + state.name();
    }

    private static UUID ownerOf(String memoKey) {
        int separator = memoKey.lastIndexOf(':');
        try {
            return separator < 0 ? null : UUID.fromString(memoKey.substring(0, separator));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static State stateOf(String memoKey) {
        int separator = memoKey.lastIndexOf(':');
        try {
            return separator < 0 ? null
                    : State.valueOf(memoKey.substring(separator + 1));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
