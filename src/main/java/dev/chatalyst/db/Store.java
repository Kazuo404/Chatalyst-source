package dev.chatalyst.db;

import java.util.Map;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Almacen clave-valor por jugador para un "namespace" (mutes, apodos, colores...). Cada
 * implementacion (YAML o SQL) mantiene un cache en memoria y persiste de forma diferida;
 * asi la lectura desde el chat async es segura y las escrituras no tocan disco/red en caliente.
 */
public interface Store {

    /** Valor del jugador, o null. */
    String get(String id);

    /** Todos los pares id -&gt; valor (copia). */
    Map<String, String> all();

    /** Guarda/actualiza el valor del jugador (persistencia diferida). */
    void put(String id, String value);

    /** Elimina el valor del jugador (persistencia diferida). */
    void remove(String id);

    /**
     * Escritura condicional ATOMICA contra el backend autoritativo (la fila de la base de
     * datos en SQL; la memoria en YAML): aplica {@code value} solo si el valor actual es
     * exactamente {@code expected} ({@code null} = "solo si no existe"). A diferencia de
     * {@link #put}, NO es diferida: el resultado refleja el estado real, asi dos servidores
     * de la red no pueden pisarse una transicion (p. ej. reclamar el mismo reporte).
     *
     * @return true si se aplico; false si el valor actual ya no coincidia (el cache local
     *         se refresca con el valor real para que el llamador pueda reintentar).
     */
    boolean compareAndPut(String id, String expected, String value);

    /**
     * Elimina la clave solo si su valor autoritativo sigue siendo {@code expected}. Sirve
     * para limpiezas/expiraciones: un dato nuevo escrito por otro hilo o servidor entre la
     * lectura y el borrado nunca debe desaparecer.
     *
     * @return true si se elimino; false si el valor cambio o fallo el backend (el cache se
     *         refresca con el valor real igual que en {@link #compareAndPut}).
     */
    boolean compareAndRemove(String id, String expected);

    /**
     * Variante crash-safe del CAS. El backend SQL puede confirmar directamente su sentencia;
     * YAML fuerza el archivo y revierte el cache si ese flush falla.
     */
    default boolean compareAndPutDurably(String id, String expected, String value) {
        if (!compareAndPut(id, expected, value)) return false;
        if (flushNow()) return true;
        if (expected == null) compareAndRemove(id, value);
        else compareAndPut(id, value, expected);
        flushNow();
        return false;
    }

    /** Variante crash-safe de {@link #compareAndRemove(String, String)}. */
    default boolean compareAndRemoveDurably(String id, String expected) {
        if (!compareAndRemove(id, expected)) return false;
        if (flushNow()) return true;
        compareAndPut(id, null, expected);
        flushNow();
        return false;
    }

    /**
     * Reemplazo durable con reintento CAS. A diferencia de {@link #put}, no confirma hasta
     * que el backend autoritativo contiene exactamente {@code value}; por eso es apropiado
     * para escrows, sanciones y migraciones que no pueden sobrevivir solo en memoria.
     */
    default boolean putDurably(String id, String value) {
        if (id == null || value == null) return false;
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String current = get(id);
            if (java.util.Objects.equals(current, value)) return flushNow();
            if (compareAndPutDurably(id, current, value)) return true;
        }
        return false;
    }

    /** Elimina durablemente el valor observado sin borrar una sustitucion concurrente. */
    default boolean removeDurably(String id) {
        if (id == null) return false;
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String current = get(id);
            if (current == null) return true;
            if (compareAndRemoveDurably(id, current)) return true;
        }
        return false;
    }

    /**
     * Observa filas refrescadas por una invalidacion remota. Los managers que mantienen
     * una segunda cache deben recargar esos ids; el cache interno del Store ya esta al dia
     * cuando se invoca al listener. Los backends locales no necesitan emitir eventos.
     */
    default void addInvalidationListener(Consumer<Collection<String>> listener) {
        // YAML no recibe invalidaciones de red.
    }

    /**
     * Fuerza la persistencia pendiente ahora mismo.
     *
     * @return {@code true} si todo el estado pendiente alcanzo el backend durable;
     *         {@code false} si sigue solo en memoria y debe reintentarse.
     */
    boolean flushNow();
}
