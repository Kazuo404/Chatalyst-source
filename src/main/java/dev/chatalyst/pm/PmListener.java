package dev.chatalyst.pm;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carga la lista de ignorados al entrar y libera el estado de sesion al salir. */
public final class PmListener implements Listener {

    private final PrivateMessages pm;

    public PmListener(PrivateMessages pm) {
        this.pm = pm;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        pm.loadPlayer(event.getPlayer());
        pm.autoEnableSpy(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pm.unloadPlayer(event.getPlayer());
    }
}
