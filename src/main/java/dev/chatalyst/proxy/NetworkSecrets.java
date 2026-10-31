package dev.chatalyst.proxy;

import dev.chatalyst.proxybridge.NetworkAuth;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Resuelve el secreto de autenticacion de red sin obligar a configurarlo a mano.
 * Orden de resolucion:
 * <ol>
 *   <li>{@code Proxy.Network_Secret} explicito en config (override manual, se usa tal cual
 *       en todos los lados).</li>
 *   <li>El forwarding secret de Velocity ({@code config/paper-global.yml}, con el modern
 *       forwarding activado): TODOS los backends de una red Velocity ya lo comparten, y el
 *       bridge del proxy lo lee de su {@code forwarding.secret}. Se deriva una subclave
 *       ({@link NetworkAuth#derive}) para no reutilizar el secreto crudo.</li>
 *   <li>{@code Redis.Shared_Secret}, SOLO cuando ningun bridge participa (modo legacy o
 *       redis): en native el bridge no conoce ese secreto y quedaria desincronizado.</li>
 * </ol>
 * Sin material, los transportes de plugin messaging quedan apagados (fail-closed).
 */
final class NetworkSecrets {

    record Resolved(String secret, String source) {
        boolean present() {
            return secret != null && !secret.isBlank();
        }
    }

    private NetworkSecrets() {
    }

    static Resolved resolve(JavaPlugin plugin, boolean bridgeInvolved) {
        String explicit = plugin.getConfig().getString("Proxy.Network_Secret", "");
        String velocity = velocityForwardingSecret();
        String redis = plugin.getConfig().getString("Redis.Shared_Secret", "");
        return select(explicit, velocity, redis, bridgeInvolved);
    }

    /** Seleccion pura y testeable: el override explicito siempre gana, incluso si es debil. */
    static Resolved select(String explicit, String velocity, String redis,
            boolean bridgeInvolved) {
        if (explicit != null && !explicit.isBlank()) {
            return new Resolved(explicit.trim(), "Proxy.Network_Secret");
        }
        if (velocity != null && !velocity.isBlank()) {
            return new Resolved(NetworkAuth.derive(velocity.trim()),
                "Velocity forwarding secret");
        }
        if (!bridgeInvolved && redis != null && !redis.isBlank()) {
            return new Resolved(NetworkAuth.derive(redis.trim()), "Redis.Shared_Secret");
        }
        return new Resolved("", "none");
    }

    /**
     * Forwarding secret del modern forwarding de Velocity, leido del paper-global.yml del
     * servidor (relativo a la raiz del server, el working directory del proceso). Solo
     * cuenta si el forwarding esta ACTIVADO: un secreto residual con Velocity apagado
     * podria no estar sincronizado entre backends.
     */
    private static String velocityForwardingSecret() {
        try {
            File file = new File("config", "paper-global.yml");
            if (!file.isFile()) return "";
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (!yaml.getBoolean("proxies.velocity.enabled", false)) return "";
            String secret = yaml.getString("proxies.velocity.secret", "");
            return secret == null ? "" : secret.trim();
        } catch (Throwable t) {
            return ""; // YAML raro o sin permisos de lectura: mejor sin auto que reventar
        }
    }
}
