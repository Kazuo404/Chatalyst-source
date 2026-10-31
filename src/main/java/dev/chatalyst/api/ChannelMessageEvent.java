package dev.chatalyst.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * API publica: se dispara cuando un mensaje de chat va a entregarse en un canal de
 * Chatalyst (despues del mute, las reglas y el router; antes de renderizarse). Es
 * ASINCRONO (hilo del chat) y cancelable: cancelarlo descarta el mensaje sin avisos.
 *
 * <p>Uso tipico de otros plugins: estadisticas por canal, filtros propios, logging.
 */
public final class ChannelMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final String channel;
    private final String message;
    private boolean cancelled;

    public ChannelMessageEvent(Player sender, String channel, String message) {
        // Segun el hilo real, no siempre async: el chat normal llega async, pero un chat FORZADO
        // por otro plugin (Player#chat, el "/sudo jugador c:hola" de Essentials) corre en el
        // hilo principal, y Bukkit rechaza con IllegalStateException un evento marcado async
        // que se dispara ahi — el mensaje no salia y la consola escupia la traza.
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.sender = sender;
        this.channel = channel;
        this.message = message;
    }

    public Player getSender() {
        return sender;
    }

    /** Nombre del canal por el que va a salir el mensaje (ya resuelto el router). */
    public String getChannel() {
        return channel;
    }

    /** Texto plano del mensaje. */
    public String getMessage() {
        return message;
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
