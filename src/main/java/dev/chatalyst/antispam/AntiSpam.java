package dev.chatalyst.antispam;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-spam: fuerza un retardo minimo entre mensajes y bloquea mensajes repetidos/muy
 * parecidos consecutivos. La comprobacion es atomica (Map#compute) para no fallar cuando
 * dos mensajes llegan casi a la vez desde el pool de hilos de chat.
 */
public final class AntiSpam {

    /** Mantiene Levenshtein en O(512^2) incluso si otro plugin inyecta texto sin sanear. */
    static final int MAX_COMPARISON_LENGTH = 512;

    public enum Result { OK, TOO_FAST, DUPLICATE }

    private record Entry(String text, long time) {
    }

    /**
     * Los ultimos mensajes del jugador, no solo el ultimo. Con uno solo, alternar dos textos
     * (A, B, A, B...) nunca contaba como repeticion: cada mensaje solo se comparaba con el
     * inmediatamente anterior, que siempre era el otro.
     */
    private record History(List<Entry> recent, long lastTime) {
    }

    static final int MEMORY = 4;

    private final JavaPlugin plugin;
    private final Map<UUID, History> last = new ConcurrentHashMap<>();

    // Volatile: se leen desde el chat async mientras /reload los reescribe.
    private volatile boolean enabled;
    private volatile long delayMillis;
    private volatile double similarity;   // 0..1; 1 = solo bloquea identicos
    // Cuanto dura la memoria de "esto ya lo dijiste". 0 = para siempre (lo de antes).
    private volatile long repeatWindowMillis;
    // Por debajo de esto, repetir no es spam: es hablar.
    private volatile int minRepeatLength;
    private volatile String bypass;
    // Opcional: retardo distinto por grupo (Groups.List.<grupo>.Chat_Delay_Seconds).
    private volatile dev.chatalyst.chat.GroupOverrides groups;

    public AntiSpam(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Anti_Spam.Enabled", true);
        delayMillis = normalizeDelayMillis(
                plugin.getConfig().getDouble("Anti_Spam.Delay_Seconds", 1.5));
        similarity = normalizeSimilarity(
                plugin.getConfig().getDouble("Anti_Spam.Similarity", 0.9));
        repeatWindowMillis = normalizeRepeatWindowMillis(
                plugin.getConfig().getDouble("Anti_Spam.Repeat_Seconds", 30.0));
        minRepeatLength = normalizeMinRepeatLength(
                plugin.getConfig().getInt("Anti_Spam.Min_Repeat_Length", 5));
        bypass = plugin.getConfig().getString("Anti_Spam.Bypass_Permission", "chatalyst.bypass.spam");
    }

    public void setGroupOverrides(dev.chatalyst.chat.GroupOverrides groups) {
        this.groups = groups;
    }

    public Result check(Player player, String message) {
        if (!enabled) return Result.OK;
        if (player.isOp()) return Result.OK;
        if (bypass != null && !bypass.isEmpty() && player.hasPermission(bypass)) return Result.OK;

        long now = System.currentTimeMillis();
        // Retardo efectivo: el del grupo del jugador si esta definido, si no el global.
        long delay = delayMillis;
        if (groups != null) {
            var override = groups.forPlayer(player);
            if (override != null && override.delaySeconds() >= 0) {
                delay = normalizeDelayMillis(override.delaySeconds());
            }
        }
        final long effectiveDelay = delay;
        String norm = normalizeForComparison(message);
        Result[] result = {Result.OK};

        last.compute(player.getUniqueId(), (k, prev) -> {
            if (prev != null) {
                if (now - prev.lastTime() < effectiveDelay) {
                    result[0] = Result.TOO_FAST;
                    return prev; // no reiniciamos el reloj: evita que el spam se auto-prolongue
                }
                for (Entry earlier : prev.recent()) {
                    if (countsAsRepeat(norm, earlier, now) && isSimilar(norm, earlier.text())) {
                        result[0] = Result.DUPLICATE;
                        break;
                    }
                }
            }
            return remember(prev, new Entry(norm, now));
        });
        return result[0];
    }

    public void clear(UUID id) {
        last.remove(id);
    }

    private static History remember(History prev, Entry added) {
        List<Entry> recent = new java.util.ArrayList<>(MEMORY);
        if (prev != null) {
            List<Entry> old = prev.recent();
            recent.addAll(old.subList(Math.max(0, old.size() - (MEMORY - 1)), old.size()));
        }
        recent.add(added);
        return new History(List.copyOf(recent), added.time());
    }

    /**
     * Si repetir esto cuenta como spam, o simplemente como hablar.
     *
     * <p>Dos motivos para que no cuente, y los dos salieron de usarlo de verdad:
     *
     * <p><b>Ha pasado el rato.</b> El mensaje anterior se recordaba hasta que el jugador se
     * desconectaba, asi que saludar con "hola" y volver a saludar veinte minutos despues era
     * "repetir". Un mensaje repetido molesta cuando llega SEGUIDO; una hora despues no molesta
     * a nadie, y quien lo escribe ni se acuerda de haberlo dicho.
     *
     * <p><b>Es demasiado corto.</b> "hola", "si", "ok", "gg" son la conversacion normal de un
     * servidor, no una carga de spam. El spam de verdad — publicidad, enlaces, parrafadas —
     * es largo, asi que exigir un minimo de longitud no lo deja pasar.
     *
     * <p>Importa mas de lo que parece: un duplicado no solo bloquea el mensaje, tambien suma
     * aviso hacia el silencio automatico. Cuatro saludos y el jugador se comia 15 minutos
     * callado sin haber hecho nada.
     */
    static boolean countsAsRepeat(String message, long previousTime, long now,
                                  long repeatWindowMillis, int minRepeatLength) {
        if (message == null || message.length() < minRepeatLength) return false;
        return repeatWindowMillis <= 0L || now - previousTime <= repeatWindowMillis;
    }

    private boolean countsAsRepeat(String message, Entry previous, long now) {
        return countsAsRepeat(message, previous.time(), now, repeatWindowMillis, minRepeatLength);
    }

    private boolean isSimilar(String a, String b) {
        if (a.equals(b)) return true;
        if (similarity >= 1.0) return false;
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) return true;
        double ratio = 1.0 - (double) distance / max;
        return ratio >= similarity;
    }

    static String normalizeForComparison(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        return normalized.length() <= MAX_COMPARISON_LENGTH ? normalized
                : normalized.substring(0, MAX_COMPARISON_LENGTH);
    }

    // Distancia de edicion clasica. Los dos operandos ya estan acotados por
    // normalizeForComparison(), independientemente del orden/prioridad de listeners.
    private static int levenshtein(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 64) return Integer.MAX_VALUE;
        return dev.chatalyst.util.TextDistance.levenshtein(a, b);
    }

    static long normalizeDelayMillis(double seconds) {
        if (!Double.isFinite(seconds)) return 1_500L;
        if (seconds <= 0) return 0L;
        if (seconds >= Long.MAX_VALUE / 1_000.0) return Long.MAX_VALUE;
        return Math.round(seconds * 1_000.0);
    }

    static double normalizeSimilarity(double configured) {
        if (!Double.isFinite(configured)) return 0.9;
        return Math.max(0.0, Math.min(1.0, configured));
    }

    /** 0 o negativo = sin ventana: el mensaje anterior se recuerda mientras dure la sesion. */
    static long normalizeRepeatWindowMillis(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 0L;
        if (seconds >= Long.MAX_VALUE / 1_000.0) return Long.MAX_VALUE;
        return Math.round(seconds * 1_000.0);
    }

    /** Un minimo negativo no significa nada; 0 o 1 dejan que cualquier cosa cuente. */
    static int normalizeMinRepeatLength(int configured) {
        return Math.max(0, configured);
    }
}
