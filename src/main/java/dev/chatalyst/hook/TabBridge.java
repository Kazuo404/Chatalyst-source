package dev.chatalyst.hook;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.util.MM;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Aislado a proposito: solo se carga cuando TAB (NEZNAMY) esta presente (lo comprueba
 * {@link TabHook}). Registra placeholders nativos de TAB y sincroniza el /nick con la
 * tab list.
 *
 * <p>Placeholders registrados (usables en cualquier config de TAB, sin PlaceholderAPI):
 * %chatalyst_nick% (con colores), %chatalyst_nick_plain% (sin efectos), %chatalyst_nickcolor%
 * (color primario del apodo como codigo &), %chatalyst_channel%, %chatalyst_muted%,
 * %chatalyst_warnings%, %chatalyst_chatcolor%.
 */
final class TabBridge {

    // TAB no parsea MiniMessage en la salida de placeholders/API, pero si codigos legacy
    // (&x y &#RRGGBB): serializamos el apodo a ese formato (gradientes incluidos, letra a letra).
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private TabBridge() {
    }

    /** Apodo MiniMessage -> texto con codigos & que TAB sabe renderizar. */
    private static String toTabFormat(String nickMini) {
        return LEGACY.serialize(MM.safe(nickMini));
    }

    private static EventHandler<TabLoadEvent> reloadHandler;

    /**
     * Vuelve a registrar los placeholders cada vez que TAB carga. Un {@code /tab reload} crea un
     * gestor de placeholders NUEVO y los registrados por API se pierden: a partir de ahi TAB
     * resolvia %chatalyst_nick% y %chatalyst_afk% por PlaceholderAPI, que devuelve MiniMessage
     * crudo y "yes"/"no" en vez del apodo ya coloreado y la etiqueta AFK.
     */
    static void registerOnReload(ChatalystPlugin plugin, Runnable afterReload) {
        EventBus bus = TabAPI.getInstance().getEventBus();
        if (bus == null) return;
        if (reloadHandler != null) bus.unregister(reloadHandler);
        reloadHandler = event -> {
            if (!plugin.isEnabled()) return;
            registerPlaceholders(plugin);
            afterReload.run();
        };
        bus.register(TabLoadEvent.class, reloadHandler);
    }

    static void registerPlaceholders(ChatalystPlugin plugin) {
        PlaceholderManager pm = TabAPI.getInstance().getPlaceholderManager();

        pm.registerPlayerPlaceholder("%chatalyst_nick%", 500, p -> {
            String nick = plugin.getNickManager().getNick(p.getUniqueId());
            // Si eligio una animacion de nametag, el TAB muestra su DEGRADADO estatico (no anima:
            // reenviar el nombre a todos cada tick laguea). TAB refresca este placeholder solo.
            dev.chatalyst.tablist.FancyNametagManager fancy = plugin.getFancyNametags();
            if (fancy != null) {
                String grad = fancy.tabGradientNick(p.getUniqueId(), nick != null ? nick : p.getName());
                if (grad != null) return toTabFormat(grad);
            }
            return nick != null ? toTabFormat(nick) : p.getName();
        });
        pm.registerPlayerPlaceholder("%chatalyst_nick_plain%", 500, p -> {
            String nick = plugin.getNickManager().getNick(p.getUniqueId());
            return nick != null ? MM.strip(nick) : p.getName();
        });
        // Color primario del apodo como codigo & ("" si no tiene). Puesto al FINAL de
        // tagprefix, TAB pinta el nombre sobre la cabeza de ese color (el nametag solo
        // acepta un color plano: limite de Minecraft, los gradientes usan su primer color).
        pm.registerPlayerPlaceholder("%chatalyst_nickcolor%", 500, p ->
                Placeholders.nickColorLegacy(p.getUniqueId()));
        pm.registerPlayerPlaceholder("%chatalyst_channel%", 1000, p -> {
            if (p.getPlayer() instanceof Player bukkit) {
                Channel ch = plugin.getChannelManager().getWriteChannel(bukkit);
                return ch != null ? ch.name : "";
            }
            return "";
        });
        pm.registerPlayerPlaceholder("%chatalyst_muted%", 1000, p ->
                plugin.getMuteManager().isMuted(p.getUniqueId()) ? "yes" : "no");
        pm.registerPlayerPlaceholder("%chatalyst_warnings%", 1000, p ->
                p.getPlayer() instanceof Player bukkit
                        ? String.valueOf(plugin.getWarningPoints().get(bukkit)) : "0");
        pm.registerPlayerPlaceholder("%chatalyst_chatcolor%", 1000, p ->
                plugin.getChatColorManager().getColorTag(p.getUniqueId()));
        // Sufijo AFK para la tab list ("" si no esta AFK). Refresco rapido para que aparezca
        // y desaparezca al instante. Nativo en TAB, sin depender de PlaceholderAPI.
        pm.registerPlayerPlaceholder("%chatalyst_afk%", 500, p -> {
            String tag = plugin.getAfkManager().tabTag(p.getUniqueId());
            return tag != null && !tag.isEmpty() ? toTabFormat(tag) : "";
        });
        // Ping nativo (sin necesitar el expansion Player de PlaceholderAPI): numero plano,
        // numero coloreado por calidad y barras clasicas.
        pm.registerPlayerPlaceholder("%chatalyst_ping%", 1000, p ->
                p.getPlayer() instanceof Player bukkit ? String.valueOf(bukkit.getPing()) : "0");
        pm.registerPlayerPlaceholder("%chatalyst_ping_ms%", 1000, p ->
                p.getPlayer() instanceof Player bukkit
                        ? toTabFormat(Placeholders.pingMs(bukkit.getPing())) : "");
        pm.registerPlayerPlaceholder("%chatalyst_ping_bars%", 1000, p ->
                p.getPlayer() instanceof Player bukkit
                        ? toTabFormat(Placeholders.pingBars(bukkit.getPing())) : "");
    }

    /**
     * Pone el apodo como nombre de tab list via la API de TAB (null lo restaura al de su
     * config). Devuelve false si TAB todavia no cargo al jugador (reintentar luego).
     */
    static boolean setTabName(Player player, String nickMini) {
        TabListFormatManager fm = TabAPI.getInstance().getTabListFormatManager();
        if (fm == null) return true; // tablist-name-formatting apagado en TAB: nada que hacer
        TabPlayer tp = TabAPI.getInstance().getPlayer(player.getUniqueId());
        if (tp == null) return false;
        fm.setName(tp, nickMini != null ? toTabFormat(nickMini) : null);
        return true;
    }
}
