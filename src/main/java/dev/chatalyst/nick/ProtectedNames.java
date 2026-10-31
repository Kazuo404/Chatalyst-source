package dev.chatalyst.nick;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Nombres que nadie puede llevar, ni como apodo ni como titulo: streamers, el staff, marcas.
 *
 * <p>Existe porque el anti-suplantacion normal solo alcanza a quien ha ENTRADO al servidor
 * ({@code getOfflinePlayerIfCached} lee el usercache local, no la API de Mojang), asi que un
 * streamer que nunca ha jugado aqui no esta protegido por nada.
 *
 * <p>Y es una lista a mano, no una consulta a Mojang, por una razon de diseño ademas de la
 * obvia de no bloquear el hilo con una llamada de red: casi cualquier palabra pronunciable ya
 * es una cuenta registrada. Prohibir "todo nombre que exista" dejaria a los jugadores sin
 * poder llamarse Dragon, Sombra ni Lobo — la regla se volveria inusable y la acabarian
 * apagando, que es la peor proteccion de todas.
 *
 * <p>La comparacion usa {@link NickText#spoofKey(String)}, de modo que proteger "Ibai" cubre
 * tambien "ibai", "I b a i", "Ｉｂａｉ" y "Ibai" con un espacio duro detras.
 */
public final class ProtectedNames {

    /** La lista tal como vino y su forma normalizada, cambiadas siempre a la vez. */
    private record Snapshot(List<String> source, Set<String> keys) {
    }

    private static volatile Snapshot cache = new Snapshot(List.of(), Set.of());
    /** La lista que trae el plugin ({@link ProtectedNamesFile}); suma a la de la config. */
    private static volatile Set<String> bundled = Set.of();

    private ProtectedNames() {
    }

    /** Convierte las lineas del fichero en claves; ignora comentarios y lineas en blanco. */
    public static Set<String> parse(Iterable<String> lines) {
        Set<String> built = new HashSet<>();
        if (lines == null) return Set.of();
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String key = NickText.spoofKey(trimmed);
            if (!key.isEmpty()) built.add(key);
        }
        return Set.copyOf(built);
    }

    /** Reemplaza la lista del plugin. Se llama al arrancar y al recargar. */
    public static void installBundled(Set<String> keys) {
        bundled = keys == null ? Set.of() : keys;
    }

    /** Cuantos nombres trae la lista del plugin (diagnostico de /chatalyst doctor). */
    public static int bundledSize() {
        return bundled.size();
    }

    /**
     * ¿Este texto se lee como uno de los nombres protegidos?
     *
     * @param configured la lista tal cual sale de la config; se normaliza y se cachea, y se
     *     vuelve a construir sola cuando cambia (un reload no necesita avisar a nadie).
     * @param spoofKey la clave del texto a comprobar, de {@link NickText#spoofKey(String)}.
     */
    /** Cubierto por la lista del dueño ({@code Nick.Protected_Names}) o por la del plugin. */
    public static boolean covers(List<String> configured, String spoofKey) {
        if (spoofKey == null || spoofKey.isEmpty()) return false;
        return bundled.contains(spoofKey) || normalized(configured).contains(spoofKey);
    }

    /**
     * ¿Debe rechazarse este texto por reservado, sabiendo QUIEN lo pide?
     *
     * <p>La lista protege un nombre de todo el mundo menos de su dueño. Sin esta distincion,
     * proteger "Ibai" impedia usar su propio nombre al Ibai de verdad — que es precisamente
     * la persona a la que la lista existe para proteger. Lo mismo con el dueño del servidor,
     * que se añade a si mismo y se queda sin poder llevar su nombre.
     *
     * <p>El resto del anti-suplantacion ya distinguia al dueño comparando UUIDs; aqui no hay
     * UUID que comparar (un nombre reservado puede no ser de nadie de este servidor), asi que
     * se compara contra el nombre REAL de la cuenta, con la misma normalizacion.
     */
    public static boolean blocks(List<String> configured, String spoofKey, String ownRealName) {
        if (!covers(configured, spoofKey)) return false;
        return !NickText.spoofKey(ownRealName).equals(spoofKey);
    }

    /** Las claves protegidas, reconstruidas solo si la lista de config cambio. */
    static Set<String> normalized(List<String> configured) {
        List<String> raw = configured == null ? List.of() : configured;
        Snapshot current = cache;
        if (raw.equals(current.source())) return current.keys();
        Set<String> built = new HashSet<>();
        for (String name : raw) {
            if (name == null) continue;
            String key = NickText.spoofKey(name);
            if (!key.isEmpty()) built.add(key);
        }
        // ArrayList y no List.copyOf: un hueco en el YAML ("- " a secas) llega como null y
        // List.copyOf lo rechaza con NPE, tumbando el /nick de todos por un renglon suelto
        // en la config. Aqui solo se guarda para comparar, asi que el null no molesta.
        // Un unico volatile: lista y claves nunca se ven desparejadas desde otro hilo.
        Snapshot next = new Snapshot(
                java.util.Collections.unmodifiableList(new java.util.ArrayList<>(raw)),
                Set.copyOf(built));
        cache = next;
        return next.keys();
    }
}
