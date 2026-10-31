package dev.chatalyst.api;

import dev.chatalyst.rules.RuleContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Lets integrations cancel or alter the final text produced by a replacement rule. */
public final class RuleReplaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String rule;
    private final RuleContext context;
    private final String before;
    private String result;
    private boolean cancelled;

    public RuleReplaceEvent(Player player, String rule, RuleContext context,
            String before, String result) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.rule = rule;
        this.context = context;
        this.before = before;
        this.result = Objects.requireNonNull(result, "result");
    }

    public Player getPlayer() { return player; }
    public String getRule() { return rule; }
    public RuleContext getContext() { return context; }
    public String getBefore() { return before; }
    public String getResult() { return result; }
    public void setResult(String value) { result = Objects.requireNonNull(value, "result"); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
