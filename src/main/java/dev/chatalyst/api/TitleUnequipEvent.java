package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleMutationContext;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/** Fired after a title selection has been durably removed. */
public final class TitleUnequipEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String previousTitleId;
    private final TitleDefinition previousTitle;
    private final TitleMutationContext context;

    public TitleUnequipEvent(UUID playerId, String previousTitleId,
                             TitleDefinition previousTitle,
                             TitleMutationContext context) {
        super(ApiEventThread.asynchronous());
        this.playerId = playerId;
        this.previousTitleId = previousTitleId == null ? "" : previousTitleId;
        this.previousTitle = previousTitle;
        this.context = context;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPreviousTitleId() {
        return previousTitleId;
    }

    public Optional<TitleDefinition> getPreviousTitle() {
        return Optional.ofNullable(previousTitle);
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
