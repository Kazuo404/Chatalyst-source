package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Cobrar y conceder, en ese orden y sin dejar al jugador sin ninguna de las dos cosas.
 *
 * <p>El orden importa. Conceder primero y cobrar despues deja regalado lo concedido si el cobro
 * falla; cobrar primero y no poder conceder deja al jugador sin dinero y sin nada — que es peor,
 * porque el dinero era suyo. Asi que se cobra primero y, si la concesion falla, se DEVUELVE.
 *
 * <p>La concesion de LuckPerms es asincrona, asi que la devolucion vive en su fallo. No es
 * teorico: LuckPerms puede estar recargando, el grupo puede haberse borrado entre que se pinta
 * el menu y se pulsa, y la base puede no responder.
 */
public final class RentalService {

    private final ChatalystPlugin plugin;
    private final RentalFlightWatch flight;

    public RentalService(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.flight = new RentalFlightWatch(plugin);
    }

    /** Por que no se puede alquilar esto ahora mismo, o null si se puede. */
    public String problem(RentalOffer offer) {
        if (!VaultHook.hasEconomy()) return "rental.no-economy";
        if (offer.rank() != null || !offer.permissions().isEmpty()) {
            if (!LuckPermsHook.isAvailable()) return "rental.no-luckperms";
        }
        if (offer.rank() != null && !LuckPermsHook.groupExists(offer.rank())) {
            return "rental.unknown-rank";
        }
        return null;
    }

    /**
     * Compra. Devuelve la clave de lang que hay que decirle al jugador; null si la compra
     * arranco bien y el resultado se le contara al terminar.
     */
    public String buy(Player buyer, RentalOffer offer) {
        String problem = problem(offer);
        if (problem != null) return problem;

        double price = offer.price();
        if (VaultHook.balance(buyer) < price) return "rental.too-expensive";
        // Se cobra ANTES de conceder. Si esto falla no se ha concedido nada y no hay nada que
        // deshacer; al reves habria que quitarle algo que ya tenia en la mano.
        if (price > 0d && !VaultHook.withdraw(buyer, price)) return "rental.charge-failed";

        grant(buyer, offer, price);
        return null;
    }

    /**
     * @param paid lo que se cobro, y por tanto lo que hay que devolver si esto falla. Cero para
     *             un vale: el dinero lo cobro la tienda y el papel ya se consumio, asi que lo
     *             que se devuelve en ese caso no es dinero, es el aviso.
     */
    private void grant(Player player, RentalOffer offer, double paid) {
        UUID id = player.getUniqueId();
        long expiry = offer.expiryMillis();
        String name = player.getName();

        // Una sola vigilancia por compra aunque la oferta conceda el rango Y el nodo de vuelo:
        // dos cortes para el mismo alquiler mandarian el aviso dos veces.
        java.util.concurrent.atomic.AtomicBoolean watched =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean watchFlight = flight.grantsFlight(offer);
        Runnable watch = () -> {
            if (watchFlight && watched.compareAndSet(false, true)) flight.watch(id, expiry);
        };

        // Una sola devolucion por compra. Cada concesion (rango y cada permiso) es su propia
        // operacion asincrona: si fallaban dos, el precio se devolvia dos veces y el jugador
        // salia ganando lo que habia pagado.
        java.util.concurrent.atomic.AtomicBoolean refunded =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        if (offer.rank() != null) {
            LuckPermsHook.grantGroupAsync(id, offer.rank(), expiry)
                    .whenComplete((granted, failure) -> {
                        if (Boolean.TRUE.equals(granted) && failure == null) {
                            watch.run();
                            return;
                        }
                        refundOnce(refunded, id, name, paid, "rank " + offer.rank(), failure);
                    });
        }
        for (String node : offer.permissions()) {
            LuckPermsHook.grantPermissionAsync(id, node, expiry)
                    .whenComplete((granted, failure) -> {
                        if (Boolean.TRUE.equals(granted) && failure == null) {
                            watch.run();
                            return;
                        }
                        refundOnce(refunded, id, name, paid, node, failure);
                    });
        }
    }

    private void refundOnce(java.util.concurrent.atomic.AtomicBoolean refunded, UUID id,
                            String name, double paid, String what, Throwable failure) {
        if (refunded.compareAndSet(false, true)) {
            refund(id, name, paid, what, failure);
        } else {
            plugin.getLogger().warning("Rental for " + name + ": '" + what + "' also failed"
                    + (failure == null ? "" : " (" + failure + ")")
                    + "; the price was already refunded once.");
        }
    }

    /**
     * Concede sin cobrar: el dinero ya lo cobro la tienda al vender el vale.
     *
     * <p>Separado de {@link #buy} a proposito. Un solo metodo con un booleano "cobrar o no"
     * es como se acaba cobrando dos veces por un vale, o regalando un alquiler por un fallo de
     * bandera. Dos caminos con nombre, y cada uno cobra o no por lo que ES.
     *
     * @return la clave de lang con el motivo, o null si la concesion arranco bien.
     */
    public String redeem(Player player, RentalOffer offer) {
        String problem = problem(offer);
        if (problem != null) return problem;
        grant(player, offer, 0d);
        return null;
    }

    /**
     * Devuelve el dinero y lo dice — al jugador si sigue conectado, y a la consola siempre.
     *
     * <p>Una devolucion silenciosa es indistinguible de un cobro que nunca ocurrio: el jugador
     * ve su saldo como estaba, cree que el boton no hizo nada y vuelve a pulsar.
     */
    private void refund(UUID id, String name, double price, String what, Throwable failure) {
        plugin.getLogger().warning("Rental failed for " + name + " (" + what + ")"
                + (failure == null ? "" : ": " + failure) + " — refunding " + price);
        FoliaTasks.global(plugin, () -> {
            Player online = plugin.getServer().getPlayer(id);
            if (price > 0d) VaultHook.deposit(plugin.getServer().getOfflinePlayer(id), price);
            if (online != null) online.sendMessage(Lang.get(online, "rental.refunded"));
        });
    }
}
