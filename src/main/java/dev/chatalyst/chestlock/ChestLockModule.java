package dev.chatalyst.chestlock;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;

/**
 * Candados de cofres, independientes de terrenos y clanes: shift + clic derecho con un cartel.
 * Todo se configura en {@code chestlocks.yml}; apagado por defecto.
 */
public final class ChestLockModule implements Module {

    private final ChatalystPlugin plugin;
    private LockService locks;
    private RuntimeCommandRegistrar commands;

    public ChestLockModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "chestlocks";
    }

    @Override
    public void enable() {
        locks = new LockService(plugin);
        LockListener listener = new LockListener(plugin, locks);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        registerCopperGolem(listener);
        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Chest_Locks.Command", "Chest_Locks.Command_Aliases",
                        "candado", List.of("lock", "proteger"),
                        "Chest locks: who can open the chest you look at", "chatalyst.command.lock",
                        () -> plugin.getConfig().getBoolean("Chest_Locks.Enabled", false)),
                new LockCommand(locks));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        commands.apply();
    }

    /**
     * El golem de cobre (servidores 1.21.9+) mueve objetos sin InventoryMoveItemEvent: se le
     * niega cualquier contenedor protegido como origen o destino. El evento no existe en la API
     * contra la que compila Chatalyst (1.21.1), asi que se registra por nombre si esta.
     */
    private void registerCopperGolem(LockListener listener) {
        Class<? extends org.bukkit.event.Event> type;
        try {
            type = Class.forName("io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent")
                    .asSubclass(org.bukkit.event.Event.class);
        } catch (ClassNotFoundException | ClassCastException absent) {
            return;
        }
        java.lang.reflect.Method getBlock;
        java.lang.reflect.Method setAllowed;
        try {
            getBlock = type.getMethod("getBlock");
            setAllowed = type.getMethod("setAllowed", boolean.class);
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("Chest locks: the copper golem event changed; golems are not blocked.");
            return;
        }
        plugin.getServer().getPluginManager().registerEvent(type, listener,
                org.bukkit.event.EventPriority.LOW, (l, event) -> {
                    if (!type.isInstance(event) || !locks.settings().enabled()) return;
                    try {
                        if (getBlock.invoke(event) instanceof org.bukkit.block.Block block
                                && locks.settings().isLockable(block.getType())
                                && locks.activeLock(block).isPresent()) {
                            setAllowed.invoke(event, false);
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // un fallo aqui no puede tumbar el tick del golem
                    }
                }, plugin, true);
    }

    @Override
    public void reload() {
        if (locks != null) locks.reload();
        if (commands != null) commands.apply();
    }
}
