package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleMutationContext;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired after a new unlock has been durably persisted. */
public final class TitleUnlockEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final TitleDefinition title;
    private final TitleMutationContext context;

    public TitleUnlockEvent(UUID playerId, TitleDefinition title,
                            TitleMutationContext context) {
        super(ApiEventThread.asynchronous());
        this.playerId = playerId;
        this.title = title;
        this.context = context;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public TitleDefinition getTitle() {
        return title;
    }

    public TitleMutationContext getContext() {
        return context;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
