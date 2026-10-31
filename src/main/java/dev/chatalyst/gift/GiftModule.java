package dev.chatalyst.gift;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;

/**
 * Cajas de regalo: un objeto que se abre y suelta algo de una tabla.
 *
 * <p>Va detras de la tienda y del sorteo porque es su ULTIMO tramo. Aquellos deciden a quien
 * le llega algo; esto decide que es ese algo, y lo decide en el momento de abrir. Un kit, una
 * rifa o una compra pueden entregar la misma caja y ninguno necesita saber que hay dentro.
 *
 * <p>El listener se registra siempre, encendido o no: ver {@link GiftOpenListener}.
 */
public final class GiftModule implements Module {

    private final ChatalystPlugin plugin;
    private GiftService gifts;
    private RuntimeCommandRegistrar commands;

    public GiftModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "gifts";
    }

    @Override
    public void enable() {
        gifts = new GiftService(plugin, new GiftItem(plugin));
        plugin.getServer().getPluginManager().registerEvents(
                new GiftOpenListener(plugin, gifts), plugin);

        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Gifts.Command", "Gifts.Command_Aliases",
                        "caja", List.of("cajas", "giftbox"),
                        "Gift boxes and what they can contain", "chatalyst.command.gift",
                        () -> plugin.getConfig().getBoolean("Gifts.Enabled", true)),
                new GiftCommand(plugin, gifts));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        commands.apply();
    }

    @Override
    public void reload() {
        if (commands != null) commands.apply();
    }

    public GiftService service() {
        return gifts;
    }
}
