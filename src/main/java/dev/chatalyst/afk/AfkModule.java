package dev.chatalyst.afk;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/**
 * Modulo AFK: detecta inactividad, avisa solo al jugador, lo marca en la tab list
 * (placeholder {afk_tag}) y registra el comando /afk.
 */
public final class AfkModule implements Module {

    private final ChatalystPlugin plugin;
    private AfkManager manager;

    public AfkModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "afk";
    }

    @Override
    public void enable() {
        manager = new AfkManager(plugin);
        manager.load();
        manager.start();
        dev.chatalyst.hook.Placeholders.setAfkTagProvider(manager::tabTag);
        plugin.getServer().getPluginManager().registerEvents(new AfkListener(manager), plugin);
        plugin.registerCommand("afk", new AfkCommand(manager));
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Override
    public void reload() {
        manager.load();
        manager.start();
    }

    public AfkManager manager() {
        return manager;
    }
}
