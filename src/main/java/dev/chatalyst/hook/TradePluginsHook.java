package dev.chatalyst.hook;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soporte para plugins de mercado (subastas, tiendas, trades). Dos piezas:
 *
 * <ul>
 *   <li><b>Deteccion</b>: al arrancar se listan los plugins de mercado conocidos que esten
 *       instalados, para dejar claro en el log que Chatalyst convive con ellos.</li>
 *   <li><b>Puente de anuncios</b>: cuando un jugador publica algo (ej. {@code /ah sell ...}),
 *       se anuncia en el canal trade/auction configurado — cada espectador lo ve en su idioma
 *       y, si el canal tiene Proxy, tambien llega al resto de la red. Best-effort: se anuncia
 *       al ejecutar el comando (MONITOR); un listado que falle en el otro plugin puede
 *       anunciar igualmente.</li>
 * </ul>
 */
public final class TradePluginsHook implements Listener {

    private record KnownPlugin(String name, Set<String> channels) {
    }

    /** Catalogo unico para deteccion, auto-defer y activacion de canales de integracion. */
    private static final List<KnownPlugin> KNOWN_PLUGINS = List.of(
            auction("AuctionHouse"), auction("zAuctionHouse"), auction("zAuctionHouseV3"),
            auction("CrazyAuctions"),
            auction("PlayerAuctions"), auction("AuctionMaster"), trade("QuickShop"),
            trade("QuickShop-Hikari"), trade("ChestShop"), trade("ShopGUIPlus"),
            trade("EconomyShopGUI"), trade("DynamicShop"), trade("BossShopPro"));

    private static KnownPlugin auction(String name) {
        return new KnownPlugin(name, Set.of("auction"));
    }

    private static KnownPlugin trade(String name) {
        return new KnownPlugin(name, Set.of("trade"));
    }

    private final ChatalystPlugin plugin;
    private final ChannelManager channels;
    private volatile boolean announceEnabled;
    private volatile long cooldownMs;
    // "comando normalizado" -> nombre de canal destino. Orden del YAML preservado.
    private volatile Map<String, String> commandRoutes = Map.of();
    // Se poda lo ya caducado cuando crece (sin poda: una entrada por jugador historico).
    private final Map<UUID, Long> lastAnnounce = new ConcurrentHashMap<>();
    private static final int ANNOUNCE_SOFT_CAP = 5000;

    public TradePluginsHook(ChatalystPlugin plugin, ChannelManager channels) {
        this.plugin = plugin;
        this.channels = channels;
    }

    public void load() {
        announceEnabled = plugin.getConfig().getBoolean("Trade_Plugins.Announce.Enabled", true);
        cooldownMs = cooldownMillis(plugin.getConfig().getLong(
                "Trade_Plugins.Announce.Cooldown_Seconds", 60));
        Map<String, String> next = new LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("Trade_Plugins.Announce.Commands");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String channel = sec.getString(key, "");
                if (!channel.isEmpty()) {
                    next.put(normalize(key), channel.toLowerCase(Locale.ROOT));
                }
            }
        }
        commandRoutes = Map.copyOf(next);
    }

    /** Plugins de mercado conocidos e instalados ahora mismo (para logs y el auto-defer). */
    public static List<String> detected() {
        List<String> found = new ArrayList<>();
        for (KnownPlugin known : KNOWN_PLUGINS) {
            if (Bukkit.getPluginManager().getPlugin(known.name()) != null) {
                found.add(known.name());
            }
        }
        return List.copyOf(found);
    }

    /** Canales requeridos por las integraciones detectadas en este servidor. */
    public static Set<String> detectedChannels() {
        return detectedChannels(name -> Bukkit.getPluginManager().getPlugin(name) != null);
    }

    /** Variante pura para probar el catalogo sin iniciar Bukkit. */
    static Set<String> detectedChannels(Predicate<String> installed) {
        java.util.LinkedHashSet<String> channels = new java.util.LinkedHashSet<>();
        for (KnownPlugin known : KNOWN_PLUGINS) {
            if (installed.test(known.name())) channels.addAll(known.channels());
        }
        return Set.copyOf(channels);
    }

    /** Loguea los plugins de mercado detectados (llamar una vez al terminar el arranque). */
    public void logDetected() {
        List<String> found = detected();
        if (!found.isEmpty()) {
            plugin.getLogger().info("Marketplace plugins detected: " + String.join(", ", found)
                    + " — listing announcements go to the trade/auction channels (Trade_Plugins in config).");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!announceEnabled || commandRoutes.isEmpty() || !channels.isEnabled()) return;
        String cmd = normalize(event.getMessage().substring(1));
        String channelName = null;
        String base = null;
        for (Map.Entry<String, String> e : commandRoutes.entrySet()) {
            if (cmd.equals(e.getKey()) || cmd.startsWith(e.getKey() + " ")) {
                channelName = e.getValue();
                base = e.getKey();
                break;
            }
        }
        if (channelName == null) return;
        Channel target = channels.byName(channelName);
        if (target == null) return;

        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastAnnounce.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        pruneStale(now);
        lastAnnounce.put(p.getUniqueId(), now);

        // El comando "de ver el mercado" que se sugiere es la primera palabra del listado
        // ("ah sell" -> /ah), que en todos estos plugins abre la GUI.
        String viewCommand = base.split(" ")[0];
        String shown = dev.chatalyst.nick.NickManager.suggestedName(p);
        // El evento corre en la region del emisor: iterar y tocar jugadores de otras
        // regiones no es seguro en Folia; se despacha por jugador (canSee es async-safe).
        Channel ch = target;
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                online -> channels.canSee(online, p, ch),
                online -> online.sendMessage(Lang.get(online, "router.listing-announce",
                        "player", shown, "command", viewCommand, "name", ch.name)));
        dev.chatalyst.util.FoliaTasks.global(plugin, () ->
                plugin.getServer().getConsoleSender().sendMessage(Lang.get("router.listing-announce",
                        "player", shown, "command", viewCommand, "name", ch.name)));
        if (target.proxy && plugin.getProxyManager() != null && plugin.getProxyManager().isEnabled()) {
            plugin.getProxyManager().forward(target.name, Lang.get("router.listing-announce",
                    "player", shown, "command", viewCommand, "name", target.name));
        }
    }

    /** Poda cooldowns de anuncio ya vencidos; solo actua cuando el mapa crece. */
    private void pruneStale(long now) {
        if (lastAnnounce.size() < ANNOUNCE_SOFT_CAP) return;
        long cutoff = now - Math.max(cooldownMs, 60_000L);
        lastAnnounce.values().removeIf(t -> t < cutoff);
    }

    static long cooldownMillis(long configuredSeconds) {
        long seconds = Math.max(0L, Math.min(86_400L, configuredSeconds));
        return seconds * 1_000L;
    }

    // Quita namespaces ("plugin:ah sell" -> "ah sell"), pasa a minusculas y colapsa espacios.
    // Visible en el paquete para poder testearlo.
    static String normalize(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int space = s.indexOf(' ');
        String first = space >= 0 ? s.substring(0, space) : s;
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        return space >= 0 ? first + s.substring(space) : first;
    }
}
