package dev.chatalyst.api;

import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleMutationContext;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/** Fired after an equipped-title selection has been durably persisted. */
public final class TitleEquipEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String previousTitleId;
    private final TitleDefinition previousTitle;
    private final TitleDefinition title;
    private final TitleMutationContext context;

    public TitleEquipEvent(UUID playerId, String previousTitleId,
                           TitleDefinition previousTitle, TitleDefinition title,
                           TitleMutationContext context) {
        super(ApiEventThread.asynchronous());
        this.playerId = playerId;
        this.previousTitleId = previousTitleId == null ? "" : previousTitleId;
        this.previousTitle = previousTitle;
        this.title = title;
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
