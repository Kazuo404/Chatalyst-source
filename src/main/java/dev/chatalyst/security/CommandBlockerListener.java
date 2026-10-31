package dev.chatalyst.security;

import dev.chatalyst.lang.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Blocks configured commands entirely (by exact name or regex), as a safety net against
 * dangerous or unwanted commands regardless of which plugin registered them. Whoever has the
 * bypass permission is not affected. Runs at LOWEST so it wins before the command executes.
 */
public final class CommandBlockerListener implements Listener {

    private final JavaPlugin plugin;
    // Volatile: ademas del hilo de comandos, PacketEvents evalua estas reglas en hilos de
    // NETTY, y /chatalyst reload las reescribe desde otro hilo.
    private volatile boolean enabled;
    private volatile String bypass;
    private volatile List<Rule> rules = List.of();
    // Bloqueados para TODOS, operadores y bypass incluidos: solo la consola los ejecuta. Es
    // la red para lo que ni el staff debe lanzar desde dentro (/stop, /reload, /op...).
    private volatile List<Rule> everyoneRules = List.of();
    private final Set<UUID> bypassCache = ConcurrentHashMap.newKeySet();

    /** Un comando bloqueado y su mensaje propio opcional (null = mensaje por defecto). */
    private record Rule(Pattern pattern, String message) {
    }

    public CommandBlockerListener(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Command_Blocker.Enabled", false);
        bypass = plugin.getConfig().getString("Command_Blocker.Bypass_Permission", "chatalyst.bypass.blocker");
        rules = compile(plugin.getConfig().getStringList("Command_Blocker.Blocked"),
                "Command_Blocker");
        everyoneRules = compile(
                plugin.getConfig().getStringList("Command_Blocker.Blocked_For_Everyone"),
                "Command_Blocker.Blocked_For_Everyone");
        refreshOnlineCache();
    }

    private List<Rule> compile(List<String> entries, String origin) {
        List<Rule> fresh = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            // Mensaje propio opcional tras " ||| " (p. ej. bloquear /msg y decir "usa /mail").
            String entry = raw;
            String custom = null;
            int sep = raw.indexOf(" ||| ");
            if (sep >= 0) {
                custom = raw.substring(sep + 5).trim();
                entry = raw.substring(0, sep).trim();
            }
            try {
                // Entry can be a literal command ("/op") or a regex ("/(minecraft:)?op\\b.*").
                String rx = entry.startsWith("regex:") ? entry.substring(6)
                        : "(?i)" + Pattern.quote(normalize(entry)) + "(\\s.*)?";
                fresh.add(new Rule(Pattern.compile(rx), custom));
            } catch (Exception ex) {
                plugin.getLogger().warning("Invalid " + origin + " entry: " + raw);
            }
        }
        return List.copyOf(fresh);
    }

    private String normalize(String cmd) {
        String c = cmd.trim().toLowerCase(Locale.ROOT);
        return c.startsWith("/") ? c : "/" + c;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        Rule rule = ruleFor(bypasses(p), event.getMessage());
        if (rule != null) {
            event.setCancelled(true);
            p.sendMessage(denial(p, rule));
        }
    }

    private boolean bypasses(Player player) {
        return player.isOp()
                || (bypass != null && !bypass.isEmpty() && player.hasPermission(bypass));
    }

    /**
     * La regla que corta este comando, o null. Las de "para todos" se miran primero y no
     * atienden a bypass; las normales solo aplican a quien no lo tiene.
     */
    private Rule ruleFor(boolean bypasses, String message) {
        if (!enabled || message == null) return null;
        Rule everyone = match(everyoneRules, message);
        if (everyone != null) return everyone;
        return bypasses ? null : match(rules, message);
    }

    private static net.kyori.adventure.text.Component denial(Player player, Rule rule) {
        return rule.message() != null && !rule.message().isEmpty()
                ? dev.chatalyst.util.MM.full(rule.message())
                : Lang.get(player, "blocker.blocked");
    }

    /**
     * El aviso que le toca a este jugador por ese comando: el propio de la regla si lo tiene,
     * si no el general. Lo usa el corte a nivel de paquete, que antes contestaba siempre con
     * el general y se comia el mensaje escrito tras " ||| ".
     */
    public net.kyori.adventure.text.Component denialFor(Player player, String message) {
        Rule rule = ruleFor(bypasses(player), message);
        return rule != null ? denial(player, rule) : Lang.get(player, "blocker.blocked");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    public void sync(Player player) {
        if (player == null) return;
        if (player.isOp() || (bypass != null && !bypass.isEmpty()
                && player.hasPermission(bypass))) {
            bypassCache.add(player.getUniqueId());
        } else {
            bypassCache.remove(player.getUniqueId());
        }
    }

    public void forget(Player player) {
        if (player != null) bypassCache.remove(player.getUniqueId());
    }

    public boolean hasBypass(UUID id) {
        return id != null && bypassCache.contains(id);
    }

    public boolean shouldBlock(Player player, String message) {
        if (player == null) return false;
        return ruleFor(bypasses(player), message) != null;
    }

    public boolean shouldBlock(UUID id, String message) {
        if (id == null) return false;
        return ruleFor(hasBypass(id), message) != null;
    }

    private Rule match(List<Rule> rules, String message) {
        if (rules.isEmpty()) return null;
        // Se prueba tambien la forma sin namespace ("/minecraft:op" -> "/op"): una entrada
        // literal "/op" debe cubrir el alias namespaced, o el bloqueo se evade trivialmente.
        // Este listener es una red de seguridad: matchear DE MAS es preferible a DE MENOS.
        String stripped = dev.chatalyst.cooldown.CommandCooldownListener.stripNamespace(
                message.toLowerCase(Locale.ROOT));
        for (Rule rule : rules) {
            if (matchesSafely(rule, message) || matchesSafely(rule, stripped)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Matcheo con presupuesto: una entrada "regex:" mal escrita (backtracking catastrofico)
     * mas un comando manipulado clavaria el hilo de comandos — o uno de NETTY via
     * PacketEvents. Al agotar el presupuesto se BLOQUEA (fail closed): en una red de
     * seguridad, cortar un comando raro es mejor que dejar pasar /op por timeout inducido.
     */
    private boolean matchesSafely(Rule rule, String text) {
        try {
            return dev.chatalyst.util.SafeRegex.matches(rule.pattern(), text,
                    dev.chatalyst.util.SafeRegex.DEFAULT_BUDGET_NANOS);
        } catch (dev.chatalyst.util.SafeRegex.BudgetExceededException slow) {
            plugin.getLogger().warning("Command_Blocker rule '" + rule.pattern().pattern()
                    + "' exceeded its regex budget; blocking the command (fail closed). "
                    + "Rewrite that rule without nested quantifiers.");
            return true;
        }
    }

    private void refreshOnlineCache() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            dev.chatalyst.util.FoliaTasks.entity(plugin, p, this::sync);
        }
    }
}
