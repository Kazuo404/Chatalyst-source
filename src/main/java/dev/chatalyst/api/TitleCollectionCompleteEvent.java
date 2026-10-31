package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleCollection;
import dev.chatalyst.api.title.TitleMutationContext;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired when a durable unlock completes a collection for the first time in that state. */
public final class TitleCollectionCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final TitleCollection collection;
    private final TitleMutationContext context;

    public TitleCollectionCompleteEvent(UUID playerId, TitleCollection collection,
                                        TitleMutationContext context) {
        super(ApiEventThread.asynchronous());
        this.playerId = playerId;
        this.collection = collection;
        this.context = context;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public TitleCollection getCollection() {
        return collection;
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
