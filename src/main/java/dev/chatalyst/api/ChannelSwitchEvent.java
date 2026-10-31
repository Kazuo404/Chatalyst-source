package dev.chatalyst.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * API publica: se dispara cuando un jugador cambia su canal de escritura con /channel
 * (no incluye el auto-switch por region ni cambios administrativos). Cancelable: si se
 * cancela, el jugador se queda en su canal actual sin mensaje de Chatalyst.
 */
public final class ChannelSwitchEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String from;
    private final String to;
    private boolean cancelled;

    public ChannelSwitchEvent(Player player, String from, String to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public Player getPlayer() {
        return player;
    }

    /** Canal anterior ("" si no tenia). */
    public String getFrom() {
        return from;
    }

    /** Canal al que quiere cambiar. */
    public String getTo() {
        return to;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
