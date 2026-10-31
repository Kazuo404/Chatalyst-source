package dev.chatalyst.channel;

import dev.chatalyst.lang.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Router inteligente de canales: detecta en el chat GLOBAL los mensajes de compra/venta o
 * subasta (patrones configurables, español + inglés por defecto) y los saca por su canal
 * (trade/auction) automaticamente — con Proxy: true en el canal, tambien al resto de la red.
 * Asi el chat general queda libre de anuncios sin castigar al jugador.
 *
 * <p>Corre en LOW: despues del mute (LOWEST) y antes del formateo/entrega (NORMAL). En modo
 * "move" solo marca la redireccion en el {@link ChannelManager} y deja seguir el evento, de
 * modo que reglas, anti-spam, logs y el boton de borrar funcionan igual que siempre.
 */
public final class ChannelRouterListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final long NOTIFY_COOLDOWN_MS = 60_000L;
    private static final int MAX_ROUTES = 64;
    private static final int MAX_PATTERNS_PER_ROUTE = 64;
    private static final int MAX_PATTERN_LENGTH = 512;
    private static final int MAX_SHORTCUTS = 128;

    /** Una regla: si el patron coincide, el mensaje va al canal {@code channel}. */
    private record Route(String channel, List<Pattern> patterns) {
    }

    private final JavaPlugin plugin;
    private final ChannelManager channels;
    // Snapshot inmutable, reemplazado entero en load(): el chat async nunca ve una mezcla.
    private volatile List<Route> routes = List.of();
    private volatile boolean enabled;
    private volatile boolean block;   // true = cancelar y avisar; false = mover de canal
    private volatile boolean notify;
    // Atajos "!mensaje" -> canal, orden del YAML (los mas largos deberian ir primero).
    private volatile Map<String, String> shortcuts = Map.of();
    // Ultimo aviso "tu mensaje se movio" por jugador, para no repetirlo en cada linea.
    private final Map<UUID, Long> lastNotify = new ConcurrentHashMap<>();

    public ChannelRouterListener(JavaPlugin plugin, ChannelManager channels) {
        this.plugin = plugin;
        this.channels = channels;
    }

    public void load() {
        slowRouteWarned.clear();
        enabled = plugin.getConfig().getBoolean("Channel_Router.Enabled", true);
        block = "block".equalsIgnoreCase(plugin.getConfig().getString("Channel_Router.Action", "move"));
        notify = plugin.getConfig().getBoolean("Channel_Router.Notify", true);

        List<Route> next = new ArrayList<>();
        ConfigurationSection rules = plugin.getConfig().getConfigurationSection("Channel_Router.Rules");
        if (rules != null) {
            for (String channelName : rules.getKeys(false)) { // orden del YAML = prioridad
                if (next.size() >= MAX_ROUTES) break;
                if (channelName == null || channelName.isBlank() || channelName.length() > 64) continue;
                List<Pattern> patterns = new ArrayList<>();
                for (String raw : rules.getStringList(channelName + ".Patterns")) {
                    if (patterns.size() >= MAX_PATTERNS_PER_ROUTE) break;
                    if (raw == null || raw.isBlank() || raw.length() > MAX_PATTERN_LENGTH) continue;
                    try {
                        patterns.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                    } catch (PatternSyntaxException ex) {
                        plugin.getLogger().warning("Channel_Router: invalid pattern for '"
                                + channelName + "': " + ex.getMessage());
                    }
                }
                if (!patterns.isEmpty()) {
                    next.add(new Route(channelName.toLowerCase(java.util.Locale.ROOT), List.copyOf(patterns)));
                }
            }
        }
        routes = List.copyOf(next);

        Map<String, String> nextShortcuts = new java.util.LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("Channel_Router.Shortcut_Prefixes");
        if (sec != null) {
            for (String prefix : sec.getKeys(false)) {
                if (nextShortcuts.size() >= MAX_SHORTCUTS) break;
                String channel = sec.getString(prefix, "");
                if (!prefix.isEmpty() && prefix.length() <= 32
                        && !channel.isEmpty() && channel.length() <= 64) {
                    nextShortcuts.put(prefix, channel.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        shortcuts = Map.copyOf(nextShortcuts);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!channels.isEnabled()) return;
        Player p = event.getPlayer();

        // Si el mensaje ya lleva un destino explicito (ej. /f a tu frecuencia), el router no
        // lo toca — ni con atajos ni con patrones. Va antes que todo para no secuestrarlo.
        if (channels.hasPendingRoute(p.getUniqueId())) return;

        // Atajos explicitos ("!vendo 64 oro" -> canal trade sin cambiarse). Son eleccion del
        // jugador: no les afecta el bypass ni el modo block del router.
        if (!shortcuts.isEmpty()) {
            String raw = PLAIN.serialize(event.message());
            for (Map.Entry<String, String> e : shortcuts.entrySet()) {
                if (!raw.startsWith(e.getKey())) continue;
                String rest = raw.substring(e.getKey().length()).trim();
                Channel target = channels.byName(e.getValue());
                if (rest.isEmpty() || target == null || !target.canUse(p)) break;
                event.message(net.kyori.adventure.text.Component.text(rest));
                channels.routeNextMessage(p, target);
                return;
            }
        }

        if (!enabled || routes.isEmpty()) return;
        if (p.hasPermission("chatalyst.bypass.router")) return;

        // Solo se enruta lo escrito en el canal por defecto (global): si el jugador ya esta
        // en trade/staff/party, se respeta su eleccion.
        Channel writing = channels.getWriteChannel(p);
        Channel def = channels.getDefault();
        if (writing == null || def == null || !writing.name.equals(def.name)) return;

        String text = PLAIN.serialize(event.message());
        for (Route route : routes) {
            if (!matchesSafely(route, text)) continue;
            Channel target = channels.byName(route.channel);
            if (target == null || !target.canUse(p)) return; // canal borrado o sin acceso: no tocar
            if (block) {
                event.setCancelled(true);
                p.sendMessage(Lang.get(p, "router.blocked", "name", target.name));
                return;
            }
            channels.routeNextMessage(p, target);
            if (notify) {
                long now = System.currentTimeMillis();
                Long last = lastNotify.get(p.getUniqueId());
                if (last == null || now - last >= NOTIFY_COOLDOWN_MS) {
                    lastNotify.put(p.getUniqueId(), now);
                    p.sendMessage(Lang.get(p, "router.moved", "name", target.name));
                }
            }
            return;
        }
    }

    /** Libera el cooldown de aviso del jugador al salir, para que el mapa no crezca sin fin. */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastNotify.remove(event.getPlayer().getUniqueId());
    }

    // Aviso una sola vez por canal si algun patron del router excede el presupuesto anti-ReDoS.
    private final java.util.Set<String> slowRouteWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Como los patrones del router son regex libres de la config y el texto lo aporta el
     * jugador, cada find() corre con presupuesto ({@link dev.chatalyst.util.SafeRegex}): un
     * patron con backtracking catastrofico se salta (fail-open) en vez de clavar el chat.
     */
    private boolean matchesSafely(Route route, String text) {
        for (Pattern pattern : route.patterns) {
            try {
                if (dev.chatalyst.util.SafeRegex.find(pattern, text,
                        dev.chatalyst.util.SafeRegex.DEFAULT_BUDGET_NANOS)) {
                    return true;
                }
            } catch (dev.chatalyst.util.SafeRegex.BudgetExceededException slow) {
                if (slowRouteWarned.add(route.channel)) {
                    plugin.getLogger().warning("Channel_Router: a pattern for '" + route.channel
                            + "' exceeded the regex time budget and was skipped; it likely has"
                            + " catastrophic backtracking. Rewrite it.");
                }
            }
        }
        return false;
    }
}
