package dev.chatalyst.itemwatch;

import java.util.List;
import java.util.Map;

/**
 * Un sitio donde un jugador guarda cosas (inventario, ender chest, una mochila) y lo que suma
 * en el para cada regla. Es lo que permite al /itemwatch scan decir DONDE esta cada cosa, no
 * solo cuanto hay en total.
 *
 * @param number   numero de mochila (0 si no es una mochila)
 * @param live     la mochila estaba abierta al leerla: el contenido es el de ese momento
 * @param totals   regla -&gt; cantidad en este sitio (contando dentro de shulkers/bundles)
 * @param nested   regla -&gt; parte de esa cantidad que va DENTRO de shulkers/bundles
 */
public record WatchSource(Kind kind, int number, boolean live, List<WatchedStack> stacks,
                          Map<String, Long> totals, Map<String, Long> nested,
                          List<WatchedStack> overstacked) {

    public enum Kind { INVENTORY, ENDER_CHEST, VAULT }

    /** Cuenta este sitio con las reglas dadas. */
    static WatchSource of(Kind kind, int number, boolean live, List<WatchedStack> stacks,
                          List<WatchRule> rules, boolean detectOverstack) {
        Map<String, Long> totals = ItemCounter.totals(rules, stacks);
        Map<String, Long> top = ItemCounter.topLevelTotals(rules, stacks);
        Map<String, Long> nested = new java.util.LinkedHashMap<>();
        for (var entry : totals.entrySet()) {
            nested.put(entry.getKey(), entry.getValue() - top.getOrDefault(entry.getKey(), 0L));
        }
        return new WatchSource(kind, number, live, List.copyOf(stacks), Map.copyOf(totals),
                Map.copyOf(nested),
                detectOverstack ? ItemCounter.overstacked(stacks) : List.of());
    }

    long total(String ruleId) {
        return totals.getOrDefault(ruleId, 0L);
    }

    long nested(String ruleId) {
        return nested.getOrDefault(ruleId, 0L);
    }
}
