package dev.chatalyst.channel;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frecuencias del chat local por RADIO. En vez de un alcance en bloques, cada jugador sintoniza
 * una frecuencia {@code NN.NN} (de 10.00 a 99.99) y solo se oye con quienes esten en la MISMA
 * frecuencia — sin limite de distancia. Es como una radio: eliges tu canal y hablas con los
 * demas de ese canal.
 *
 * <p>La frecuencia se guarda internamente en CENTESIMAS (int 1000..9999) para no arrastrar
 * errores de coma flotante. La cache es estatica y thread-safe: el chat es asincrono y consulta
 * {@link #same} sin tocar disco (mismo patron que {@link RegionPartyTracker} con las posiciones);
 * las escrituras (sintonizar) ocurren en el hilo del jugador y persisten de forma diferida.
 */
public final class FrequencyManager {

    /** Limites del formato NN.NN: 10.00 (parte entera de 2 digitos) a 99.99. */
    public static final int MIN = 1000;
    public static final int MAX = 9999;
    /** 0 = frecuencia DESACTIVADA: el jugador no oye ni habla por radio hasta sintonizar una. */
    public static final int OFF = 0;
    public static final int STEP_UNIT = 1;    // 0.01
    public static final int STEP_TENTH = 10;  // 0.10
    public static final int STEP_ONE = 100;   // 1.00
    public static final int STEP_TEN = 1000;  // 10.00

    // Frecuencia sintonizada por jugador (centesimas; 0 = desactivada). Estatico para el async.
    private static final Map<UUID, Integer> TUNED = new ConcurrentHashMap<>();
    private static volatile int defaultFreq = OFF;

    // Jugadores escribiendo la frecuencia por chat (desde la GUI). Se reabre al terminar.
    private final java.util.Set<UUID> composing = ConcurrentHashMap.newKeySet();

    private final JavaPlugin plugin;
    private final Store store;

    public FrequencyManager(JavaPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("frequencies");
    }

    public void startComposing(UUID id) {
        composing.add(id);
    }

    public boolean isComposing(UUID id) {
        return composing.contains(id);
    }

    public void endComposing(UUID id) {
        composing.remove(id);
    }

    public void load() {
        // Default vacio (o invalido) = DESACTIVADA: nadie arranca en una frecuencia, cada quien
        // la activa cuando quiere. Si el dueño pone "10.00", todos empiezan ahi.
        Integer d = parse(plugin.getConfig().getString("Channels.Frequency.Default", ""));
        defaultFreq = d != null ? d : OFF;
    }

    /** Carga la frecuencia guardada del jugador (o el default) a la cache. */
    public void loadPlayer(UUID id) {
        int freq = defaultFreq;
        String raw = store.get(id.toString());
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v == OFF || (v >= MIN && v <= MAX)) freq = v;
            } catch (NumberFormatException ignore) {
                // valor corrupto: se queda el default
            }
        }
        TUNED.put(id, freq);
    }

    public void unloadPlayer(UUID id) {
        TUNED.remove(id);
        composing.remove(id);
    }

    /** Sintoniza al jugador en {@code cents} (0 = desactivar; el resto se capa a [MIN, MAX]). */
    public boolean set(UUID id, int cents) {
        int v = cents == OFF ? OFF : Math.max(MIN, Math.min(MAX, cents));
        if (!store.putDurably(id.toString(), String.valueOf(v))) return false;
        TUNED.put(id, v);
        return true;
    }

    /** Frecuencia sintonizada del jugador en centesimas (0 = desactivada). */
    public static int tuned(UUID id) {
        return TUNED.getOrDefault(id, defaultFreq);
    }

    /** True si el jugador tiene una frecuencia activa (no desactivada). */
    public static boolean active(UUID id) {
        return tuned(id) != OFF;
    }

    /**
     * True si ambos estan en la MISMA frecuencia ACTIVA (lo usa el filtro del chat async). Dos
     * jugadores sin frecuencia (desactivada) NO se oyen entre si.
     */
    public static boolean same(UUID a, UUID b) {
        int fa = tuned(a);
        return fa != OFF && fa == tuned(b);
    }

    public int defaultFrequency() {
        return defaultFreq;
    }

    // ---- Parseo / formato (sin Bukkit; testeable) -------------------------------------------

    /** "10.25" / "10,25" -&gt; 1025 centesimas; null si no es un numero o esta fuera de rango. */
    public static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            double d = Double.parseDouble(t);
            int cents = (int) Math.round(d * 100.0);
            return (cents >= MIN && cents <= MAX) ? cents : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 1025 -&gt; "10.25" (siempre 2 digitos enteros y 2 decimales). */
    public static String format(int cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }
}
