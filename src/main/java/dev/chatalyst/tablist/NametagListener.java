package dev.chatalyst.tablist;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Aplica el nametag al entrar y limpia su team al salir. */
public final class NametagListener implements Listener {

    private final NametagManager nametags;

    public NametagListener(NametagManager nametags) {
        this.nametags = nametags;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        nametags.update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nametags.remove(event.getPlayer());
    }
}
