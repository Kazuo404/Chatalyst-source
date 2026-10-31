package dev.chatalyst.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A quien pertenece una compra.
 *
 * <p>Siempre un UUID, nunca un nombre: un nombre cambia y el UUID no, asi que atar lo pagado
 * al nombre es atarlo a algo que el jugador puede cambiar cuando quiera. Todo lo que se guarda
 * — el registro de derechos, los recibos, las entregas pendientes — se guarda por UUID, y este
 * es el unico sitio donde se decide cual es.
 */
public final class BuyerId {

    /**
     * Lo bastante ancho para un nombre de Java y para el de un jugador de Bedrock tras
     * Floodgate (el prefijo "." y los espacios ya convertidos). No es un filtro de seguridad:
     * el nombre solo sirve para buscar una cuenta REAL, jamas para inventar un UUID.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    private BuyerId() {
    }

    /**
     * Los dos formatos de UUID que manda Tebex: con guiones y sin ellos.
     *
     * <p>Un nombre se rechaza aqui a proposito. Derivar un UUID de un nombre es lo que hace un
     * servidor en offline-mode, y eso es exactamente lo que provoca que el dia que el jugador
     * se cambia el nombre su rango deje de ser suyo.
     *
     * @return el UUID, o null si no lo es.
     */
    public static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() == 32) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12)
                    + "-" + value.substring(12, 16) + "-"
                    + value.substring(16, 20) + "-" + value.substring(20);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Quien recibe una entrega: su UUID si viene el UUID, y si no, el de la cuenta REAL que
     * lleve ese nombre.
     *
     * <p>El UUID de {@code {id}} sigue siendo la forma correcta y la unica que no se puede
     * confundir con nadie. Pero rechazar todo lo demas salia caro: Tebex da el comando por
     * ejecutado en cuanto lo manda y no lo reenvia jamas, asi que un paquete configurado con
     * {@code {username}}, o un pago cobrado fuera de la tienda que un admin entrega a mano, se
     * perdia entero por haber escrito un nombre.
     *
     * <p>El nombre se busca contra el jugador conectado primero y, si no lo esta, contra la
     * cache de nombres del servidor — la misma que usan {@code /op} o {@code /whitelist} — y
     * lo que se devuelve es siempre el UUID que salga de ahi. Ninguna de las dos consultas
     * sale a la red: bloquear aqui congelaria el hilo del que viene el comando de Tebex.
     *
     * <p>Los apodos NO cuentan: se busca el nombre real de la cuenta. Un apodo se elige uno
     * mismo, y aceptarlo permitiria dirigir hacia uno la entrega de otro.
     *
     * @return el UUID del comprador, o null si no hay ninguna cuenta que responda a eso.
     */
    public static UUID resolve(Server server, String raw) {
        UUID direct = parseUuid(raw);
        if (direct != null) return direct;
        if (server == null || raw == null) return null;
        String name = raw.trim();
        if (!NAME.matcher(name).matches()) return null;
        Player online = server.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer known = server.getOfflinePlayerIfCached(name);
        return known == null ? null : known.getUniqueId();
    }
}
