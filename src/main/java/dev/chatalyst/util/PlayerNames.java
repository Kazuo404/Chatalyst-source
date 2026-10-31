package dev.chatalyst.util;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Como se llama HOY una cuenta que guardamos por UUID.
 *
 * <p>Un nombre escrito en disco es una foto del dia en que se guardo, no la identidad de
 * nadie. Se guarda igualmente porque hay que poder enseñar ALGO cuando el UUID no resuelve,
 * pero cuando el jugador sigue existiendo lo correcto es preguntar por su nombre de ahora:
 * quien se cambio el nombre aparece con el nuevo, y nadie hereda el viejo de otro.
 *
 * <p>Ninguna consulta sale a la red — el jugador conectado primero, y si no la cache de
 * perfiles del propio servidor — asi que esto se puede llamar desde el hilo principal.
 */
public final class PlayerNames {

    private PlayerNames() {
    }

    /**
     * @param fallback lo que se enseña si el servidor no conoce ese UUID (el nombre guardado).
     */
    public static String current(Server server, UUID id, String fallback) {
        if (server == null || id == null) return fallback;
        Player online = server.getPlayer(id);
        if (online != null) return online.getName();
        String known = server.getOfflinePlayer(id).getName();
        return known == null || known.isBlank() ? fallback : known;
    }
}
