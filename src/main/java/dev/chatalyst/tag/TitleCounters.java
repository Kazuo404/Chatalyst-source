package dev.chatalyst.tag;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contadores por jugador para los titulos (regla {@code COUNTER}, 2026-10-18): lunas de sangre
 * sobrevividas, jefes derrotados, votos... Los suman otros plugins o eventos con
 * {@code /tagcontador}. Se guardan en el Store {@code title_counters}, una fila por jugador
 * ({@code clave=valor,clave=valor}), con CAS para que dos servidores de la red no se pisen una suma.
 *
 * <p>La instancia activa se publica en {@link #install} para que {@link TagRequirement} (un
 * record sin acceso al plugin) pueda leer el progreso sin cambiar su firma.
 */
public final class TitleCounters {

    /** Nombre del Store compartido. */
    public static final String STORE = "title_counters";
    /** Formato de una clave de contador. */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");

    private static volatile TitleCounters active;

    private final Store store;

    public TitleCounters(Store store) {
        this.store = store;
    }

    public static TitleCounters fromStorage(StorageManager storage) {
        return new TitleCounters(storage.store(STORE));
    }

    /** Publica (o con null retira) la instancia que leen las reglas COUNTER. */
    public static void install(TitleCounters counters) {
        active = counters;
    }

    public static TitleCounters active() {
        return active;
    }

    /** Valor de la instancia activa; 0 si todavia no hay ninguna (tests, arranque). */
    static long current(UUID player, String key) {
        TitleCounters counters = active;
        return counters == null ? 0L : counters.get(player, key);
    }

    /** Clave en minusculas y sin espacios alrededor ("" si es null). */
    public static String normalizeKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validKey(String raw) {
        return raw != null && KEY.matcher(raw).matches();
    }

    public long get(UUID player, String key) {
        if (player == null || !validKey(key)) return 0L;
        return decode(store.get(player.toString())).getOrDefault(key, 0L);
    }

    /** Todos los contadores del jugador, ordenados por clave. */
    public Map<String, Long> all(UUID player) {
        if (player == null) return Map.of();
        return Collections.unmodifiableMap(decode(store.get(player.toString())));
    }

    /**
     * Suma {@code delta} (puede ser negativo) al contador. Nunca baja de 0 ni desborda.
     *
     * @return el valor nuevo, o vacio si la clave no es valida o no se pudo guardar.
     */
    public OptionalLong add(UUID player, String key, long delta) {
        if (player == null || !validKey(key)) return OptionalLong.empty();
        String id = player.toString();
        for (int attempt = 0; attempt < StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = store.get(id);
            TreeMap<String, Long> values = decode(observed);
            long next = applyDelta(values.getOrDefault(key, 0L), delta);
            if (next == 0L) values.remove(key);
            else values.put(key, next);
            String encoded = encode(values);
            if (encoded.equals(observed == null ? "" : observed)) return OptionalLong.of(next);
            boolean persisted = encoded.isEmpty()
                    ? store.compareAndRemoveDurably(id, observed)
                    : store.compareAndPutDurably(id, observed, encoded);
            if (persisted) return OptionalLong.of(next);
        }
        return OptionalLong.empty();
    }

    /** {@code current + delta} acotado a {@code [0, Long.MAX_VALUE]}. */
    static long applyDelta(long current, long delta) {
        long base = Math.max(0L, current);
        long sum = base + delta;
        if (delta > 0 && sum < base) return Long.MAX_VALUE; // desborde
        return Math.max(0L, sum);
    }

    static TreeMap<String, Long> decode(String raw) {
        TreeMap<String, Long> out = new TreeMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            if (!validKey(key)) continue;
            try {
                long value = Long.parseLong(part.substring(eq + 1).trim());
                if (value > 0L) out.put(key, value);
            } catch (NumberFormatException ignored) {
                // fila corrupta: se ignora esa clave, no el resto
            }
        }
        return out;
    }

    static String encode(Map<String, Long> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : new TreeMap<>(values).entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0L || !validKey(e.getKey())) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
