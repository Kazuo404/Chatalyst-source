package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers "is the Discord side actually going to work?" without waiting for it to fail.
 *
 * <p>Everything this plugin does on Discord depends on things no config file can confirm: a
 * permission granted in a menu, a role dragged above another one, an intent ticked in a
 * developer portal. Get any of them wrong and nothing crashes — the bot connects, the config
 * looks right, and features simply do nothing, each failing quietly in its own corner.
 *
 * <p>So this asks Discord directly, once, and says what it found. It only READS: no test
 * message, no test role, nothing to clean up afterwards.
 */
public final class DiscordSelfTest {

    private record Check(boolean ok, String label, String detail) {
    }

    private final ChatalystPlugin plugin;

    public DiscordSelfTest(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void run(CommandSender sender) {
        List<Check> checks = new ArrayList<>();
        var config = plugin.getConfig();
        DiscordBot bot = plugin.getDiscordBot();

        if (!config.getBoolean("Discord.Enabled", false)) {
            report(sender, List.of(new Check(false, "Discord.Enabled",
                    "the integration is switched off; nothing else was checked")));
            return;
        }
        JDA jda = bot == null ? null : bot.jdaForDiagnostics();
        if (jda == null || !bot.isReady()) {
            report(sender, List.of(new Check(false, "Gateway",
                    "the bot is not connected. Check the token and that MESSAGE CONTENT"
                            + " INTENT is enabled in the developer portal")));
            return;
        }
        checks.add(new Check(true, "Gateway", "connected as "
                + jda.getSelfUser().getName()));

        Guild guild = jda.getGuildById(config.getString("Discord.Guild_Id", "").trim());
        if (guild == null) {
            checks.add(new Check(false, "Guild_Id",
                    "the bot is not in that server, or the ID is wrong"));
            report(sender, checks);
            return;
        }
        checks.add(new Check(true, "Guild", guild.getName()));
        Member self = guild.getSelfMember();

        // Channels: configured is not the same as reachable, and "reachable" is not the same
        // as "may write there" — the third is the one that silently swallows messages.
        for (String path : List.of("Discord.Channel_Id", "Discord.Linking.Channel_Id",
                "Discord.Staff.Channel_Id", "Discord.Staff.Priority_Channel_Id",
                "Discord.Market.Channel_Id")) {
            String id = config.getString(path, "").trim();
            if (id.isEmpty()) continue;
            TextChannel channel = guild.getTextChannelById(id);
            if (channel == null) {
                checks.add(new Check(false, path, "not visible to the bot in this server"));
            } else if (!self.hasPermission(channel, Permission.MESSAGE_SEND)) {
                checks.add(new Check(false, path,
                        "#" + channel.getName() + " — cannot send messages there"));
            } else {
                checks.add(new Check(true, path, "#" + channel.getName()));
            }
        }

        boolean needsRoles = config.getBoolean("Discord.Punishment_Roles.Enabled", false)
                || config.getBoolean("Discord.Rank_Sync.Enabled", false)
                || !config.getString("Discord.Linking.Linked_Role_Id", "").trim().isEmpty();
        if (needsRoles) {
            checks.add(new Check(self.hasPermission(Permission.MANAGE_ROLES), "Manage Roles",
                    self.hasPermission(Permission.MANAGE_ROLES) ? "granted"
                            : "missing — every role this plugin gives will be refused"));
            // The permission alone is not enough: Discord refuses to touch a role that sits
            // above the bot's own, and that is the single most common reason "nothing happens".
            String linked = config.getString("Discord.Linking.Linked_Role_Id", "").trim();
            if (!linked.isEmpty()) {
                Role role = guild.getRoleById(linked);
                if (role == null) {
                    checks.add(new Check(false, "Linked_Role_Id",
                            "that role does not exist in this server"));
                } else {
                    checks.add(new Check(self.canInteract(role), "Linked role position",
                            self.canInteract(role) ? "below the bot, fine"
                                    : "'" + role.getName() + "' sits ABOVE the bot's role;"
                                            + " drag the bot's role higher"));
                }
            }
        }
        if (config.getBoolean("Discord.Ban_Sync.Enabled", false)) {
            checks.add(new Check(self.hasPermission(Permission.BAN_MEMBERS), "Ban Members",
                    self.hasPermission(Permission.BAN_MEMBERS) ? "granted"
                            : "missing — bans will never reach Discord"));
        }
        // Report buttons and !ban are gated behind an allowlist that fails closed. Empty lists
        // authorise nobody, which looks exactly like the feature being broken.
        if (config.getBoolean("Discord.Staff.Handle_From_Discord", false)) {
            boolean anyone = !config.getStringList("Discord.Staff.Allowed_User_Ids").isEmpty()
                    || !config.getStringList("Discord.Staff.Allowed_Role_Ids").isEmpty();
            checks.add(new Check(anyone, "Staff allowlist", anyone ? "configured"
                    : "empty — buttons and commands will answer 'not authorized' to everyone,"
                            + " including you"));
        }
        report(sender, checks);
    }

    private void report(CommandSender sender, List<Check> checks) {
        long failed = checks.stream().filter(check -> !check.ok()).count();
        FoliaTasks.global(plugin, () -> {
            sender.sendMessage(MM.full("<#B56CFF>Discord self-test <#7B68A0>— "
                    + (failed == 0 ? "<#3DFFA8>everything checks out"
                    : "<#FF4D6D>" + failed + " problem(s) found")));
            for (Check check : checks) {
                sender.sendMessage(MM.full((check.ok() ? "<#3DFFA8>✔ " : "<#FF4D6D>✖ ")
                        + "<#FBF2FF>" + MM.escape(check.label())
                        + " <#7B68A0>— <#C0B3D9>" + MM.escape(check.detail())));
            }
        });
    }
}
