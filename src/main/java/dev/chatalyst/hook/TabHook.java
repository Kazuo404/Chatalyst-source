package dev.chatalyst.hook;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Puerta de entrada segura a TAB (NEZNAMY). Registra los placeholders %chatalyst_...%
 * nativamente en TAB (sin necesitar PlaceholderAPI) y empuja el /nick a la tab list al
 * instante. Si TAB no esta instalado, todo degrada limpio (patron anti-NoClassDefFound:
 * {@link TabBridge} solo se toca cuando esta disponible).
 */
public final class TabHook {

    private static Boolean available = null;

    private TabHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("TAB") != null;
            if (available) {
                try {
                    Class.forName("me.neznamy.tab.api.TabAPI");
                } catch (Throwable t) {
                    available = false;
                }
            }
        }
        return available;
    }

    public static void reset() {
        available = null;
    }

    /** Registra los placeholders %chatalyst_...% directamente en TAB. Llamar una vez en enable. */
    public static void registerPlaceholders(ChatalystPlugin plugin) {
        if (!isAvailable()) return;
        try {
            TabBridge.registerPlaceholders(plugin);
            TabBridge.registerOnReload(plugin, () -> repushNicks(plugin));
            // El banner ya lista TAB en Hooks; este detalle va a debug.
            plugin.getLogger().fine("Hooked into TAB: registered native %chatalyst_...% placeholders.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not hook into TAB: " + t.getMessage());
        }
    }

    /**
     * Tras un {@code /tab reload} TAB olvida tambien los nombres puestos por API (Sync_Nick):
     * se vuelven a empujar. Con un segundo de margen, porque TAB avisa de la recarga desde su
     * propio hilo y antes de terminar de cargar a los jugadores.
     */
    private static void repushNicks(ChatalystPlugin plugin) {
        if (!plugin.getConfig().getBoolean("TAB.Sync_Nick", true)) return;
        try {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task ->
                    dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin, player -> {
                        String nick = plugin.getNickManager().getNick(player.getUniqueId());
                        if (nick != null) pushNick(player, nick);
                    }), 20L);
        } catch (RuntimeException ignored) {
            // El plugin se esta apagando: no hay nada que reempujar.
        }
    }

    /**
     * Empuja el apodo (MiniMessage, o null para restaurar el nombre real) al nombre de la
     * tab list de TAB. TAB carga a los jugadores en su propio hilo al entrar, asi que si
     * aun no conoce al jugador se reintenta una vez poco despues.
     */
    public static void pushNick(Player player, String nickMini) {
        push(player, nickMini, true);
    }

    /**
     * Fotograma efimero de la animacion. No programa reintentos: el siguiente tick del reloj
     * vuelve a intentarlo y asi un TAB que aun carga no genera una cola de tareas atrasadas.
     */
    public static void pushAnimatedName(Player player, String frameMini) {
        push(player, frameMini, false);
    }

    private static void push(Player player, String nickMini, boolean retry) {
        if (!isAvailable()) return;
        ChatalystPlugin plugin = ChatalystPlugin.get();
        if (plugin == null || !plugin.getConfig().getBoolean("TAB.Sync_Nick", true)) return;
        try {
            if (retry && !TabBridge.setTabName(player, nickMini)) {
                player.getScheduler().runDelayed(plugin, t -> {
                    try {
                        // El apodo VIGENTE, no el de hace dos segundos: si entre tanto cambio
                        // (un /nick, o el alias de /anonymous), reponer el viejo lo deshacia
                        // en el tab — y con /anonymous eso es enseñar quien es.
                        TabBridge.setTabName(player,
                                plugin.getNickManager().getNick(player.getUniqueId()));
                    } catch (Throwable ignored) {
                    }
                }, null, 40L);
            } else if (!retry) {
                TabBridge.setTabName(player, nickMini);
            }
        } catch (Throwable ignored) {
        }
    }
}
