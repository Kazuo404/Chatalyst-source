package dev.chatalyst.itemwatch;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/** Vigilancia de objetos (posibles dupes): reglas, escaneo periodico y /itemwatch. */
public final class ItemWatchModule implements Module {

    private final ChatalystPlugin plugin;
    private ItemWatchManager manager;

    public ItemWatchModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "itemwatch";
    }

    @Override
    public void enable() {
        manager = new ItemWatchManager(plugin);
        manager.load();
        plugin.registerCommand("itemwatch", new ItemWatchCommand(plugin, manager));
        plugin.getServer().getPluginManager().registerEvents(new ItemWatchListener(manager), plugin);
        manager.start();
    }

    @Override
    public void disable() {
        if (manager != null) manager.stop();
    }

    @Override
    public void reload() {
        manager.load();
        manager.start();
    }
}
