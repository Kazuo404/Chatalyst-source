package dev.chatalyst.sign;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/** Reloadable rank perk that renders colored text on physical signs. */
public final class FancySignsModule implements Module {

    private final ChatalystPlugin plugin;
    private FancySignListener listener;

    public FancySignsModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "fancy-signs";
    }

    @Override
    public void enable() {
        listener = new FancySignListener(loadFormatter());
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void reload() {
        if (listener != null) listener.reload(loadFormatter());
    }

    private FancySignFormatter loadFormatter() {
        return FancySignFormatter.load(plugin.getConfig().getConfigurationSection("Fancy_Signs"));
    }
}
