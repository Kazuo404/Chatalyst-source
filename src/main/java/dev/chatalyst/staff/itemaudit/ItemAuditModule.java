package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;

/**
 * Registro de objetos del staff y control de gamemode ({@code /itemaudit}): opt-in, apagado
 * de fabrica en {@code Staff_Item_Audit.Enabled}.
 *
 * <p>Es una hoja de la suite de staff: no depende de que {@code Staff_Moderation} este
 * encendido (un servidor puede querer el registro sin el resto de la suite) y nada depende
 * de el. Los listeners se registran siempre y miran la opcion en cada evento, asi un
 * {@code /chatalyst reload} lo enciende o lo apaga sin reiniciar.
 */
public final class ItemAuditModule implements Module {

    private final ChatalystPlugin plugin;
    private ItemAuditManager manager;

    public ItemAuditModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "item audit";
    }

    @Override
    public void enable() {
        manager = new ItemAuditManager(plugin);
        manager.load();
        var events = plugin.getServer().getPluginManager();
        events.registerEvents(new ItemAuditListener(manager), plugin);
        events.registerEvents(new GamemodeGuard(manager), plugin);
        plugin.registerCommand("itemaudit", new ItemAuditCommand(manager));
        manager.start();
    }

    @Override
    public void reload() {
        if (manager == null) return;
        manager.load();
        manager.start();
    }

    /** Las filas aun sin escribir se vuelcan a disco antes de soltar nada. */
    @Override
    public void disable() {
        if (manager != null) manager.stop();
    }
}
