package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;

/**
 * Alquiler de ventajas con dinero del juego.
 *
 * <p>El comando se registra en RUNTIME y no en {@code plugin.yml}, como {@code /sorteo}: "rent"
 * y "alquilar" son etiquetas que otro plugin puede tener, y reservarlas en un server que no use
 * esto seria quedarse un nombre que no se usa.
 */
public final class RentalModule implements Module {

    private final ChatalystPlugin plugin;
    private RentalService rentals;
    private RuntimeCommandRegistrar commands;

    public RentalModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "rental";
    }

    @Override
    public void enable() {
        rentals = new RentalService(plugin);
        // El canje va SIEMPRE, aunque el menu propio este apagado: un vale ya comprado en otra
        // tienda tiene que poder canjearse, y apagar el menu no puede convertir en basura algo
        // por lo que alguien ya pago.
        plugin.getServer().getPluginManager().registerEvents(
                new RentalVoucherListener(plugin, rentals), plugin);
        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Rentals.Command", "Rentals.Command_Aliases",
                        "alquilar", List.of("rent", "alquiler"),
                        "Rent perks with in-game money", "chatalyst.command.rental",
                        () -> plugin.getConfig().getBoolean("Rentals.Enabled", false)),
                new RentalCommand(plugin, rentals));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        commands.apply();
    }

    @Override
    public void reload() {
        if (commands != null) commands.apply();
    }

    public RentalService service() {
        return rentals;
    }
}
