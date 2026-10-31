package dev.chatalyst.db;

import java.util.function.UnaryOperator;

/**
 * Read-modify-write seguro entre servidores sobre un {@link Store}: lee el valor actual,
 * aplica la mutacion y la escribe con {@link Store#compareAndPut}. Si otro servidor toco
 * la misma clave entre medias, el CAS falla, el cache se refresca con el valor real y se
 * reintenta sobre datos frescos.
 */
public final class StoreCas {

    /** Reintentos por defecto: contencion real en estas claves es rara y corta. */
    public static final int DEFAULT_ATTEMPTS = 5;

    private StoreCas() {
    }

    /**
     * Aplica {@code mutation} sobre el valor actual de {@code key} (recibe null si la clave
     * no existe) y persiste el resultado de forma atomica.
     *
     * @param mutation devuelve el nuevo valor, o null para abortar sin escribir.
     * @return true si se escribio; false si la mutacion aborto o se agotaron los reintentos.
     */
    public static boolean update(Store store, String key, UnaryOperator<String> mutation) {
        for (int attempt = 0; attempt < DEFAULT_ATTEMPTS; attempt++) {
            String current = store.get(key);
            String next = mutation.apply(current);
            if (next == null) return false;
            if (store.compareAndPut(key, current, next)) return true;
            // CAS fallido: compareAndPut ya refresco el cache; el siguiente intento
            // vuelve a leer y muta el valor que realmente hay en la base de datos.
        }
        return false;
    }

    /** Igual que {@link #update}, pero no devuelve exito hasta cruzar la barrera durable. */
    public static boolean updateDurably(Store store, String key, UnaryOperator<String> mutation) {
        for (int attempt = 0; attempt < DEFAULT_ATTEMPTS; attempt++) {
            String current = store.get(key);
            String next = mutation.apply(current);
            if (next == null) return false;
            if (store.compareAndPutDurably(key, current, next)) return true;
        }
        return false;
    }

    /** Inserta solo si la clave sigue ausente y cruza la barrera durable. */
    public static boolean putIfAbsentDurably(Store store, String key, String value) {
        if (store.compareAndPutDurably(key, null, value)) return true;
        return java.util.Objects.equals(store.get(key), value);
    }
}
