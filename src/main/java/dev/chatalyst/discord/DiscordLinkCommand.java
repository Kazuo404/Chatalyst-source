package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player-facing account linking command. Discord remains entirely optional. */
public final class DiscordLinkCommand implements CommandExecutor, TabCompleter, Listener {

    private static final List<String> SUBCOMMANDS =
            List.of("link", "unlink", "status", "vincular", "desvincular", "estado");

    private final ChatalystPlugin plugin;
    private final DiscordLinkManager links;
    private final DiscordRankSync rankSync;
    private final DiscordLinkGate gate;

    public DiscordLinkCommand(ChatalystPlugin plugin, DiscordLinkManager links,
                              DiscordRankSync rankSync, DiscordLinkGate gate) {
        this.plugin = plugin;
        this.links = links;
        this.rankSync = rankSync;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "discord.players-only"));
            return true;
        }
        // Checked here rather than declared in plugin.yml: a static permission makes Bukkit
        // refuse the command before this executor runs, which would leave a held player with
        // no way to satisfy the linking requirement.
        if (!player.hasPermission("chatalyst.command.discord")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link", "vincular" -> create(player);
            case "unlink", "desvincular" -> unlink(player);
            case "status", "estado" -> status(player);
            default -> player.sendMessage(Lang.get(player, "discord.usage"));
        }
        return true;
    }

    private void create(Player player) {
        if (!plugin.getConfig().getBoolean("Discord.Enabled", false)
                || plugin.getDiscordBot() == null) {
            player.sendMessage(Lang.get(player, "discord.bot-disabled"));
            return;
        }
        if (!plugin.getConfig().getBoolean("Discord.Linking.Enabled", true)) {
            player.sendMessage(Lang.get(player, "discord.linking-disabled"));
            return;
        }
        // Already linked is NOT refused: completing a new code re-points the mapping. Without
        // this, someone who linked the wrong Discord account could never correct it while
        // mandatory linking blocks /discord unlink.
        if (links.discordId(player.getUniqueId()) != null) {
            player.sendMessage(Lang.get(player, "discord.already-linked"));
        }
        links.createCode(player.getUniqueId(), player.getName()).whenComplete((created, failure) ->
                FoliaTasks.entity(plugin, player, online -> {
                    if (failure != null || created == null) {
                        online.sendMessage(Lang.get(online, "discord.code-failed"));
                        return;
                    }
                    switch (created.status()) {
                        case RATE_LIMITED ->
                                online.sendMessage(Lang.get(online, "discord.rate-limited"));
                        case SUCCESS -> {
                            String command = plugin.getDiscordBot().linkCommand();
                            online.sendMessage(MM.full(Lang.raw(online, "discord.code-issued",
                                    "code", created.code())));
                            online.sendMessage(MM.full(Lang.raw(online, "discord.code-howto",
                                    "command", command, "code", created.code(),
                                    "minutes", Math.max(1L, links.codeExpirySeconds() / 60L))));
                        }
                        default -> online.sendMessage(Lang.get(online, "discord.code-failed"));
                    }
                }));
    }

    private void unlink(Player player) {
        // Unlinking while the requirement is active would immediately re-gate the player;
        // saying so up front beats silently locking them out one second later.
        if (gate != null && gate.isRequiredFor(player)) {
            player.sendMessage(Lang.get(player, "discord.unlink-required"));
            return;
        }
        links.unlinkMinecraft(player.getUniqueId()).whenComplete((removed, failure) ->
                FoliaTasks.entity(plugin, player, online -> {
                    if (failure != null) {
                        online.sendMessage(Lang.get(online, "discord.unlink-failed"));
                    } else if (Boolean.TRUE.equals(removed)) {
                        online.sendMessage(Lang.get(online, "discord.unlinked"));
                    } else {
                        online.sendMessage(Lang.get(online, "discord.not-linked"));
                    }
                }));
    }

    private void status(Player player) {
        String discordId = links.discordId(player.getUniqueId());
        player.sendMessage(discordId == null
                ? Lang.get(player, "discord.status-none")
                : Lang.get(player, "discord.status-linked", "id", discordId));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rankSync.scheduleMinecraftToDiscord(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        // Bilingual aliases are offered to the client that speaks that language, so a Spanish
        // player is not nudged towards a word they did not type.
        boolean spanish = Lang.isSpanish(sender);
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> offered = new ArrayList<>(3);
        for (String value : SUBCOMMANDS) {
            boolean spanishAlias = SUBCOMMANDS.indexOf(value) >= 3;
            if (spanishAlias == spanish && value.startsWith(prefix)) offered.add(value);
        }
        return offered;
    }
}
