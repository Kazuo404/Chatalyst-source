package dev.chatalyst.config;

import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports which of Chatalyst's commands another plugin actually owns.
 *
 * <p>Two plugins declaring {@code /msg} is normal and harmless — Bukkit gives the plain label
 * to whichever loaded first and namespaces the loser. What is not harmless is that nobody is
 * told. Ownership here is decided by plugin load order, which on a default install comes down
 * to the alphabetical order of the jar file names: renaming a jar silently swaps which plugin
 * answers {@code /msg}, and the only symptom is a command that suddenly behaves differently.
 *
 * <p>So this states plainly, once at startup and on demand in {@code /chatalyst doctor}, which
 * labels went elsewhere and to whom.
 */
public final class CommandConflictReport {

    private CommandConflictReport() {
    }

    /** One Chatalyst label that a different plugin answers. */
    public record Lost(String label, String owner, String command) {
    }

    /**
     * Must run after every plugin has registered, which is why callers hook it to
     * {@code ServerLoadEvent} rather than to enable.
     */
    public static List<Lost> evaluate(JavaPlugin plugin) {
        Map<String, Command> known;
        try {
            known = plugin.getServer().getCommandMap().getKnownCommands();
        } catch (Throwable unsupported) {
            return List.of(); // getCommandMap is Paper-only; nothing to report elsewhere
        }
        List<Lost> lost = new ArrayList<>();
        var commands = plugin.getDescription().getCommands();
        if (commands == null) return List.of();

        for (var entry : commands.entrySet()) {
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            labels.add(entry.getKey().toLowerCase(Locale.ROOT));
            Object aliases = entry.getValue() == null ? null : entry.getValue().get("aliases");
            if (aliases instanceof List<?> list) {
                for (Object alias : list) {
                    labels.add(String.valueOf(alias).toLowerCase(Locale.ROOT));
                }
            } else if (aliases instanceof String single) {
                labels.add(single.toLowerCase(Locale.ROOT));
            }

            for (String label : labels) {
                Command registered = known.get(label);
                if (registered == null) continue;
                if (!(registered instanceof PluginIdentifiableCommand owned)) continue;
                if (owned.getPlugin().getName().equals(plugin.getName())) continue;
                lost.add(new Lost(label, owned.getPlugin().getName(), entry.getKey()));
            }
        }
        return List.copyOf(lost);
    }

    /**
     * Drops the labels the owner gave away on purpose ({@code Commands_Owned_Elsewhere} in
     * config.yml, e.g. {@code trade} when AxTrade does trading): those are a decision, not a
     * surprise, so the startup warning stays quiet about them. {@code /chatalyst doctor} still
     * lists every conflict.
     */
    static List<Lost> withoutIntended(List<Lost> lost, List<String> ownedElsewhere) {
        if (ownedElsewhere == null || ownedElsewhere.isEmpty()) return lost;
        java.util.Set<String> intended = new java.util.HashSet<>();
        for (String label : ownedElsewhere) {
            if (label != null) intended.add(label.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT));
        }
        return lost.stream().filter(l -> !intended.contains(l.label())).toList();
    }

    /** One concise block at startup; silent when Chatalyst owns everything it declared. */
    public static void logStartupSummary(JavaPlugin plugin) {
        List<Lost> lost = withoutIntended(evaluate(plugin),
                plugin.getConfig().getStringList("Commands_Owned_Elsewhere"));
        if (lost.isEmpty()) return;
        StringBuilder message = new StringBuilder(lost.size()
                + " Chatalyst command(s) are answered by another plugin: ");
        for (int i = 0; i < lost.size(); i++) {
            if (i > 0) message.append(", ");
            message.append('/').append(lost.get(i).label())
                    .append(" -> ").append(lost.get(i).owner());
        }
        message.append(". Chatalyst's versions still work as /chatalyst:<command>."
                + " This is decided by plugin load order, not by configuration: to swap it,"
                + " remove the other plugin's command or let it own the label on purpose.");
        plugin.getLogger().warning(message.toString());
    }
}
