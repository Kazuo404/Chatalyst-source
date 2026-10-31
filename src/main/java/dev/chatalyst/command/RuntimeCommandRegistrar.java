package dev.chatalyst.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Registers an optional player-facing command at runtime, under a name the owner chooses.
 *
 * <p>Declaring one in {@code plugin.yml} has two costs a modular plugin should not pay: the
 * label is reserved even on servers that never enable the feature, and its name is frozen into
 * the jar, so an owner whose server already uses that word has no way out.
 *
 * <p>A label another plugin already owns is <b>never taken</b>. Moderation commands are claimed
 * on purpose elsewhere — a server wants one moderation plugin — but hijacking a shop or store
 * label would break the very thing the player was trying to use. A taken label is skipped with
 * a warning naming its owner; {@code /chatalyst:<name>} always works regardless.
 */
public final class RuntimeCommandRegistrar implements Listener {

    /** What the command does. Kept separate so the same registrar serves any feature. */
    public interface Handler {
        boolean execute(CommandSender sender, String label, String[] args);

        default List<String> complete(CommandSender sender, String[] args) {
            return List.of();
        }
    }

    /**
     * @param namePath     config path holding the command name
     * @param aliasesPath  config path holding its aliases
     * @param defaultName  used when the configured name is missing or unusable
     * @param enabled      re-read on every apply, so a reload can register or drop it
     */
    public record Spec(String namePath, String aliasesPath, String defaultName,
                       List<String> defaultAliases, String description, String permission,
                       BooleanSupplier enabled) {
    }

    private final JavaPlugin plugin;
    private final Spec spec;
    private final Handler handler;
    private final List<String> claimed = new ArrayList<>();
    private boolean registered;

    public RuntimeCommandRegistrar(JavaPlugin plugin, Spec spec, Handler handler) {
        this.plugin = plugin;
        this.spec = spec;
        this.handler = handler;
    }

    private List<String> configuredLabels() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        addLabel(labels, plugin.getConfig().getString(spec.namePath(), spec.defaultName()));
        if (labels.isEmpty()) addLabel(labels, spec.defaultName());
        List<String> aliases = plugin.getConfig().getStringList(spec.aliasesPath());
        for (String alias : aliases.isEmpty() ? spec.defaultAliases() : aliases) {
            addLabel(labels, alias);
        }
        return List.copyOf(labels);
    }

    private static void addLabel(LinkedHashSet<String> into, String raw) {
        if (raw == null) return;
        String label = raw.trim().toLowerCase(Locale.ROOT);
        // A label with a space or a slash would be unreachable; refusing it beats shipping a
        // command nobody can type.
        if (label.matches("[a-z0-9_-]{1,32}")) into.add(label);
    }

    /** Registers, drops or renames the command to match the current configuration. */
    public synchronized void apply() {
        boolean enabled = spec.enabled().getAsBoolean();
        if (registered) unregister();
        if (enabled) register();
    }

    private void register() {
        List<String> labels = configuredLabels();
        if (labels.isEmpty()) {
            plugin.getLogger().warning(spec.namePath()
                    + " has no usable label; the command was not registered.");
            return;
        }
        CommandMap map = plugin.getServer().getCommandMap();
        var known = map.getKnownCommands();
        Dynamic command = new Dynamic(labels.getFirst(),
                labels.subList(1, labels.size()));

        for (String label : labels) {
            Command previous = known.get(label);
            if (previous != null && !(previous instanceof Dynamic)) {
                plugin.getLogger().warning("/" + label + " already belongs to "
                        + owner(previous) + ", so Chatalyst did not take it. Use /chatalyst:"
                        + label + " or set " + spec.namePath() + " to a free name.");
                continue;
            }
            // Otro modulo de Chatalyst con el mismo alias: antes se lo quitaba sin avisar, y
            // al apagarse este se llevaba el alias y dejaba al otro modulo sin el.
            if (previous instanceof Dynamic other && other.owner() != this) {
                plugin.getLogger().warning("/" + label + " is already used by another"
                        + " Chatalyst command, so " + spec.namePath() + " did not take it."
                        + " Give each feature its own aliases.");
                continue;
            }
            known.put(label, command);
            claimed.add(label);
        }
        // The namespaced form works even when every label was taken.
        String namespaced = "chatalyst:" + labels.getFirst();
        if (!(known.get(namespaced) instanceof Dynamic other) || other.owner() == this) {
            known.put(namespaced, command);
        }
        registered = true;
        if (!claimed.isEmpty()) {
            plugin.getLogger().info(spec.description() + " available as /"
                    + String.join(", /", claimed) + ".");
        }
        syncCommandTree();
    }

    private void unregister() {
        // NOT entrySet().removeIf: on current Paper the known-commands map is a view whose
        // iterator refuses removal, and that threw UnsupportedOperationException("remove")
        // out of the middle of /chatalyst reload, aborting the whole reload. Collect the
        // keys first, then remove them by key.
        var known = plugin.getServer().getCommandMap().getKnownCommands();
        List<String> ours = new ArrayList<>();
        for (var entry : new LinkedHashSet<>(known.entrySet())) {
            if (entry.getValue() instanceof Dynamic dynamic && dynamic.owner() == this) {
                ours.add(entry.getKey());
            }
        }
        for (String key : ours) {
            try {
                known.remove(key);
            } catch (UnsupportedOperationException immutable) {
                // Nothing else to try; say so instead of failing the reload around it.
                plugin.getLogger().warning("Could not unregister /" + key
                        + ": this server does not allow removing commands at runtime.");
            }
        }
        claimed.clear();
        registered = false;
        syncCommandTree();
    }

    /**
     * Plugins loading after Chatalyst can write the command map directly. If one took our
     * label, say so rather than leave the owner wondering why the command changed behaviour.
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        synchronized (this) {
            if (!registered) return;
            var known = plugin.getServer().getCommandMap().getKnownCommands();
            for (String label : List.copyOf(claimed)) {
                Command current = known.get(label);
                if (current instanceof Dynamic dynamic && dynamic.owner() == this) continue;
                claimed.remove(label);
                plugin.getLogger().warning("/" + label + " was taken over by "
                        + (current == null ? "another plugin" : owner(current))
                        + " after startup. Set " + spec.namePath() + " to a free name.");
            }
        }
    }

    private static String owner(Command command) {
        return command instanceof PluginIdentifiableCommand owned
                ? owned.getPlugin().getName() : command.getClass().getSimpleName();
    }

    /**
     * Se pide un reenvio; lo hace CommandTreeSync un tick despues, cuando la tanda de
     * altas y bajas ha terminado. Reenviar aqui mismo, una vez por comando, es lo que
     * ponia al pool asincrono de Paper a recorrer el arbol mientras el hilo principal
     * seguia escribiendo en el.
     */
    private void syncCommandTree() {
        CommandTreeSync.request(plugin);
    }

    private final class Dynamic extends Command implements PluginIdentifiableCommand {

        private Dynamic(String name, List<String> aliases) {
            super(name, spec.description(), "/" + name, aliases);
            if (spec.permission() != null) setPermission(spec.permission());
        }

        private RuntimeCommandRegistrar owner() {
            return RuntimeCommandRegistrar.this;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label,
                               @NotNull String[] args) {
            return handler.execute(sender, label, args);
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
            List<String> out = handler.complete(sender, args);
            return out == null ? List.of() : out;
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
