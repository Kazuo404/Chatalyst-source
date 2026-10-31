package dev.chatalyst.tablist;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/**
 * Modulo de presentacion de jugadores: tab list (header/footer/formato), nametags clasicos
 * (scoreboard) y nametags fancy (TextDisplay sobre la cabeza).
 */
public final class TabListModule implements Module {

    private final ChatalystPlugin plugin;
    private TabListManager tabList;
    private NametagManager nametags;
    private FancyNametagManager fancyNametags;

    public TabListModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "tablist";
    }

    @Override
    public void enable() {
        var pm = plugin.getServer().getPluginManager();

        tabList = new TabListManager(plugin);
        tabList.load();
        tabList.start();
        pm.registerEvents(new TabListListener(tabList), plugin);

        nametags = new NametagManager(plugin);
        nametags.load();
        nametags.start();
        pm.registerEvents(new NametagListener(nametags), plugin);

        // Nametag fancy (TextDisplay): estilo completo del apodo sobre la cabeza.
        fancyNametags = new FancyNametagManager(plugin, tabList, plugin.getStorageManager());
        fancyNametags.load();
        fancyNametags.start();
        pm.registerEvents(new FancyNametagListener(plugin, fancyNametags), plugin);
    }

    @Override
    public void disable() {
        if (tabList != null) {
            tabList.stop();
        }
        if (nametags != null) {
            nametags.stop();
            nametags.clearAll();
        }
        if (fancyNametags != null) {
            fancyNametags.stop();
        }
    }

    @Override
    public void reload() {
        tabList.load();
        tabList.start();
        nametags.clearAll();
        nametags.load();
        nametags.start();
        fancyNametags.load();
        fancyNametags.start();
    }

    public TabListManager tabList() {
        return tabList;
    }

    public NametagManager nametags() {
        return nametags;
    }

    public FancyNametagManager fancyNametags() {
        return fancyNametags;
    }
}
