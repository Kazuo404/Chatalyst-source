package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.api.ChatalystApi;
import dev.chatalyst.api.TitlesApi;

/** Runtime implementation registered in Bukkit's services manager. */
public final class DefaultChatalystApi implements ChatalystApi {

    private final TitlesApi titles;

    public DefaultChatalystApi(ChatalystPlugin plugin, TagManager manager,
                               TagCatalog catalog) {
        this.titles = new DefaultTitlesApi(plugin, manager, catalog);
    }

    @Override
    public TitlesApi titles() {
        return titles;
    }
}
