package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;

/**
 * Giveaways: the draw itself, the immunity that protects the people watching it, the prize
 * handover and the {@code /sorteo} command.
 *
 * <p>The command is registered at RUNTIME rather than declared in {@code plugin.yml}: giveaway
 * plugins are common, {@code /giveaway} is a label many of them already own, and a chat plugin
 * reserving it on every server that never runs a draw would be taking a name it does not use.
 * A label another plugin holds is skipped with a warning instead of stolen.
 *
 * <p>2026-10-03: premios hechos en el juego ({@link GiveawayCustomPrizes}, editor de cofre),
 * varios ganadores, espera entre victorias, historial y sorteos programados
 * ({@link GiveawaySchedule}).
 */
public final class GiveawayModule implements Module {

    private final ChatalystPlugin plugin;
    private GiveawayManager manager;
    private GiveawayDelivery delivery;
    private RuntimeCommandRegistrar commands;
    private GiveawayCustomPrizes customPrizes;
    private GiveawaySchedule schedule;

    public GiveawayModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "giveaway";
    }

    @Override
    public void enable() {
        GiveawayImmunity immunity = new GiveawayImmunity();
        customPrizes = new GiveawayCustomPrizes(plugin.getStorageManager());
        GiveawayRankSettings.open(plugin.getStorageManager());
        GiveawayRanks ranks = new GiveawayRanks(plugin);
        delivery = new GiveawayDelivery(plugin, ranks);
        manager = new GiveawayManager(plugin, immunity, delivery, ranks);
        manager.load();

        GiveawayAmountPrompt amounts = new GiveawayAmountPrompt(plugin, manager);
        manager.amountPrompt(amounts);

        listen(immunity);
        listen(delivery);
        listen(manager);
        listen(amounts);
        listen(new GiveawayCustomEditor.Listener(plugin, customPrizes));

        schedule = new GiveawaySchedule(plugin, manager);
        schedule.start();

        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Giveaway.Command", "Giveaway.Command_Aliases",
                        "sorteo", List.of("giveaway", "rifa", "raffle"),
                        "Server giveaways", "chatalyst.command.giveaway",
                        () -> manager != null && manager.enabled()),
                new GiveawayCommand(plugin, manager, customPrizes));
        listen(commands);
        commands.apply();
    }

    @Override
    public void disable() {
        if (schedule != null) schedule.stop();
        if (manager != null) manager.shutdown();
        if (customPrizes != null) GiveawayCustomEditor.closeAll(plugin, customPrizes);
        GiveawayCustomPrizes.clear(customPrizes);
    }

    @Override
    public void reload() {
        if (manager != null) manager.load();
        if (schedule != null) schedule.start();
        // Re-applied after the reload so renaming Giveaway.Command, or switching the feature
        // off entirely, takes effect without a restart.
        if (commands != null) commands.apply();
    }

    public GiveawayManager manager() {
        return manager;
    }

    private void listen(org.bukkit.event.Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }
}
