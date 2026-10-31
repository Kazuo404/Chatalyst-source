package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Apaga el vuelo cuando caduca el alquiler que lo daba.
 *
 * <p>Hace falta porque EssentialsX comprueba {@code essentials.fly} SOLO al entrar, al cambiar
 * de mundo, al teletransportarse y al cambiar de modo de juego — nunca mientras se juega
 * (EssentialsPlayerListener 2.22.1: esos son los unicos sitios donde llama a setAllowFlight).
 * Cuando LuckPerms retira el nodo caducado, el jugador conserva el allowFlight que le puso
 * Essentials y sigue volando hasta que se desconecta. Sin esta vigilancia, "dos horas de vuelo"
 * es en realidad "vuelo hasta que me canse": la duracion que se cobra no se cumple justo en la
 * sesion en la que se compra, que es cuando se usa.
 *
 * <p>Cortar el vuelo a alguien que esta en el aire lo tira al suelo. Es exactamente lo que le
 * pasa si se desconecta volando, pero aqui lo provoca el server, asi que se avisa un minuto
 * antes y se amortigua la caida: matar a un jugador porque se le acabo algo que pago seria
 * cobrarselo dos veces.
 */
final class RentalFlightWatch {

    /** Lo que da vuelo aqui si nadie escribe otra cosa. Ver {@code Rentals.Flight_Permissions}. */
    private static final List<String> DEFAULT_NODES = List.of("essentials.fly");

    /**
     * LuckPerms no borra el nodo en el milisegundo exacto: lo hace su ExpireTemporaryTask, que
     * pasa cada 3 segundos. Comprobar el permiso antes de esa pasada lo veria todavia vivo y la
     * vigilancia no haria nada, que es el fallo que viene a arreglar.
     */
    private static final long EXPIRY_MARGIN_MILLIS = 5_000L;

    private static final long WARNING_MILLIS = 60_000L;

    /** Cada cuanto se vuelve a mirar un vuelo que al caducar la vigilancia seguia vigente. */
    private static final long RECHECK_TICKS = 60L * 20L;

    /** Amortiguacion de la caida al cortar en el aire. 10 s bastan para bajar de cualquier altura. */
    private static final int SLOW_FALL_TICKS = 200;

    private final ChatalystPlugin plugin;
    private final java.util.Set<UUID> rechecking =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    RentalFlightWatch(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    /** Los nodos que dan vuelo en este server, tal y como estan escritos en la config. */
    List<String> nodes() {
        return nodes(plugin.getConfig().getStringList("Rentals.Flight_Permissions"));
    }

    /**
     * Sin lista escrita se vigila {@code essentials.fly}: es lo que da vuelo en la practica, y
     * una lista vacia por descuido dejaria la vigilancia apagada sin que nadie lo notara.
     */
    static List<String> nodes(List<String> configured) {
        if (configured == null || configured.isEmpty()) return DEFAULT_NODES;
        List<String> out = new ArrayList<>(configured.size());
        for (String raw : configured) {
            String node = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!node.isEmpty()) out.add(node);
        }
        return out.isEmpty() ? DEFAULT_NODES : List.copyOf(out);
    }

    /** Si esta oferta puede acabar dando vuelo, y por tanto hay que vigilar su caducidad. */
    boolean grantsFlight(RentalOffer offer) {
        return grantsFlight(offer.permissions(), offer.rank(), nodes());
    }

    /**
     * Un rango alquilado cuenta SIEMPRE. Lo que trae por herencia no se sabe desde aqui, y
     * equivocarse por vigilar de mas solo cuesta una comprobacion que no hace nada; por vigilar
     * de menos, un vuelo que no se acaba.
     */
    static boolean grantsFlight(List<String> permissions, String rank, List<String> flightNodes) {
        if (rank != null && !rank.isBlank()) return true;
        if (permissions == null) return false;
        for (String permission : permissions) {
            if (permission == null) continue;
            String node = permission.trim().toLowerCase(Locale.ROOT);
            if (flightNodes.contains(node)) return true;
        }
        return false;
    }

    /** Cuantos ticks faltan para el aviso, o -1 si el alquiler dura menos que el propio aviso. */
    static long warningTicks(long expiryMillis, long nowMillis) {
        long millis = expiryMillis - WARNING_MILLIS - nowMillis;
        return millis <= 0L ? -1L : millis / 50L;
    }

    /** Cuantos ticks faltan para cortar, ya con el margen de la caducidad de LuckPerms. */
    static long cutTicks(long expiryMillis, long nowMillis) {
        return Math.max(1L, (expiryMillis + EXPIRY_MARGIN_MILLIS - nowMillis) / 50L);
    }

    /**
     * Vigila un alquiler recien concedido. {@code expiryMillis} es cuando caduca, en tiempo de
     * reloj; cero significa que no caduca y entonces no hay nada que apagar.
     */
    void watch(UUID id, long expiryMillis) {
        if (expiryMillis <= 0L) return;
        long now = System.currentTimeMillis();

        long warning = warningTicks(expiryMillis, now);
        if (warning > 0L) FoliaTasks.globalLater(plugin, () -> warn(id), warning);

        FoliaTasks.globalLater(plugin, () -> cut(id), cutTicks(expiryMillis, now));
    }

    private void warn(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        // Sin vuelo puesto el aviso no dice nada util: o ya lo apago el, o nunca llego a usarlo.
        if (player == null || !player.getAllowFlight()) return;
        player.sendMessage(Lang.get(player, "rental.flight-ending"));
    }

    private void cut(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        // Desconectado no hace falta: al volver a entrar, EssentialsX ya mira el permiso.
        if (player == null) return;
        FoliaTasks.entity(plugin, player, current -> {
            // Lo volvio a alquilar, se lo dio el staff o subio de rango: el permiso manda. Pero
            // si lo volvio a alquilar, el tiempo se SUMA y esta vigilancia se programo para la
            // hora antigua: se vuelve a mirar mas tarde, o ese vuelo no se cortaria nunca en
            // esta sesion. Con vuelo permanente esto es un vistazo por minuto hasta que salga.
            if (hasFlight(current)) {
                // Una sola cadena por jugador, aunque haya alquilado varias veces.
                if (rechecking.add(id)) {
                    FoliaTasks.globalLater(plugin, () -> {
                        rechecking.remove(id);
                        cut(id);
                    }, RECHECK_TICKS);
                }
                return;
            }
            // En creativo o espectador el vuelo no viene de este alquiler y no es nuestro.
            if (current.getGameMode() == GameMode.CREATIVE
                    || current.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (!current.getAllowFlight()) return;

            boolean flying = current.isFlying();
            current.setFlying(false);
            current.setAllowFlight(false);
            if (flying) {
                current.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOW_FALLING, SLOW_FALL_TICKS, 0, false, false, false));
            }
            current.sendMessage(Lang.get(current, "rental.flight-ended"));
        });
    }

    private boolean hasFlight(Player player) {
        for (String node : nodes()) {
            if (player.hasPermission(node)) return true;
        }
        return false;
    }
}
