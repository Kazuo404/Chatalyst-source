package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.staff.BanManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.TimeUtil;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One ban, both sides: banning an account anywhere closes the door everywhere.
 *
 * <p>A player banned in Minecraft who keeps their Discord seat is only half-banned — that is
 * where they keep arguing the ban, and where they arrange the alt that comes back. The
 * opposite is just as true: someone banned from the Discord for what they said there usually
 * should not keep playing either.
 *
 * <p>Which account is which is the LINK, never a name. Nothing here reads a nickname or a
 * Minecraft username: an unlinked player simply cannot be mirrored, which is the honest
 * outcome — guessing a Discord account from a name is how you ban the wrong person.
 */
public final class DiscordBanSync implements BanManager.Observer {

    /**
     * Accounts whose ban is being copied to the other side right now.
     *
     * <p>Without this, a ban in Minecraft bans the Discord account, Discord reports its own
     * ban back, and that lands as a fresh Minecraft ban — the two mirrors bouncing a ban
     * between them forever. Membership is dropped as soon as the round trip is over.
     */
    private final java.util.Map<String, Long> mirroring = new ConcurrentHashMap<>();

    /**
     * How long a mirror may claim an account before the claim is assumed lost.
     *
     * <p>Every claim is released from an asynchronous callback, and a callback is not a
     * promise: shut the bot down mid-flight, or schedule the release as the server is
     * stopping, and it simply never runs. The claim would then sit there for ever and every
     * later ban of that account would be mistaken for one arriving from the other side — and
     * skipped, in silence. A claim nobody released within this window is treated as
     * abandoned, so a single lost callback costs one round trip instead of the feature.
     */
    private static final long CLAIM_MILLIS = 30_000L;

    /**
     * Claims this account for a mirror in progress, unless one is genuinely under way.
     *
     * @return false when another mirror holds it right now, i.e. this event is the echo of
     *         something we just did.
     */
    private boolean claim(String discordId) {
        return claim(discordId, System.currentTimeMillis());
    }

    /** Test seam: the decision depends only on the clock, so the clock is passed in. */
    boolean claimForTest(String discordId, long now) {
        return claim(discordId, now);
    }

    void releaseForTest(String discordId) {
        release(discordId);
    }

    private boolean claim(String discordId, long now) {
        Long held = mirroring.putIfAbsent(discordId, now);
        if (held == null) return true;
        if (now - held < CLAIM_MILLIS) return false;
        // Stale: whoever took it never came back. Take it over, but only if nobody else beat
        // us to the same conclusion in between.
        return mirroring.replace(discordId, held, now);
    }

    private void release(String discordId) {
        mirroring.remove(discordId);
    }

    private final ChatalystPlugin plugin;
    private final DiscordBot bot;
    private final DiscordLinkManager links;
    private final DiscordPunishmentRoles roles;

    public DiscordBanSync(ChatalystPlugin plugin, DiscordBot bot, DiscordLinkManager links,
                          DiscordPunishmentRoles roles) {
        this.plugin = plugin;
        this.bot = bot;
        this.links = links;
        this.roles = roles;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("Discord.Enabled", false)
                && plugin.getConfig().getBoolean("Discord.Ban_Sync.Enabled", false);
    }

    private boolean toDiscord() {
        return enabled() && direction() != Direction.TO_MINECRAFT;
    }

    private boolean toMinecraft() {
        return enabled() && direction() != Direction.TO_DISCORD;
    }

    private Direction direction() {
        String raw = plugin.getConfig()
                .getString("Discord.Ban_Sync.Direction", "BOTH").trim().toUpperCase(Locale.ROOT);
        try {
            return Direction.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return Direction.BOTH;
        }
    }

    public enum Direction { BOTH, TO_DISCORD, TO_MINECRAFT }

    // ---- Minecraft -> Discord -------------------------------------------------

    @Override
    public void onBanned(UUID target, long until, String reason, String staff) {
        if (target == null) return;
        String discordId = links.discordId(target);
        if (discordId == null) return; // not linked: nothing to mirror, and nothing to guess
        boolean permanent = closesTheDiscordDoor(until);

        // A ban that ends on its own is SHOWN, never enforced on Discord — and this is not a
        // preference, it is the only honest option: a Discord ban has no expiry. Mirroring a
        // one-day Minecraft ban as a guild ban would outlive the punishment by years, because
        // nothing ever comes back to lift it (an expiry is not an unban and fires no event).
        // So a temporary ban wears the tier role if that feature is on, and otherwise leaves
        // Discord untouched. Only a permanent ban closes the door.
        if (!permanent) {
            if (roles != null && roles.enabled()) {
                roles.mark(target, DiscordPunishmentRoles.State.BAN,
                        until - System.currentTimeMillis());
            }
            return;
        }
        if (!toDiscord()) return;
        if (!claim(discordId)) return; // this ban CAME from Discord
        String message = roles == null ? null
                : roles.permanentBanMessage(reason, appealNote());
        if (roles != null && !roles.removeOnPermanent()) {
            roles.mark(target, DiscordPunishmentRoles.State.BAN, -1L);
            release(discordId);
            return;
        }
        // Told first, removed second: after the ban the bot may share no guild with them and
        // Discord refuses to open the DM, so the explanation would never arrive.
        bot.dmThen(discordId, message, () -> bot.banFromGuild(discordId,
                banReason(reason, staff, until), () -> release(discordId)));
    }

    @Override
    public void onUnbanned(UUID target) {
        if (target == null) return;
        String discordId = links.discordId(target);
        if (discordId == null) return;
        if (roles != null) roles.clear(target, DiscordPunishmentRoles.State.BAN);
        if (!toDiscord()) return;
        if (!claim(discordId)) return;
        bot.unbanFromGuild(discordId, () -> release(discordId));
    }

    private String appealNote() {
        return plugin.getConfig().getString("Discord.Punishment_Roles.Ban.Appeal", "");
    }

    /**
     * Whether this ban may remove the account from Discord at all.
     *
     * <p>Only a permanent one. A Discord ban has no expiry, so a temporary Minecraft ban
     * mirrored as a guild ban would outlast the punishment indefinitely — nothing comes back
     * to lift it, because an expiry is not an unban and fires no event.
     */
    static boolean closesTheDiscordDoor(long until) {
        return until == TimeUtil.PERMANENT || until <= 0L;
    }

    /** Why the Discord side sees the ban, in one line and always in English. */
    static String banReason(String reason, String staff, long until) {
        String when = until == TimeUtil.PERMANENT || until <= 0L
                ? "permanent" : "expires in " + TimeUtil.formatRemaining(until);
        String plain = reason == null || reason.isBlank() ? "no reason given" : reason;
        String by = staff == null || staff.isBlank() ? "staff" : staff;
        return ("Banned in Minecraft by " + by + " (" + when + "): " + plain)
                .replaceAll("\\s+", " ").trim();
    }

    // ---- Discord -> Minecraft -------------------------------------------------

    /** A guild ban seen by the bot. Runs on the JDA thread. */
    void onDiscordBan(String discordId) {
        if (!toMinecraft() || discordId == null) return;
        UUID minecraftId = links.minecraftId(discordId);
        if (minecraftId == null) return;
        if (!claim(discordId)) return; // this ban CAME from Minecraft
        FoliaTasks.global(plugin, () -> {
            try {
                var staff = plugin.getStaffModeration();
                var bans = staff == null ? null : staff.bans();
                if (bans == null || bans.get(minecraftId) != null) return;
                String reason = discordReason();
                bans.ban(plugin.getServer().getOfflinePlayer(minecraftId),
                        TimeUtil.PERMANENT, reason, DISCORD_STAFF);
                // Lo mismo que hace /ban despues de persistir: sin esto el baneado seguia
                // jugando hasta desconectarse, y el ban no aparecia en su historial.
                staff.history().record(minecraftId, "ban", "Discord",
                        staff.formatter().plainReason(reason));
                staff.endFreezeForBan(minecraftId);
                var record = bans.get(minecraftId);
                var online = plugin.getServer().getPlayer(minecraftId);
                if (online != null && record != null) {
                    FoliaTasks.entity(plugin, online, player -> player.kick(
                            staff.formatter().screen(player,
                                    dev.chatalyst.staff.ModerationFormatter.Screen.BAN,
                                    player.getName(), "Discord", record.until(), reason)));
                }
                plugin.getLogger().info("Mirrored a Discord ban onto Minecraft account "
                        + minecraftId + ".");
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not mirror a Discord ban onto "
                        + minecraftId + ": " + failure.getMessage());
            } finally {
                release(discordId);
            }
        });
    }

    /** A guild unban seen by the bot. */
    void onDiscordUnban(String discordId) {
        if (!toMinecraft() || discordId == null) return;
        UUID minecraftId = links.minecraftId(discordId);
        if (minecraftId == null) return;
        if (!claim(discordId)) return;
        FoliaTasks.global(plugin, () -> {
            try {
                var bans = plugin.getStaffModeration() == null
                        ? null : plugin.getStaffModeration().bans();
                if (bans == null) return;
                var record = bans.get(minecraftId);
                if (record == null) return;
                // Solo se levanta el ban que es "el mismo" en los dos lados: uno que vino de
                // Discord o que se copio alli. Un ban temporal de Minecraft nunca se copia, asi
                // que quitar un ban de Discord por otra cosa no puede soltar a un tramposo.
                boolean copiesPermanent = toDiscord()
                        && (roles == null || roles.removeOnPermanent());
                if (!mirroredBan(record.until(), record.staff(), copiesPermanent)) {
                    plugin.getLogger().info("Discord unban of " + discordId + " left the"
                            + " Minecraft ban of " + minecraftId + " alone: that ban was never"
                            + " mirrored to Discord.");
                    return;
                }
                bans.unban(plugin.getServer().getOfflinePlayer(minecraftId));
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not mirror a Discord unban onto "
                        + minecraftId + ": " + failure.getMessage());
            } finally {
                release(discordId);
            }
        });
    }

    /**
     * Si este ban de Minecraft es el reflejo de uno de Discord.
     *
     * @param copiesPermanent si hoy un ban permanente de Minecraft se copia como ban de Discord
     */
    static boolean mirroredBan(long until, String staff, boolean copiesPermanent) {
        if (!closesTheDiscordDoor(until)) return false; // temporal: nunca sale de Minecraft
        return DISCORD_STAFF.equals(staff) || copiesPermanent;
    }

    /** El autor con el que se registra un ban que llega desde Discord. */
    private static final String DISCORD_STAFF = "Discord";

    private String discordReason() {
        return plugin.getConfig().getString("Discord.Ban_Sync.Minecraft_Reason",
                "Banned on our Discord server");
    }
}
