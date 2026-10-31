package dev.chatalyst.chat;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consentimiento temporal para que otros abran una vista del inventario o del ender chest
 * de un jugador que escribio [inv] o [ec] en el chat. Cada mensaje genera un TOKEN opaco
 * (nunca el UUID: el JSON del chat no debe filtrar la identidad real, importante con
 * /anonymous). Solo se guarda el permiso (token -> dueno + tipo + caducidad), nunca items:
 * la vista se lee EN VIVO en la region del dueno al hacer click, asi que es segura en
 * Folia y nadie puede abrir a quien no compartio.
 */
public final class InventoryShareManager {

    public enum Kind { INVENTORY, ENDER_CHEST }

    /** Un permiso vigente: a quien se mira y que vista. */
    public record Grant(UUID owner, Kind kind, long until) {
    }

    /** Cuanto dura el boton del chat desde el mensaje que lo creo. */
    private static final long GRANT_MILLIS = 5 * 60 * 1000L;
    private static final int CLEAN_THRESHOLD = 512;
    static final int MAX_GRANTS = 4096;
    private static final char[] TOKEN_CHARS =
            "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int TOKEN_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /** Registra el permiso y devuelve su token. Seguro desde el hilo async del chat. */
    public synchronized String share(UUID owner, Kind kind) {
        long now = System.currentTimeMillis();
        if (grants.size() >= CLEAN_THRESHOLD) {
            grants.entrySet().removeIf(entry -> entry.getValue().until() <= now);
        }
        // El renderer de Paper puede construir el componente una vez por viewer. Un solo
        // mensaje con [inv] en un servidor grande genera muchos grants, todos aun vigentes;
        // podar solo caducados no imponia ningun limite real. Si se llena, sacrificamos el
        // boton que antes vence para mantener memoria acotada.
        while (grants.size() >= MAX_GRANTS) {
            Map.Entry<String, Grant> oldest = grants.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(Grant::until)))
                    .orElse(null);
            if (oldest == null) break;
            grants.remove(oldest.getKey(), oldest.getValue());
        }

        Grant grant = new Grant(owner, kind, now + GRANT_MILLIS);
        for (;;) {
            String token = randomToken();
            // Aunque la probabilidad sea diminuta, nunca reemplazar el permiso de otro
            // mensaje si SecureRandom produce el mismo token.
            if (grants.putIfAbsent(token, grant) == null) return token;
        }
    }

    /** Permiso del token si sigue vigente, o null (caducado o inventado). */
    public Grant resolve(String token) {
        if (token == null || token.isEmpty()) return null;
        Grant grant = grants.get(token);
        if (grant == null) return null;
        if (grant.until() > System.currentTimeMillis()) return grant;
        grants.remove(token, grant);
        return null;
    }

    int size() {
        return grants.size();
    }

    private static String randomToken() {
        StringBuilder out = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            out.append(TOKEN_CHARS[RANDOM.nextInt(TOKEN_CHARS.length)]);
        }
        return out.toString();
    }
}
