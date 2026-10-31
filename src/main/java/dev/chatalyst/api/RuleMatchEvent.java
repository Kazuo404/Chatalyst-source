package dev.chatalyst.api;

import dev.chatalyst.rules.RuleContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a rule and all its conditions match, immediately before cooldown/actions. */
public final class RuleMatchEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String rule;
    private final RuleContext context;
    private final String originalMessage;
    private final String currentMessage;
    private boolean cancelled;

    public RuleMatchEvent(Player player, String rule, RuleContext context,
            String originalMessage, String currentMessage) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.rule = rule;
        this.context = context;
        this.originalMessage = originalMessage;
        this.currentMessage = currentMessage;
    }

    public Player getPlayer() { return player; }
    public String getRule() { return rule; }
    public RuleContext getContext() { return context; }
    public String getOriginalMessage() { return originalMessage; }
    public String getCurrentMessage() { return currentMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
