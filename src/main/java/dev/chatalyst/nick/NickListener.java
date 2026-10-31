package dev.chatalyst.nick;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carga el apodo del jugador al entrar y libera la cache al salir. */
public final class NickListener implements Listener {

    private final NickManager nicks;

    public NickListener(NickManager nicks) {
        this.nicks = nicks;
    }

    // LOWEST al entrar y MONITOR al salir: el apodo debe estar cargado ANTES de que nadie
    // componga el mensaje de entrada, y seguir disponible HASTA que se componga el de
    // salida. Con la prioridad por defecto, liberar la cache antes de tiempo hacia que el
    // adios anunciara el nombre REAL de quien tuviera /nick (o /anonymous).
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        nicks.loadPlayer(event.getPlayer());
        reclaim(event.getPlayer());
    }

    /**
     * Le devuelve su nombre al que entra, quitandoselo a quien lo llevara puesto.
     *
     * <p>Se hace ANTES de que se componga el mensaje de entrada (por eso el listener es
     * LOWEST): si no, el servidor anunciaria la llegada de dos jugadores con el mismo
     * nombre a la vez.
     */
    private void reclaim(org.bukkit.entity.Player arrival) {
        for (java.util.UUID id : nicks.reclaimNameFor(arrival)) {
            org.bukkit.entity.Player stripped = org.bukkit.Bukkit.getPlayer(id);
            if (stripped != null) {
                stripped.sendMessage(dev.chatalyst.lang.Lang.get(stripped, "nick.reclaimed"));
            }
            String who = stripped != null ? stripped.getName() : id.toString();
            org.bukkit.Bukkit.getLogger().info("[Chatalyst] Released the nickname of "
                    + who + ": it belongs to " + arrival.getName() + ", who just joined.");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nicks.unloadPlayerLater(event.getPlayer());
    }
}
