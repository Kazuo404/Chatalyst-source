package dev.chatalyst.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Carga el color de chat del jugador al entrar y libera la cache al salir. */
public final class ChatColorListener implements Listener {

    private final ChatColorManager colors;

    public ChatColorListener(ChatColorManager colors) {
        this.colors = colors;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        colors.loadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        colors.unloadPlayer(event.getPlayer());
    }
}
