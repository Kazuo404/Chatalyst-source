package dev.chatalyst.marriage;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.module.Module;
import dev.chatalyst.nick.NickManager;

/**
 * Matrimonios ({@code /marry}, {@code /divorce}): opt-in, apagado de fabrica.
 *
 * <p>Es una hoja — nada mas del plugin depende de esto — y por eso su ciclo de vida cabe
 * entero aqui, incluido el reload, que en el monolito vivia dentro de un metodo llamado
 * {@code applyMentionHighlight}.
 */
public final class MarriageModule implements Module {

    private final ChatalystPlugin plugin;
    private final StorageManager storage;
    private final NickManager nicks;

    private MarriageManager marriages;
    private MarriageStorage chest;
    private MarriageBank bank;
    private MarriageCeremony ceremony;
    private WeddingRite rite;

    public MarriageModule(ChatalystPlugin plugin, StorageManager storage, NickManager nicks) {
        this.plugin = plugin;
        this.storage = storage;
        this.nicks = nicks;
    }

    @Override
    public String name() {
        return "marriage";
    }

    @Override
    public void enable() {
        marriages = new MarriageManager(plugin, storage);
        marriages.load();
        Placeholders.setMarriageProvider(marriages::view);
        listen(marriages);
        chest = new MarriageStorage(plugin, marriages, storage);
        chest.load();
        listen(chest);
        bank = new MarriageBank(plugin, marriages, storage);
        bank.load();
        ceremony = new MarriageCeremony(plugin);
        ceremony.load();
        marriages.setCeremony(ceremony);
        rite = new WeddingRite(plugin, marriages, ceremony);
        rite.load();
        marriages.setRite(rite);
        listen(rite);
        MarriageCommand command = new MarriageCommand(
                plugin, marriages, chest, bank, ceremony, rite, nicks);
        plugin.register("marry", command);
        plugin.register("divorce", command);
    }

    @Override
    public void reload() {
        if (marriages != null) marriages.load();
        if (chest != null) chest.load();
        if (bank != null) bank.load();
        if (ceremony != null) {
            ceremony.load();
            ceremony.clear(); // una invitacion viva no sobrevive a un reload
        }
        if (rite != null) {
            rite.shutdown(); // una boda a medias tampoco: sus tareas ya no valen
            rite.load();
        }
    }

    /**
     * El rito primero: sus tareas programadas tocan al manager y al almacen. Y el almacen
     * ANTES que el manager, porque guarda los cofres que sigan abiertos — hacerlo tarde
     * significa perder lo que hubiera dentro al apagar.
     */
    @Override
    public void disable() {
        stopSafely("wedding ceremony", rite == null ? null : rite::shutdown);
        stopSafely("marriage storage", chest == null ? null : chest::shutdown);
        stopSafely("marriage bank", bank == null ? null : bank::shutdown);
        stopSafely("marriages", marriages == null ? null : marriages::shutdown);
    }

    public MarriageManager marriages() {
        return marriages;
    }

    private void listen(org.bukkit.event.Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void stopSafely(String component, Runnable action) {
        if (action == null) return;
        try {
            action.run();
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to shut down " + component + ": " + t.getMessage());
        }
    }
}
