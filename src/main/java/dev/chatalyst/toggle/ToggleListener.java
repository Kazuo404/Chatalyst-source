package dev.chatalyst.toggle;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carga los toggles del jugador al entrar y libera el estado al salir. */
public final class ToggleListener implements Listener {

    private final ToggleManager toggles;

    public ToggleListener(ToggleManager toggles) {
        this.toggles = toggles;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        toggles.loadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        toggles.unloadPlayer(event.getPlayer());
    }
}
