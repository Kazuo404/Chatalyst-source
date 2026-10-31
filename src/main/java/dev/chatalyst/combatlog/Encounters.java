package dev.chatalyst.combatlog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quien empezo. Agrupa los golpes entre dos jugadores en <b>encuentros</b> y recuerda cual de
 * los dos dio el primero.
 *
 * <p>Existe porque la regla del servidor ("el PvP es valido en cuanto alguien da el primer
 * golpe") es imposible de arbitrar sin este dato: en una muerte, el log de Minecraft dice
 * quien mato a quien, y esa suele ser justo la victima de la agresion. Sin registrar la
 * apertura, cada pelea acaba en la palabra de uno contra la del otro.
 *
 * <p>Un encuentro se abre con el primer golpe entre dos jugadores y se cierra cuando pasan
 * {@code windowMillis} sin que se toquen, o cuando uno muere. Reencontrarse mas tarde es una
 * pelea NUEVA: si no, quien pego primero hace media hora cargaria con la culpa de una pelea
 * que empezo el otro.
 *
 * <p>Sin dependencias de Bukkit a proposito: es la unica parte con reglas de decision y
 * merece test propio.
 */
public final class Encounters {

    /**
     * Un encuentro abierto entre dos jugadores. {@code startedAt} es el primer golpe y no se
     * mueve; {@code lastHitAt} es el ultimo, y es el que mide el silencio.
     */
    public record Encounter(UUID starterId, String starterName, long startedAt,
                            long lastHitAt) {

        /** True si ya paso el silencio que separa esta pelea de la siguiente. */
        boolean expired(long now, long windowMillis) {
            return now - lastHitAt >= windowMillis;
        }
    }

    /** Lo que hay que saber de un golpe recien registrado. */
    public record Hit(boolean first, String starterName, long sinceMillis) {
    }

    /** Por encima de esto se barren los encuentros caducados (peleas masivas, mundo grande). */
    private static final int CLEAN_THRESHOLD = 2048;

    private final Map<String, Encounter> open = new ConcurrentHashMap<>();

    /**
     * Registra un golpe de {@code attacker} sobre {@code victim} y devuelve si abrio el
     * encuentro, quien lo abrio y cuanto ha pasado desde entonces.
     *
     * @param windowMillis silencio tras el cual la siguiente pelea entre esos dos es nueva
     */
    public Hit hit(UUID attacker, String attackerName, UUID victim, long now, long windowMillis) {
        if (open.size() > CLEAN_THRESHOLD) {
            open.entrySet().removeIf(e -> e.getValue().expired(now, windowMillis));
        }
        String key = key(attacker, victim);
        Encounter current = open.get(key);
        if (current == null || current.expired(now, windowMillis)) {
            Encounter fresh = new Encounter(attacker, attackerName, now, now);
            open.put(key, fresh);
            return new Hit(true, attackerName, 0L);
        }
        // La apertura no se reescribe, pero el silencio se mide desde ESTE golpe. Medirlo
        // desde el primero cerraba la pelea a los 30 s aunque siguieran pegandose, y el
        // siguiente golpe -- a menudo del que se estaba defendiendo -- quedaba anotado como
        // el que abria una pelea nueva: el agredido pasaba a constar como agresor.
        open.put(key, new Encounter(current.starterId(), current.starterName(),
                current.startedAt(), now));
        return new Hit(false, current.starterName(), now - current.startedAt());
    }

    /**
     * El encuentro abierto entre dos jugadores, o null si no hay ninguno vigente. Se consulta
     * al morir alguien, para poder decir en la misma linea quien habia empezado.
     */
    public Encounter peek(UUID a, UUID b, long now, long windowMillis) {
        Encounter current = open.get(key(a, b));
        if (current == null || current.expired(now, windowMillis)) return null;
        return current;
    }

    /** Cierra el encuentro entre dos jugadores (alguien murio: lo que venga despues es otra pelea). */
    public void close(UUID a, UUID b) {
        open.remove(key(a, b));
    }

    /** Olvida todos los encuentros de un jugador (se desconecto o se apaga el modulo). */
    public void forget(UUID player) {
        String half = player.toString();
        open.keySet().removeIf(k -> k.startsWith(half + "|") || k.endsWith("|" + half));
    }

    public void clear() {
        open.clear();
    }

    /** Clave sin orden: A golpeando a B y B golpeando a A son el MISMO encuentro. */
    private static String key(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
