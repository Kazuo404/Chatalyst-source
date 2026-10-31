package dev.chatalyst.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Motores de script compartidos entre hilos: cada uno lo usa un solo hilo a la vez.
 *
 * <p>Un motor por hilo crearia uno en cada hilo que pase por el chat, y cada motor pesa decenas de
 * megas. Un candado global, en cambio, pone a todo el chat en fila. El tope da paralelismo sin
 * crecer sin limite. Los motores se crean al necesitarlos.
 */
final class ScriptEnginePool<T> {

    private final int max;
    private final Supplier<T> factory;
    private final Deque<T> idle = new ArrayDeque<>();
    private int created;
    private long generation;

    ScriptEnginePool(int max, Supplier<T> factory) {
        this.max = Math.max(1, max);
        this.factory = factory;
    }

    /** Un motor para este hilo, o null si todos siguen ocupados pasado {@code timeoutMillis}. */
    T borrow(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (this) {
            while (idle.isEmpty() && created >= max) {
                long waitMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (waitMillis <= 0) return null;
                wait(waitMillis);
            }
            T ready = idle.pollFirst();
            if (ready != null) return ready;
            created++;
        }
        try {
            return factory.get();
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                created--;
                notifyAll();
            }
            throw failure;
        }
    }

    /** Devuelve un motor. Uno prestado antes de {@link #clear()} se descarta. */
    synchronized void release(T engine, long borrowedGeneration) {
        if (borrowedGeneration == generation) {
            idle.addFirst(engine);
        }
        notifyAll();
    }

    synchronized long generation() {
        return generation;
    }

    /** Olvida todos los motores: lo compilado con la configuracion anterior deja de usarse. */
    synchronized void clear() {
        generation++;
        idle.clear();
        created = 0;
        notifyAll();
    }

    synchronized int created() {
        return created;
    }
}
