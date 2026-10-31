package dev.chatalyst.log;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;
import dev.chatalyst.pm.PrivateMessages;

/**
 * Modulo de registro de chat: LogManager (archivo/rotacion), listener de captura,
 * el comando /chatlog y el enganche del log de mensajes privados.
 */
public final class LogModule implements Module {

    private final ChatalystPlugin plugin;
    private final PrivateMessages privateMessages;
    private LogManager manager;

    public LogModule(ChatalystPlugin plugin, PrivateMessages privateMessages) {
        this.plugin = plugin;
        this.privateMessages = privateMessages;
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void enable() {
        manager = new LogManager(plugin);
        manager.load();
        manager.start();
        privateMessages.setLog(manager);
        plugin.getServer().getPluginManager().registerEvents(new LogListener(manager), plugin);
        plugin.registerCommand("chatlog", new ChatLogCommand(plugin, manager));
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

    public LogManager manager() {
        return manager;
    }
}
