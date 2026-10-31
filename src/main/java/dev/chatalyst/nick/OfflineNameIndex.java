package dev.chatalyst.nick;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Indice propio nombre -&gt; UUID de quien entro alguna vez, para resolver objetivos que ya
 * no estan en el usercache de Paper (guarda ~1000 entradas / 30 dias: a un jugador de hace
 * semanas no se le podia /ban, /mute, /banip ni /check por nombre).
 *
 * <p>Esta clase es solo el PUNTO DE ENCUENTRO: el indice lo escribe y lo sirve el modulo de
 * staff (AltTracker), pero quien lo consulta es {@link NickManager#resolveOffline}, que es
 * estatico y arranca antes. Con una fuente instalable no hay dependencia del orden de
 * arranque: sin modulo de staff no hay fuente y la busqueda simplemente no encuentra nada.
 */
public final class OfflineNameIndex {

    // Un nombre real mide 16 como mucho (mas el prefijo de Floodgate). Lo que pase de aqui
    // es texto arbitrario de un comando, no un nombre: ni se busca ni se guarda.
    private static final int MAX_NAME_LENGTH = 32;

    private static final Function<String, UUID> NONE = name -> null;
    private static volatile Function<String, UUID> source = NONE;

    private OfflineNameIndex() {
    }

    public static void install(Function<String, UUID> lookup) {
        source = lookup != null ? lookup : NONE;
    }

    /**
     * Retira la fuente SOLO si sigue siendo la instalada: en un reload el modulo nuevo puede
     * instalarse antes de que el viejo termine de apagarse, y este no debe llevarse la buena.
     */
    public static void uninstall(Function<String, UUID> lookup) {
        if (source == lookup) source = NONE;
    }

    /** UUID del ultimo que entro con ese nombre, o null. Seguro desde cualquier hilo. */
    public static UUID find(String name) {
        if (key(name) == null) return null;
        try {
            return source.apply(name);
        } catch (RuntimeException failure) {
            // Un indice roto no puede tumbar el comando: equivale a "no lo conozco".
            return null;
        }
    }

    /**
     * Forma del nombre como clave de almacen, o null si no puede ser un nombre.
     *
     * <p>{@code Locale.ROOT} por la misma trampa que en {@link NickManager#findByDisplay}
     * (la "I" turca). Y todo lo que no sea [a-z0-9_] se escapa: los nombres de Bedrock
     * llevan el prefijo de Floodgate, que por defecto es un PUNTO, y el backend YAML trata
     * el punto como separador de ruta — la clave se guardaba como seccion anidada y al
     * reiniciar el jugador desaparecia del indice.
     */
    public static String key(String name) {
        if (name == null) return null;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.length() > MAX_NAME_LENGTH) return null;
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%04x", (int) c));
            }
        }
        return out.toString();
    }
}
