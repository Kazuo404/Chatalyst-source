package dev.chatalyst.chat;

import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache del item en mano de cada jugador, actualizada SIEMPRE en el hilo principal
 * (join, cambio de slot de la hotbar y un refresco periodico de 2s). El chat es async
 * y no puede tocar el inventario: el token [item] lee esta cache (clones detachados),
 * mismo patron que RegionPartyTracker para regiones/party.
 */
public final class HeldItemTracker implements Listener {

    private static final Map<UUID, ItemStack> HELD = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private ScheduledTask task;

    public HeldItemTracker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Arranca (o reinicia) el refresco periodico; no hace nada si el token esta apagado. */
    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("Chat.Item_Placeholder", true)) return;
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                FoliaTasks.entity(plugin, p, this::update);
            }
        }, 20L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        HELD.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        // Cambio de slot de hotbar: el evento trae el slot NUEVO (el item en mano aun es el viejo).
        put(event.getPlayer().getUniqueId(), event.getPlayer().getInventory().getItem(event.getNewSlot()));
    }

    private void update(Player p) {
        put(p.getUniqueId(), p.getInventory().getItemInMainHand());
    }

    private static void put(UUID id, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            HELD.remove(id);
        } else {
            HELD.put(id, item.clone());
        }
    }

    /** Item en mano cacheado (clon), o null si mano vacia. Seguro desde el chat async. */
    public static ItemStack held(UUID id) {
        return HELD.get(id);
    }
}
