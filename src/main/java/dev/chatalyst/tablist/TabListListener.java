package dev.chatalyst.tablist;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Aplica el formato de la lista de tab en cuanto un jugador entra. */
public final class TabListListener implements Listener {

    private final TabListManager tab;

    public TabListListener(TabListManager tab) {
        this.tab = tab;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tab.update(event.getPlayer());
    }
}
