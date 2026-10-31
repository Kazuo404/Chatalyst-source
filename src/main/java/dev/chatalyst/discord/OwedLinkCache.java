package dev.chatalyst.discord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Memoria de "este jugador tiene algo de pago atascado esperando a Discord".
 *
 * <p>La respuesta sale de recorrer todas sus compras, y la puerta la pregunta cada segundo por
 * cada jugador conectado sin vincular: sin memoria eran cientos de miles de lecturas por
 * segundo en el hilo global (y consultas sincronas con SQL). Se olvida al entrar, al salir y
 * al cambiar el vinculo; la caducidad cubre lo que no avisa, como una compra nueva.
 */
final class OwedLinkCache {

    static final long TTL_MILLIS = 60_000L;

    private record Entry(boolean owes, long at) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    OwedLinkCache() {
        this(System::currentTimeMillis);
    }

    /** Test seam: el reloj es lo unico que la caducidad necesita del mundo. */
    OwedLinkCache(LongSupplier clock) {
        this.clock = clock;
    }

    boolean owes(UUID player, Predicate<UUID> lookup) {
        long now = clock.getAsLong();
        Entry cached = entries.get(player);
        if (cached != null && now - cached.at() < TTL_MILLIS) return cached.owes();
        boolean owes = lookup.test(player);
        entries.put(player, new Entry(owes, now));
        return owes;
    }

    void forget(UUID player) {
        if (player != null) entries.remove(player);
    }

    void clear() {
        entries.clear();
    }
}
