package dev.chatalyst.mail;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.module.Module;

/**
 * Modulo de correo: manager, composicion, notificaciones, listeners y el comando /inbox.
 */
public final class MailModule implements Module {

    private final ChatalystPlugin plugin;
    private final StorageManager storage;
    private MailManager manager;
    private MailComposeManager compose;
    private MailNotifier notifier;

    public MailModule(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public String name() {
        return "mail";
    }

    @Override
    public void enable() {
        manager = new MailManager(plugin, storage);
        compose = new MailComposeManager();
        notifier = new MailNotifier(plugin);
        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new MailListener(plugin, manager, notifier), plugin);
        pm.registerEvents(new MailBookListener(plugin, manager, compose, notifier), plugin);
        plugin.registerCommand("inbox", new MailCommand(plugin, manager, compose, notifier));
    }

    @Override
    public void disable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public MailManager manager() {
        return manager;
    }

    public MailComposeManager compose() {
        return compose;
    }

    public MailNotifier notifier() {
        return notifier;
    }
}
