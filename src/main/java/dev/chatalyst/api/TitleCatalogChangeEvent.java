package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleCatalogChangeType;
import dev.chatalyst.api.title.TitleDefinition;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** Fired after a title definition change has been atomically written to {@code tags.yml}. */
public final class TitleCatalogChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TitleCatalogChangeType changeType;
    private final TitleDefinition previous;
    private final TitleDefinition current;

    public TitleCatalogChangeEvent(TitleCatalogChangeType changeType,
                                   TitleDefinition previous,
                                   TitleDefinition current) {
        super(ApiEventThread.asynchronous());
        this.changeType = changeType;
        this.previous = previous;
        this.current = current;
    }

    public TitleCatalogChangeType getChangeType() {
        return changeType;
    }

    public Optional<TitleDefinition> getPrevious() {
        return Optional.ofNullable(previous);
    }

    public Optional<TitleDefinition> getCurrent() {
        return Optional.ofNullable(current);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
