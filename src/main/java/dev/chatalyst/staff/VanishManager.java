package dev.chatalyst.staff;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chatalyst-owned vanish state compatible with the standard {@code vanished} metadata. */
public final class VanishManager implements Listener {

    private final JavaPlugin plugin;
    private final Store store;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(JavaPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("staff_vanish");
    }

    public void load() {
        Set<UUID> previous = Set.copyOf(vanished);
        vanished.clear();
        boolean cleaned = false;
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                if (Boolean.parseBoolean(entry.getValue())) {
                    vanished.add(id);
                    continue;
                }
            } catch (RuntimeException ex) {
                // Id corrupto: se limpia igual que una entrada apagada.
            }
            store.remove(entry.getKey());
            cleaned = true;
        }
        if (cleaned) store.flushNow();
        FoliaTasks.onlinePlayers(plugin, online -> {
            UUID id = online.getUniqueId();
            if (vanished.contains(id)) {
                applyMetadata(online);
                synchronizeTarget(online);
            } else if (previous.contains(id)) {
                clearVanishState(online);
                synchronizeTarget(online);
            }
        });
    }

    public boolean isOwned(Player player) {
        return isOwned(player.getUniqueId());
    }

    public boolean isOwned(UUID id) {
        boolean stored = Boolean.parseBoolean(store.get(id.toString()));
        if (stored) vanished.add(id);
        else vanished.remove(id);
        return stored;
    }

    public boolean set(Player target, boolean enabled) {
        String key = target.getUniqueId().toString();
        boolean persisted = enabled ? store.putDurably(key, "true") : store.removeDurably(key);
        if (!persisted) {
            plugin.getLogger().severe("Could not durably update vanish for " + target.getName());
            return false;
        }
        if (enabled) {
            vanished.add(target.getUniqueId());
            applyMetadata(target);
        } else {
            vanished.remove(target.getUniqueId());
            clearVanishState(target);
        }
        synchronizeTarget(target);
        return true;
    }

    public boolean toggle(Player target) {
        boolean current = isOwned(target);
        boolean next = !current;
        return set(target, next) ? next : current;
    }

    /**
     * La lista de servidores del cliente (contador "x/y" y nombres del hover) tampoco debe
     * delatar a un vanisheado de Chatalyst. Solo se descuentan los nuestros: si otro plugin
     * de vanish ajusta el ping por su cuenta, no se resta dos veces.
     */
    @EventHandler
    public void onPing(com.destroystokyo.paper.event.server.PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("Staff_Moderation.Vanish.Hide_From_Ping", true)) {
            return;
        }
        int hidden = 0;
        for (UUID id : vanished) {
            if (plugin.getServer().getPlayer(id) != null) hidden++;
        }
        if (hidden == 0) return;
        event.setNumPlayers(Math.max(0, event.getNumPlayers() - hidden));
        event.getListedPlayers().removeIf(info -> vanished.contains(info.id()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (isOwned(joined)) {
            applyMetadata(joined);
            // Sin este aviso el staff cree que su entrada fancy "no funciona": el vanish
            // persistido silencia todo el anuncio de join. Tras el MOTD (30 ticks).
            joined.getScheduler().runDelayed(plugin, task -> {
                if (joined.isOnline() && isOwned(joined)) {
                    joined.sendMessage(dev.chatalyst.lang.Lang.get(joined,
                            "staff-moderation.vanish-join-reminder"));
                }
            }, null, 30L);
        }
        synchronizeTarget(joined);
        FoliaTasks.entity(plugin, joined, viewer -> {
            for (Player hidden : plugin.getServer().getOnlinePlayers()) {
                if (hidden.equals(viewer) || !isOwned(hidden)) continue;
                applyVisibility(viewer, hidden, true, isGhost(hidden));
            }
        });
    }

    /**
     * Modo fantasma: vanisheado Y disfrazado (Morphy / Lib's Disguises).
     *
     * <p>El vanish normal esconde la ENTIDAD, asi que estando disfrazado no se veria ni el
     * mob: el disfraz no serviria de nada. En modo fantasma el cuerpo si se muestra —eres un
     * mob cualquiera— pero sigues fuera del tablist, del contador y de los anuncios, asi que
     * nadie sabe que estas conectado.
     */
    private boolean isGhost(Player target) {
        return plugin.getConfig().getBoolean(
                        "Staff_Moderation.Vanish.Show_Body_When_Disguised", true)
                && dev.chatalyst.hook.DisguiseHook.isDisguised(target);
    }

    /** Reaplica la visibilidad de un jugador; publico para repintar al disfrazarse. */
    public void resync(Player target) {
        synchronizeTarget(target);
    }

    /**
     * Respawn y cambio de mundo reconstruyen la entrada de tablist del jugador, lo que
     * volveria a listar a un fantasma y lo delataria. Se reaplica la visibilidad despues.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        resyncLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        resyncLater(event.getPlayer());
    }

    private void resyncLater(Player target) {
        if (!isOwned(target)) return;
        // Un tick despues: durante el evento el jugador aun se esta reubicando.
        FoliaTasks.entity(plugin, target, moved -> {
            if (moved.isOnline()) synchronizeTarget(moved);
        });
    }

    private void synchronizeTarget(Player target) {
        boolean hidden = isOwned(target);
        boolean ghost = hidden && isGhost(target);
        FoliaTasks.onlinePlayers(plugin, viewer -> {
            if (viewer.equals(target)) return;
            applyVisibility(viewer, target, hidden, ghost);
        });
    }

    /**
     * Decide que ve un espectador concreto.
     *
     * <p>El orden importa: {@code unlistPlayer} solo funciona sobre un jugador VISIBLE, asi
     * que primero se muestra la entidad y despues se saca del tablist. Al reves no haria nada.
     */
    private void applyVisibility(Player viewer, Player target, boolean hidden, boolean ghost) {
        if (!hidden || StaffAccess.canSeeVanished(viewer)) {
            viewer.showPlayer(plugin, target);
            viewer.listPlayer(target);
        } else if (ghost) {
            viewer.showPlayer(plugin, target);
            viewer.unlistPlayer(target);
        } else {
            viewer.hidePlayer(plugin, target);
        }
    }

    private void applyMetadata(Player target) {
        removeMetadata(target);
        target.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        worldPresence(target, true);
    }

    private void clearVanishState(Player target) {
        removeMetadata(target);
        worldPresence(target, false);
    }

    /**
     * Ocultar la entidad no basta: el MUNDO tambien delata. Los mobs que nacen alrededor y no
     * desaparecen marcan donde hay alguien, y la noche que no pasa porque "falta uno por dormir"
     * avisa a todo el servidor de que hay un jugador mas. Mientras dura el vanish el jugador no
     * cuenta para ninguna de las dos cosas.
     */
    private static void worldPresence(Player target, boolean hidden) {
        target.setSleepingIgnored(hidden);
        target.setAffectsSpawning(!hidden);
        // Y el cuerpo: oculto para los clientes, pero el servidor lo seguia empujando. Quien
        // pasaba por donde estaba el staff chocaba con una pared invisible que se movia.
        target.setCollidable(!hidden);
    }

    /**
     * "X ha conseguido el logro..." anunciaba a todo el server que el vanisheado estaba
     * conectado, igual que un mensaje de entrada. El logro se conserva; solo calla el anuncio.
     */
    // HIGHEST: despues de que el modulo de apodos reescriba el nombre del anuncio (HIGH).
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) event.message(null);
    }

    /**
     * Un mob que persigue a la nada, o un esqueleto disparando a un punto vacio, señala al
     * vanisheado mejor que su propio nombre. Mientras dura el vanish los mobs no lo eligen
     * como objetivo.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player target && vanished.contains(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Sensores y chilladores de sculk reaccionan a los pasos: un sensor que se enciende sin
     * nadie delante es lo mismo que un cofre que se abre solo (y un chillador llama al warden).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSculk(org.bukkit.event.block.BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player target && vanished.contains(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void removeMetadata(Player target) {
        // Only remove Chatalyst's value; Essentials/CMI/SuperVanish metadata remains intact.
        target.removeMetadata("vanished", plugin);
    }

    public void shutdown() {
        // Folia ya detuvo los region schedulers cuando entra onDisable. No tocar metadata ni
        // visibilidad de entidades desde el hilo de apagado; Bukkit retira el estado asociado
        // al plugin al desregistrarlo y load()/onJoin reconstruyen el estado durable al volver.
        store.flushNow();
    }
}
