package dev.chatalyst.autoreply;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Resuelve si una "capacidad" esta disponible AHORA MISMO en el servidor, para que el bot de
 * FAQ responda con el comando/feature que de verdad existe (y no con uno que no esta). Tres
 * formas de expresar una capacidad en la config (campo {@code Needs}):
 *
 * <ul>
 *   <li>{@code command:<cmd>} — el comando esta registrado, sea de Chatalyst o de otro plugin
 *       (p. ej. {@code command:jobs}, {@code command:balance}). Es la comprobacion mas fiable de
 *       "esto se puede usar".</li>
 *   <li>{@code plugin:<Nombre>} — ese plugin esta instalado y activo (nombre como en
 *       {@code /plugins}, sensible a mayusculas).</li>
 *   <li>Una clave nativa de Chatalyst por su interruptor de config: {@code pm}, {@code mail},
 *       {@code market}, {@code trade}, {@code channels}, {@code announcements}, {@code economy},
 *       {@code baltop}.</li>
 * </ul>
 *
 * Se evalua en el momento de responder, asi refleja cambios en caliente ({@code /reload},
 * plugins cargados, features encendidas o apagadas).
 */
final class Capabilities {

    private final JavaPlugin plugin;

    Capabilities(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean has(String need) {
        if (need == null) return false;
        String n = need.trim();
        if (n.isEmpty()) return false;
        if (n.regionMatches(true, 0, "command:", 0, 8)) return commandAvailable(n.substring(8));
        if (n.regionMatches(true, 0, "plugin:", 0, 7)) return pluginPresent(n.substring(7));
        return builtin(n.toLowerCase(Locale.ROOT));
    }

    private boolean pluginPresent(String name) {
        var other = plugin.getServer().getPluginManager().getPlugin(name.trim());
        return other != null && other.isEnabled();
    }

    private boolean commandAvailable(String command) {
        String c = command.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isEmpty()) return false;
        try {
            return plugin.getServer().getCommandMap().getCommand(c) != null;
        } catch (Throwable ignored) {
            // getCommandMap es de Paper; si faltara, tratamos el comando como no disponible.
            return false;
        }
    }

    /** Capacidades nativas de Chatalyst, mapeadas a su interruptor real de config. */
    private boolean builtin(String cap) {
        var cfg = plugin.getConfig();
        return switch (cap) {
            case "pm", "msg", "privatemessages" -> cfg.getBoolean("Private_Messages.Enabled", false);
            case "mail", "inbox" -> commandAvailable("inbox");
            case "market" -> cfg.getBoolean("Market.Enabled", false);
            case "trade" -> cfg.getBoolean("Trade.Enabled", false);
            case "channels" -> cfg.getBoolean("Channels.Enabled", true);
            case "announcements", "announcer" -> cfg.getBoolean("Announcer.Enabled", true);
            case "economy" -> dev.chatalyst.hook.VaultHook.hasEconomy();
            case "baltop", "balancetop" -> cfg.getBoolean("Balance_Top.Enabled", true)
                    && dev.chatalyst.hook.VaultHook.hasEconomy();
            case "store", "purchases" -> cfg.getBoolean("Store.Enabled", false);
            // Only true when a player could actually complete a link right now, so the bot
            // never tells someone to run /discord link against a bot that is not connected.
            case "discordlink", "discord" -> cfg.getBoolean("Discord.Enabled", false)
                    && cfg.getBoolean("Discord.Linking.Enabled", true);
            case "ranks", "rankshop" -> cfg.getBoolean("Tebex.Enabled", false)
                    && cfg.getBoolean("Tebex.Shop.Enabled", false);
            // Alquilar es la OTRA puerta a lo mismo, la que se paga jugando. Se anuncia solo
            // si de verdad se puede: sin economia no hay con que pagar, y sin ofertas escritas
            // mandar a alguien a un menu vacio es peor que no mencionarlo.
            case "rent", "rental", "alquilar" -> cfg.getBoolean("Rentals.Enabled", false)
                    && dev.chatalyst.hook.VaultHook.hasEconomy()
                    && !dev.chatalyst.rental.RentalOffer.all(cfg).isEmpty();
            default -> false;
        };
    }
}
