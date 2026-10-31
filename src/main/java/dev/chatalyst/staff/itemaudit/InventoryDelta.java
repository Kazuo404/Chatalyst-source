package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Que entro en un inventario entre dos fotos: lo que dejo un /give, un kit o un /loot.
 *
 * <p>Por slot: un slot vacio que ahora tiene algo, uno que cambio de objeto, o uno que tiene
 * mas del MISMO objeto. Lo que bajo o desaparecio no interesa (no es una entrega).
 */
final class InventoryDelta {

    /** El slot {@code slot} recibio {@code amount} unidades; el stack actual entero es {@code now}. */
    record Added<T>(int slot, int amount, T now) {
    }

    private InventoryDelta() {
    }

    /**
     * @param wanted solo cuentan los stacks que lo cumplan (p. ej. el material del /give,
     *               para no confundir la entrega con algo que recogio del suelo en ese tick)
     */
    static <T> List<Added<T>> added(List<T> before, List<T> after, BiPredicate<T, T> similar,
                                    ToIntFunction<T> amount, Predicate<T> wanted) {
        List<Added<T>> out = new ArrayList<>();
        int size = Math.min(before.size(), after.size());
        for (int slot = 0; slot < size; slot++) {
            T now = after.get(slot);
            if (now == null || !wanted.test(now)) continue;
            T was = before.get(slot);
            int gained;
            if (was == null || !similar.test(was, now)) {
                gained = amount.applyAsInt(now);
            } else {
                gained = amount.applyAsInt(now) - amount.applyAsInt(was);
            }
            if (gained > 0) out.add(new Added<>(slot, gained, now));
        }
        return out;
    }
}
