package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code /tagcontador <jugador> <clave> [cantidad]} (2026-10-18): suma (o con negativo, resta)
 * a un contador de titulos ({@link TitleCounters}). Pensado para la consola y los comandos de
 * recompensa de otros plugins: fin de una Luna de Sangre, jefe derrotado, voto...
 *
 * <p>Vale con el jugador desconectado si el servidor lo conoce por nombre (cache de usuarios).
 * Si esta conectado, sus titulos se re-evaluan al momento.
 */
public final class TagCounterCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "chatalyst.admin.tags";
    /** Tope de una sola suma, para que un comando mal escrito no deje un valor absurdo. */
    static final long MAX_DELTA = TagRequirement.MAX_AMOUNT;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final TitleCounters counters;

    public TagCounterCommand(ChatalystPlugin plugin, TagManager tags, TitleCounters counters) {
        this.plugin = plugin;
        this.tags = tags;
        this.counters = counters;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Lang.get(sender, "tag.counter-usage", "label", label));
            return true;
        }
        String key = TitleCounters.normalizeKey(args[1]);
        if (!TitleCounters.validKey(key)) {
            sender.sendMessage(Lang.get(sender, "tag.counter-invalid-key"));
            return true;
        }
        OptionalLong delta = parseDelta(args.length == 3 ? args[2] : null);
        if (delta.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "tag.counter-invalid-amount",
                    "max", MAX_DELTA));
            return true;
        }
        Target target = resolve(args[0]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[0]));
            return true;
        }
        OptionalLong value = counters.add(target.id(), key, delta.getAsLong());
        if (value.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "tag.counter-updated",
                "player", target.name(), "key", key,
                "delta", (delta.getAsLong() >= 0 ? "+" : "") + delta.getAsLong(),
                "value", value.getAsLong()));
        Player online = Bukkit.getPlayer(target.id());
        if (online != null) {
            FoliaTasks.entity(plugin, online, player ->
                    TagUnlockProcessor.refresh(plugin, tags, player));
        }
        return true;
    }

    /** Cantidad a sumar: 1 si no se da; entero con signo en {@code [-MAX, MAX]}; vacio si no. */
    static OptionalLong parseDelta(String raw) {
        if (raw == null || raw.isBlank()) return OptionalLong.of(1L);
        try {
            long value = Long.parseLong(raw.trim());
            if (value < -MAX_DELTA || value > MAX_DELTA) return OptionalLong.empty();
            return OptionalLong.of(value);
        } catch (NumberFormatException invalid) {
            return OptionalLong.empty();
        }
    }

    private record Target(UUID id, String name) {
    }

    /** Conectado por nombre exacto, o desconectado si el servidor ya lo conoce (sin red). */
    private static Target resolve(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.*]{1,32}")) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) return null;
        String shown = cached.getName() == null ? name : cached.getName();
        return new Target(cached.getUniqueId(), shown);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERMISSION)) return out;
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(pre)) {
                    out.add(online.getName());
                }
            }
        } else if (args.length == 2) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            TagCatalog catalog = tags.catalog();
            if (catalog != null) {
                java.util.Set<String> keys = new java.util.TreeSet<>();
                for (TagDefinition definition : catalog.all()) {
                    for (TagRequirement rule : definition.requirements().rules()) {
                        if (rule.type() == TagRequirementType.COUNTER
                                && !rule.target().isEmpty()) {
                            keys.add(rule.target());
                        }
                    }
                }
                for (String key : keys) {
                    if (key.startsWith(pre)) out.add(key);
                }
            }
        } else if (args.length == 3) {
            out.add("1");
            out.add("-1");
        }
        return out;
    }
}
