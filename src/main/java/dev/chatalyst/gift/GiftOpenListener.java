package dev.chatalyst.gift;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Abrir la caja con clic derecho.
 *
 * <p>Se cancela el evento SIEMPRE que el objeto sea una caja, incluso cuando luego no se puede
 * abrir. Una caja es una cabeza, y una cabeza en la mano contra un bloque se COLOCA: dejar
 * pasar el evento convertiria el premio en decoracion de pared, y encima sin avisar.
 *
 * <p>El listener se registra este el sistema encendido o no. Si el dueño lo apaga con
 * jugadores dentro, la caja que ya llevan en el bolsillo sigue explicando por que no se abre,
 * en vez de volver a ser una cabeza que se pega en la pared.
 */
public final class GiftOpenListener implements Listener {

    private final ChatalystPlugin plugin;
    private final GiftService gifts;

    public GiftOpenListener(ChatalystPlugin plugin, GiftService gifts) {
        this.plugin = plugin;
        this.gifts = gifts;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack held = event.getItem();
        String id = gifts.items().idOf(held);
        if (id == null) return;
        // Tambien en la mano secundaria: antes ese clic se ignoraba entero y la caja acababa
        // colocada en el mundo como una cabeza cualquiera.
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && gifts.items().idOf(event.getPlayer().getInventory().getItemInMainHand())
                        != null) {
            return; // el mismo clic ya abrio la de la mano principal: una por clic
        }
        open(event.getPlayer(), held, id);
    }

    private void open(Player player, ItemStack held, String id) {
        if (!gifts.enabled()) {
            player.sendMessage(Lang.get(player, "gift.disabled"));
            return;
        }
        Optional<GiftTier> tier = gifts.tier(id);
        if (tier.isEmpty()) {
            // La caja existe pero su tipo ya no esta en gifts.yml. No se consume: el jugador
            // no tiene la culpa de un catalogo recortado, y devolverle algo al azar seria
            // peor que decirle que hable con el staff.
            player.sendMessage(Lang.get(player, "gift.unknown"));
            return;
        }
        Optional<GiftReward> reward = gifts.draw(tier.get());
        if (reward.isEmpty()) {
            player.sendMessage(Lang.get(player, "gift.empty"));
            return;
        }

        // Se consume DESPUES de saber que hay premio, y antes de entregarlo.
        held.setAmount(held.getAmount() - 1);
        ItemStack prize = gifts.deliver(player, reward.get());

        if (plugin.getConfig().getBoolean("Gifts.Announce", true)) {
            player.sendMessage(Lang.get(player, "gift.opened",
                    "box", tier.get().displayName(plugin.getConfig(), player),
                    "reward", reward.get().label(player),
                    "amount", String.valueOf(prize.getAmount())));
        }
    }
}
