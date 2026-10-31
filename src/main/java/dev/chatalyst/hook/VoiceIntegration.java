package dev.chatalyst.hook;

import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Sanitize;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Bridges Chatalyst chat mutes to voice-chat moderation commands. */
public final class VoiceIntegration {

    private final JavaPlugin plugin;
    private boolean enabled;
    private String provider;
    private String muteCommand;
    private String unmuteCommand;
    private boolean warnedNoCommand;

    public VoiceIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Voice.Enabled", false);
        provider = plugin.getConfig().getString("Voice.Provider", "auto").toLowerCase(Locale.ROOT);
        muteCommand = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Voice.Mute_Command", "");
        unmuteCommand = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Voice.Unmute_Command", "");
        warnedNoCommand = false;
        if (enabled) {
            plugin.getLogger().info("Voice mute bridge enabled (" + provider + ").");
        }
    }

    public void mute(OfflinePlayer target, long durationMillis, String reason, String by) {
        if (!enabled || target == null) return;
        dispatch(resolveMuteCommand(), target, durationMillis, reason, by);
    }

    public void unmute(OfflinePlayer target) {
        if (!enabled || target == null) return;
        dispatch(resolveUnmuteCommand(), target, 0L, "", "");
    }

    private String resolveMuteCommand() {
        if (muteCommand != null && !muteCommand.isBlank()) return muteCommand;
        if (("auto".equals(provider) || "plasmo".equals(provider)) && hasPlugin("PlasmoVoice")) {
            return "vmute {player} {duration} {reason}";
        }
        warnNoCommand();
        return "";
    }

    private String resolveUnmuteCommand() {
        if (unmuteCommand != null && !unmuteCommand.isBlank()) return unmuteCommand;
        if (("auto".equals(provider) || "plasmo".equals(provider)) && hasPlugin("PlasmoVoice")) {
            return "vunmute {player}";
        }
        warnNoCommand();
        return "";
    }

    private void dispatch(String template, OfflinePlayer target, long durationMillis, String reason, String by) {
        if (template == null || template.isBlank()) return;
        String command = apply(template, target, durationMillis, reason, by);
        if (command.isBlank()) return;
        FoliaTasks.global(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String apply(String template, OfflinePlayer target, long durationMillis, String reason, String by) {
        String name = target.getName();
        if (name == null || name.isBlank()) {
            name = target.getUniqueId().toString();
        }
        return template
                .replace("{player}", clean(name))
                .replace("{uuid}", target.getUniqueId().toString())
                .replace("{duration}", formatDuration(durationMillis))
                .replace("{duration_ms}", String.valueOf(durationMillis))
                .replace("{reason}", clean(reason))
                .replace("{by}", clean(by))
                .replaceFirst("^/+", "")
                .trim();
    }

    private void warnNoCommand() {
        if (warnedNoCommand) return;
        warnedNoCommand = true;
        plugin.getLogger().warning("Voice bridge is enabled but no usable mute/unmute command was found. "
                + "Set Voice.Mute_Command and Voice.Unmute_Command for your voice plugin/addon.");
    }

    private boolean hasPlugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name) != null;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return Sanitize.stripInvisible(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private static String formatDuration(long millis) {
        if (millis == TimeUtil.PERMANENT) return "perm";
        long seconds = Math.max(1L, millis / 1000L);
        if (seconds % 86400L == 0L) return (seconds / 86400L) + "d";
        if (seconds % 3600L == 0L) return (seconds / 3600L) + "h";
        if (seconds % 60L == 0L) return (seconds / 60L) + "m";
        return seconds + "s";
    }
}
