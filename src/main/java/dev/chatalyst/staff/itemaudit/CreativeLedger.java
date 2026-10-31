package dev.chatalyst.staff.itemaudit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.BiPredicate;

/**
 * Distingue en creativo "mover un objeto propio" de "sacarlo de la nada".
 *
 * <p>El cliente en creativo no pide mover: dice "este slot ahora tiene X". Mover un stack
 * llega como dos mensajes (el slot de origen queda vacio, el de destino recibe el stack), y
 * sacar del selector como uno solo (un slot recibe algo que antes no estaba en ningun sitio).
 * El libro de cuentas apunta lo que sale de los slots y lo descuenta de lo que entra: lo que
 * entra sin haber salido antes es lo creado.
 *
 * <p>El compromiso: lo que sale se recuerda {@code windowMillis}. Tirar algo a la papelera
 * del creativo y sacar ese mismo objeto dentro de esa ventana no cuenta como creado (falso
 * negativo pequeño); mantener un objeto en el cursor mas que la ventana y soltarlo si cuenta
 * (falso positivo). Ninguno de los dos casos es el que se busca vigilar.
 */
final class CreativeLedger<K> {

    /** Tope de apuntes: un cliente que manda basura no hace crecer la memoria. */
    private static final int MAX_ENTRIES = 128;

    private final BiPredicate<K, K> similar;
    private final long windowMillis;
    private final Deque<Entry<K>> pool = new ArrayDeque<>();

    private static final class Entry<K> {
        final K item;
        int amount;
        final long time;

        Entry(K item, int amount, long time) {
            this.item = item;
            this.amount = amount;
            this.time = time;
        }
    }

    CreativeLedger(BiPredicate<K, K> similar, long windowMillis) {
        this.similar = similar;
        this.windowMillis = Math.max(0L, windowMillis);
    }

    /** Un slot perdio {@code amount} de {@code item}: queda disponible para un movimiento. */
    synchronized void removed(K item, int amount, long now) {
        expire(now);
        if (item == null || amount <= 0) return;
        pool.addLast(new Entry<>(item, amount, now));
        while (pool.size() > MAX_ENTRIES) pool.pollFirst();
    }

    /**
     * Un slot gano {@code amount} de {@code item}. Devuelve cuanto de eso NO venia de ningun
     * sitio (lo creado). Consume primero lo mas reciente: es lo que el jugador tiene en mano.
     */
    synchronized int added(K item, int amount, long now) {
        expire(now);
        if (item == null || amount <= 0) return 0;
        int missing = amount;
        Iterator<Entry<K>> newestFirst = pool.descendingIterator();
        while (missing > 0 && newestFirst.hasNext()) {
            Entry<K> entry = newestFirst.next();
            if (!similar.test(entry.item, item)) continue;
            int used = Math.min(missing, entry.amount);
            entry.amount -= used;
            missing -= used;
            if (entry.amount == 0) newestFirst.remove();
        }
        return missing;
    }

    synchronized void clear() {
        pool.clear();
    }

    synchronized int pending() {
        return pool.stream().mapToInt(entry -> entry.amount).sum();
    }

    private void expire(long now) {
        while (!pool.isEmpty() && now - pool.peekFirst().time > windowMillis) pool.pollFirst();
    }
}
