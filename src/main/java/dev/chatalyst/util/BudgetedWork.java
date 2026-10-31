package dev.chatalyst.util;

import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reparte una cola de trabajo en tramos de tiempo acotado, para no gastar un tick entero.
 *
 * <p>Cada elemento se procesa completo y en el orden de la cola: solo cambia cuando. Cada tramo
 * hace al menos uno, asi que la cola siempre avanza aunque un elemento tarde mas que el tope.
 */
public final class BudgetedWork {

    private BudgetedWork() {
    }

    /** @return true si la cola quedo vacia; false si queda trabajo para el siguiente tramo. */
    public static <T> boolean run(Queue<T> queue, Consumer<T> work, long budgetNanos, LongSupplier clock) {
        long deadline = clock.getAsLong() + budgetNanos;
        boolean first = true;
        while (!queue.isEmpty()) {
            if (!first && clock.getAsLong() - deadline >= 0) return false;
            first = false;
            work.accept(queue.poll());
        }
        return true;
    }
}
