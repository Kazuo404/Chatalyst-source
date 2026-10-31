package dev.chatalyst.tablist;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Formatea la lista de tab: el nombre de cada jugador (con su rango/prefijo) y el
 * header/footer. Se refresca periodicamente para reflejar cambios (ping, rango, etc.).
 */
public final class TabListManager {

    private final JavaPlugin plugin;
    private boolean enabled;
    private String nameFormat;
    private List<String> header;
    private List<String> footer;
    private int intervalSeconds;
    private ScheduledTask task;
    /** Mientras el reloj de animacion manda, el refresco lento solo toca header/footer. */
    private volatile boolean animatedOverride;

    public TabListManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        stop();
        animatedOverride = false;
        enabled = plugin.getConfig().getBoolean("Tab_List.Enabled", false);
        nameFormat = dev.chatalyst.config.ConfigFiles.string(plugin.getConfig(),
                "Tab_List.Name_Format", "{player_prefix}{player_name}");
        header = plugin.getConfig().getStringList("Tab_List.Header");
        footer = plugin.getConfig().getStringList("Tab_List.Footer");
        intervalSeconds = Math.max(1, plugin.getConfig().getInt("Tab_List.Update_Interval", 10));
    }

    public void start() {
        if (!enabled) return;
        // Si TAB (NEZNAMY) esta instalado, la tab list es suya: no competimos con el.
        if (dev.chatalyst.hook.TabHook.isAvailable()) {
            plugin.getLogger().warning("Tab_List is enabled but TAB is installed: skipping the "
                    + "Chatalyst tab list to avoid conflicts. Use TAB's config (it can show "
                    + "%chatalyst_nick% and the other Chatalyst placeholders).");
            return;
        }
        // El scheduler global corre en el hilo principal: seguro para tocar la tab.
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> updateAll(), 20L, intervalSeconds * 20L);
        updateAll();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        shownNames.clear();
        shownHeaders.clear();
    }

    public void updateAll() {
        // Al salir no hay evento propio aqui: se limpia en cada vuelta. Una entrada vieja no
        // puede dejar nada sin pintar porque se compara tambien con lo que el servidor tiene.
        forgetOffline();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            update(p);
        }
    }

    public void update(Player p) {
        if (!enabled) return;
        FoliaTasks.entity(plugin, p, this::updateNow);
    }

    private void updateNow(Player p) {
        // Variables construidas UNA vez por jugador y refresco: antes cada linea del
        // header/footer (y el nombre) reconstruia el mapa base entero con sus lookups —
        // con 100 jugadores y 6 lineas, 600 construcciones por ciclo de 10 s.
        java.util.Map<String, String> vars = Placeholders.merged(p, null);
        if (!animatedOverride) {
            setListName(p, MM.full(Legacy.toMini(resolve(p, nameFormat, vars))));
        }
        if (!header.isEmpty() || !footer.isEmpty()) {
            setHeaderFooter(p, lines(p, header, vars), lines(p, footer, vars));
        }
    }

    void setAnimatedOverride(boolean active) {
        animatedOverride = active;
    }

    /** Ya corre en el scheduler de entidad del jugador desde FancyNametagManager. */
    void showAnimatedName(Player p, String miniMessage) {
        if (!enabled || dev.chatalyst.hook.TabHook.isAvailable() || !animatedOverride) return;
        setListName(p, MM.full(miniMessage));
    }

    /**
     * Lo ultimo que se le puso a cada jugador: lo que mandamos y lo que el servidor devolvio
     * justo despues. Cada cambio del nombre se reenvia a TODOS los jugadores (200 x 200 cada
     * refresco), asi que repetir el mismo valor era casi todo el trafico de la tab.
     *
     * <p>Se guarda tambien lo leido tras ponerlo: si otro plugin cambia el nombre o el
     * header, lo leido ya no coincide y se vuelve a poner el nuestro, como antes.
     */
    private record Shown(Component sent, Component observed) {
    }

    private record ShownHeader(Component header, Component footer,
                               Component observedHeader, Component observedFooter) {
    }

    private final java.util.Map<java.util.UUID, Shown> shownNames =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, ShownHeader> shownHeaders =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void setListName(Player p, Component name) {
        java.util.UUID id = p.getUniqueId();
        Shown last = shownNames.get(id);
        if (last != null && last.sent().equals(name)
                && java.util.Objects.equals(last.observed(), p.playerListName())) return;
        p.playerListName(name);
        shownNames.put(id, new Shown(name, p.playerListName()));
    }

    private void setHeaderFooter(Player p, Component header, Component footer) {
        java.util.UUID id = p.getUniqueId();
        ShownHeader last = shownHeaders.get(id);
        if (last != null && last.header().equals(header) && last.footer().equals(footer)
                && java.util.Objects.equals(last.observedHeader(), p.playerListHeader())
                && java.util.Objects.equals(last.observedFooter(), p.playerListFooter())) return;
        p.sendPlayerListHeaderAndFooter(header, footer);
        shownHeaders.put(id, new ShownHeader(header, footer,
                p.playerListHeader(), p.playerListFooter()));
    }

    /** Olvida lo enviado a quien ya no esta conectado (lo llama cada refresco global). */
    private void forgetOffline() {
        java.util.Set<java.util.UUID> online = new java.util.HashSet<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) online.add(p.getUniqueId());
        shownNames.keySet().retainAll(online);
        shownHeaders.keySet().retainAll(online);
    }

    private Component lines(Player p, List<String> raw, java.util.Map<String, String> vars) {
        Component out = Component.empty();
        for (int i = 0; i < raw.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(MM.full(Legacy.toMini(resolve(p, raw.get(i), vars))));
        }
        return out;
    }

    // Emojis/imagenes de ItemsAdder de la PLANTILLA (staff, sin chequeo de permisos) ANTES
    // de los placeholders, para no convertir sin permisos texto que controla un jugador.
    private static String resolve(Player p, String format, java.util.Map<String, String> vars) {
        return Placeholders.applyPrepared(p,
                dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(format), vars);
    }
}
