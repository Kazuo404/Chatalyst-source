package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /alquilar} abre el menu; {@code /alquilar <oferta>} compra directamente.
 *
 * <p>La segunda forma existe para que otra tienda pueda vender esto sin saber nada de Chatalyst:
 * EconomyShopGUI, un cartel o un NPC ejecutan el comando y el cobro, la caducidad y la
 * devolucion siguen ocurriendo aqui, donde se pueden hacer bien.
 */
public final class RentalCommand implements RuntimeCommandRegistrar.Handler {

    private final ChatalystPlugin plugin;
    private final RentalService rentals;

    public RentalCommand(ChatalystPlugin plugin, RentalService rentals) {
        this.plugin = plugin;
        this.rentals = rentals;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length == 0) {
            Menu.open(player, new RentalMenu(plugin, rentals));
            return true;
        }
        RentalOffer offer = RentalOffer.resolve(plugin.getConfig(), args[0]).orElse(null);
        if (offer == null) {
            player.sendMessage(Lang.get(player, "rental.unknown-offer"));
            return true;
        }
        String failure = rentals.buy(player, offer);
        player.sendMessage(failure != null
                ? Lang.get(player, failure, "price",
                        dev.chatalyst.hook.VaultHook.format(offer.price()))
                : Lang.get(player, "rental.bought",
                        "what", offer.displayName(plugin.getConfig(), player),
                        "duration", dev.chatalyst.util.TimeUtil
                                .formatElapsed(offer.durationMillis()).trim()));
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return RentalOffer.all(plugin.getConfig()).stream()
                .map(RentalOffer::id)
                .filter(id -> id.startsWith(prefix))
                .toList();
    }
}
