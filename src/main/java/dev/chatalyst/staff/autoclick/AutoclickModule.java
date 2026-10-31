package dev.chatalyst.staff.autoclick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/**
 * Detector de autoclick de la suite de staff: opt-in, apagado de fabrica. Es una hoja (nadie
 * depende de el) y por eso su ciclo de vida cabe entero aqui. Se enciende despues del staff
 * para poder escribir en su historial.
 */
public final class AutoclickModule implements Module {

    private final ChatalystPlugin plugin;
    private AutoclickManager manager;

    public AutoclickModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "autoclick";
    }

    @Override
    public void enable() {
        manager = new AutoclickManager(plugin);
        manager.load();
        plugin.registerCommand("autoclick", new AutoclickCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new AutoclickListener(manager), plugin);
        manager.start();
    }

    @Override
    public void reload() {
        if (manager == null) return;
        manager.load();
        manager.start();
    }

    @Override
    public void disable() {
        if (manager != null) manager.stop();
    }
}
