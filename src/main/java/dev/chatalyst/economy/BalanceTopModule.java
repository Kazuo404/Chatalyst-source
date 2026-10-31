package dev.chatalyst.economy;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Modulo de baltop: ranking de dinero (via Vault), calculado async y cacheado,
 * con su comando /baltop.
 */
public final class BalanceTopModule implements Module {

    private final ChatalystPlugin plugin;
    private BalanceTop balanceTop;
    private ScheduledTask startupTask;

    public BalanceTopModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "baltop";
    }

    @Override
    public void enable() {
        balanceTop = new BalanceTop(plugin);
        balanceTop.load();
        plugin.registerCommand("baltop", new BalanceTopCommand(balanceTop));
        // El primer tick ocurre despues de que todos los plugins hayan terminado onEnable.
        // EssentialsX ya habra registrado entonces su proveedor Economy en Vault.
        startupTask = plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                task -> {
                    startupTask = null;
                    balanceTop.start();
                }, 1L);
    }

    @Override
    public void disable() {
        cancelStartupTask();
        if (balanceTop != null) {
            balanceTop.stop();
        }
    }

    @Override
    public void reload() {
        cancelStartupTask();
        balanceTop.load();
        balanceTop.start();
    }

    private void cancelStartupTask() {
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
    }

    public BalanceTop balanceTop() {
        return balanceTop;
    }
}
