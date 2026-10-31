package dev.chatalyst.itemwatch;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Una regla de vigilancia: que objetos cuentan, cuanto pesa cada uno y a partir de cuanto es
 * sospechoso.
 *
 * <p>{@code weights} permite contar en una sola unidad: un bloque de diamante vale 9 diamantes,
 * asi que guardarlos en bloques no esquiva la regla. Con {@code weights} vacio cuenta cualquier
 * material, y entonces los filtros de nombre/lore son los que deciden (llaves de cajas, items
 * con nombre propio).
 *
 * @param max        cantidad total a partir de la cual se avisa (0 = sin tope)
 * @param gain       cuanto puede ganar en {@code gainWindowMillis} sin avisar (0 = no mira)
 */
public record WatchRule(String id, String displayName, Map<Material, Integer> weights,
                        List<String> nameContains, List<String> loreContains,
                        long max, long gain, long gainWindowMillis) {

    public WatchRule {
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        nameContains = lower(nameContains);
        loreContains = lower(loreContains);
    }

    private static List<String> lower(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank())
                        .map(v -> v.toLowerCase(Locale.ROOT)).toList();
    }

    /** Lo que vale UNA unidad de este stack para la regla (0 = no cuenta). */
    public int weightOf(WatchedStack stack) {
        int weight;
        if (weights.isEmpty()) {
            weight = 1;
        } else {
            Integer configured = weights.get(stack.type());
            if (configured == null) return 0;
            weight = configured;
        }
        if (!nameContains.isEmpty()
                && nameContains.stream().noneMatch(stack.name().toLowerCase(Locale.ROOT)::contains)) {
            return 0;
        }
        if (!loreContains.isEmpty()) {
            String lore = String.join("\n", stack.lore()).toLowerCase(Locale.ROOT);
            if (loreContains.stream().noneMatch(lore::contains)) return 0;
        }
        return weight;
    }

    /** Una regla sin nada que la limite contaria todo el inventario: se descarta al cargar. */
    public boolean matchesSomething() {
        return !weights.isEmpty() || !nameContains.isEmpty() || !loreContains.isEmpty();
    }
}
