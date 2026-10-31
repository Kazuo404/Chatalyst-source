package dev.chatalyst.rules;

import org.bukkit.entity.Player;

/** Executes an administrator-authored, precompiled boolean condition for a rule. */
@FunctionalInterface
public interface RuleScriptEvaluator {

    boolean test(String script, Player player, String message, RuleContext context);
}
