package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Canjear un vale: clic derecho, se concede lo que dice y el papel se consume. */
public final class RentalVoucherListener implements Listener {

    private final ChatalystPlugin plugin;
    private final RentalService rentals;

    public RentalVoucherListener(ChatalystPlugin plugin, RentalService rentals) {
        this.plugin = plugin;
        this.rentals = rentals;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Solo la mano principal: el evento llega dos veces (una por mano) y sin esto un
        // vale se canjearia dos veces de un solo clic.
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack held = event.getItem();
        String offerId = RentalVoucher.offerIn(held);
        if (offerId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        RentalOffer offer = RentalOffer.resolve(plugin.getConfig(), offerId).orElse(null);
        if (offer == null) {
            // El vale nombra algo que ya no existe. NO se consume: el jugador pago por el, y
            // quedarse con su papel ademas de no darle nada seria robarle dos veces.
            player.sendMessage(Lang.get(player, "rental.voucher-unknown"));
            plugin.getLogger().warning("A rental voucher held by " + player.getName()
                    + " names the offer '" + offerId + "', which is not in Rental_Offers.");
            return;
        }
        String failure = rentals.redeem(player, offer);
        if (failure != null) {
            player.sendMessage(Lang.get(player, failure));
            return;
        }
        // Se consume DESPUES de que la concesion arranque sin problema conocido. Al reves, un
        // server sin LuckPerms se comeria el vale y no daria nada.
        held.setAmount(held.getAmount() - 1);
        player.sendMessage(Lang.get(player, "rental.bought",
                "what", offer.displayName(plugin.getConfig(), player),
                "duration", TimeUtil.formatElapsed(offer.durationMillis()).trim()));
    }
}
