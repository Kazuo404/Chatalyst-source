package dev.chatalyst.staff;

import java.util.Arrays;
import java.util.function.BiPredicate;

/**
 * Fusion a tres bandas de una edicion de inventario: lo que habia al ABRIR la sesion, lo que
 * dejo el staff al CERRARLA y lo que tiene el objetivo AHORA.
 *
 * <p>Escribir la copia entera de vuelta ("la ultima escritura gana") borraba lo que el objetivo
 * hubiera conseguido mientras tanto y le devolvia lo que ya habia gastado o tirado: minaba 64
 * diamantes y desaparecian; le pasaba la espada a un amigo y la espada volvia — duplicada. Aqui
 * solo se escriben los slots que el staff cambio, y solo si el objetivo no los toco tambien. Un
 * slot que tocaron los dos es un conflicto: manda el objetivo, y el intercambio del staff en ese
 * slot se revierte aparte. Generico para poder testearlo sin servidor.
 */
final class SlotMerge {

    /**
     * @param merged    lo que hay que escribir en el objetivo (copia de {@code live} con los
     *                  cambios del staff que no chocan)
     * @param conflicts por slot, true si staff y objetivo cambiaron el mismo slot
     */
    record Result<T>(T[] merged, boolean[] conflicts) {

        boolean anyConflict() {
            for (boolean c : conflicts) if (c) return true;
            return false;
        }

        boolean changesLive(T[] live, BiPredicate<T, T> same) {
            for (int i = 0; i < live.length; i++) if (!same.test(live[i], merged[i])) return true;
            return false;
        }
    }

    private SlotMerge() {
    }

    /**
     * Fusiona los {@code slots} primeros slots. Los tres arrays deben tener al menos ese tamano;
     * lo que haya detras (paneles de relleno de la vista) se deja como esta en {@code live}.
     */
    static <T> Result<T> merge(T[] original, T[] edited, T[] live, int slots,
                               BiPredicate<T, T> same) {
        T[] merged = Arrays.copyOf(live, live.length);
        boolean[] conflicts = new boolean[slots];
        for (int i = 0; i < slots; i++) {
            if (same.test(original[i], edited[i])) continue;      // el staff no lo toco
            if (same.test(original[i], live[i])) {
                merged[i] = edited[i];                            // solo lo toco el staff
            } else if (!same.test(edited[i], live[i])) {
                conflicts[i] = true;                              // lo tocaron los dos
            }
            // edited == live: los dos llegaron a lo mismo, nada que escribir.
        }
        return new Result<>(merged, conflicts);
    }

    /**
     * Lo que hay que DEVOLVER (+) o RETIRAR (-) al staff para que la edicion no cree ni
     * destruya nada, por clave de item.
     *
     * <p>Conservacion: lo que el staff metio o saco de la copia ({@code edited - original}) tiene
     * que ser exactamente lo que el objetivo gano o perdio con la escritura ({@code merged -
     * live}); la diferencia es del staff. Mirar solo los slots en conflicto se dejaba el caso en
     * que los dos llegan "a lo mismo": el staff saca la espada y el objetivo, a la vez, la tira
     * al suelo (o muere y la suelta). El slot queda vacio en los dos lados, no hay conflicto, y
     * la espada existia DOS veces — una en el staff y otra en el suelo. Al reves, si los dos
     * meten el mismo item en el mismo hueco, el del staff desaparecia.
     *
     * @param key    clave del item (normalizada a cantidad 1); null para un hueco vacio
     * @param amount cantidad del stack
     */
    static <T, K> java.util.Map<K, Integer> staffBalance(T[] original, T[] edited, T[] live,
            T[] merged, int slots, java.util.function.Function<T, K> key,
            java.util.function.ToIntFunction<T> amount) {
        java.util.Map<K, Integer> balance = new java.util.HashMap<>();
        for (int i = 0; i < slots; i++) {
            add(balance, edited[i], 1, key, amount);
            add(balance, original[i], -1, key, amount);
            add(balance, live[i], 1, key, amount);
            add(balance, merged[i], -1, key, amount);
        }
        balance.values().removeIf(value -> value == 0);
        return balance;
    }

    private static <T, K> void add(java.util.Map<K, Integer> balance, T item, int sign,
                                   java.util.function.Function<T, K> key,
                                   java.util.function.ToIntFunction<T> amount) {
        if (item == null) return;
        K k = key.apply(item);
        if (k == null) return;
        balance.merge(k, sign * amount.applyAsInt(item), Integer::sum);
    }
}
