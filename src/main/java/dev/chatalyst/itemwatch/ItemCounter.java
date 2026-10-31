package dev.chatalyst.itemwatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cuenta, sin servidor, lo que un conjunto de stacks suma para cada regla. */
public final class ItemCounter {

    /**
     * Hasta donde se mira dentro de contenedores: un shulker dentro de un bundle dentro de un
     * shulker ya es mas de lo que el juego deja hacer, y un limite evita recursiones raras.
     */
    static final int MAX_DEPTH = 3;

    private ItemCounter() {
    }

    /** Total ponderado por regla (id -> cantidad), contando tambien dentro de shulkers/bundles. */
    public static Map<String, Long> totals(List<WatchRule> rules, List<WatchedStack> stacks) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (WatchRule rule : rules) out.put(rule.id(), 0L);
        for (WatchedStack stack : stacks) add(rules, stack, out, 0);
        return out;
    }

    private static void add(List<WatchRule> rules, WatchedStack stack, Map<String, Long> out,
                            int depth) {
        if (stack == null || stack.amount() <= 0) return;
        for (WatchRule rule : rules) {
            int weight = rule.weightOf(stack);
            if (weight > 0) out.merge(rule.id(), (long) weight * stack.amount(), Long::sum);
        }
        if (depth >= MAX_DEPTH) return;
        // Lo de dentro cuenta una vez por cada contenedor del stack (un stack de shulkers
        // iguales no existe en vanilla, pero un dupe si lo fabrica).
        for (int copy = 0; copy < Math.min(stack.amount(), 64); copy++) {
            for (WatchedStack inner : stack.contents()) add(rules, inner, out, depth + 1);
        }
    }

    /** Como {@link #totals} pero sin mirar dentro de shulkers/bundles (lo que esta "a la vista"). */
    public static Map<String, Long> topLevelTotals(List<WatchRule> rules,
                                                   List<WatchedStack> stacks) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (WatchRule rule : rules) out.put(rule.id(), 0L);
        for (WatchedStack stack : stacks) {
            if (stack == null || stack.amount() <= 0) continue;
            for (WatchRule rule : rules) {
                int weight = rule.weightOf(stack);
                if (weight > 0) out.merge(rule.id(), (long) weight * stack.amount(), Long::sum);
            }
        }
        return out;
    }

    /**
     * Stacks con mas unidades de las que el juego permite (64 espadas en un hueco, 127
     * perlas): ningun jugador legitimo los consigue, son la marca clasica de un dupe o de un
     * cliente modificado. Mira tambien dentro de contenedores.
     */
    public static List<WatchedStack> overstacked(List<WatchedStack> stacks) {
        List<WatchedStack> out = new ArrayList<>();
        for (WatchedStack stack : stacks) collectOverstacked(stack, out, 0);
        return out;
    }

    private static void collectOverstacked(WatchedStack stack, List<WatchedStack> out, int depth) {
        if (stack == null) return;
        if (stack.maxStack() > 0 && stack.amount() > stack.maxStack()) out.add(stack);
        if (depth >= MAX_DEPTH) return;
        for (WatchedStack inner : stack.contents()) collectOverstacked(inner, out, depth + 1);
    }
}
