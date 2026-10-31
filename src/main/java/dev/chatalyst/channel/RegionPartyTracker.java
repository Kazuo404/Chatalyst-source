package dev.chatalyst.channel;

import dev.chatalyst.hook.PartiesHook;
import dev.chatalyst.hook.WorldGuardHook;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cachea, en el HILO PRINCIPAL, las regiones (WorldGuard) y el party (Parties) de cada jugador.
 * El chat es asincrono y no debe tocar esas APIs ni la ubicacion del jugador desde otro hilo;
 * por eso {@link ChannelManager#canSee} lee de esta cache, que es segura para lectura async.
 */
public final class RegionPartyTracker implements Listener {

    private static final Map<UUID, Set<String>> REGIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> PARTIES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PARTY_NAMES = new ConcurrentHashMap<>();
    // Valor de cada placeholder de los canales GROUP. Los placeholders de terceros no son
    // seguros fuera del hilo del jugador, asi que el chat solo lee esta copia.
    private static final Map<UUID, Map<String, String>> GROUPS = new ConcurrentHashMap<>();
    // Posicion (mundo + coordenadas) para el canal WORLD: el chat async no puede llamar a
    // getWorld(). Hasta 2s de retraso, aceptable para decidir "mismo mundo".
    private static final Map<UUID, Pos> POSITIONS = new ConcurrentHashMap<>();

    private record Pos(String world, double x, double y, double z) {
    }

    private final JavaPlugin plugin;
    private ScheduledTask task;

    public RegionPartyTracker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        // Corre SIEMPRE: aunque no haya WorldGuard/Parties, la cache de posiciones hace
        // falta para el canal WORLD (el chat async no puede leer la ubicacion).
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> refreshAll(), 20L, 40L);
        refreshAll();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void refreshAll() {
        boolean wg = WorldGuardHook.isAvailable();
        boolean parties = PartiesHook.isAvailable();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            FoliaTasks.entity(plugin, p, player -> refresh(player, wg, parties));
        }
    }

    private void refresh(Player p, boolean wg, boolean parties) {
        UUID id = p.getUniqueId();
        var loc = p.getLocation();
        String world = p.getWorld().getName();
        POSITIONS.put(id, new Pos(world, loc.getX(), loc.getY(), loc.getZ()));
        // Regiones: WorldGuard + las propias de Chatalyst (regions.yml), mezcladas. Las
        // nativas llevan prefijo "chatalyst:" asi que nunca chocan con un id de WG.
        java.util.Set<String> regionSet = new java.util.HashSet<>();
        if (wg) {
            regionSet.addAll(WorldGuardHook.currentRegions(p));
        }
        ChatRegions chatRegions = plugin instanceof dev.chatalyst.ChatalystPlugin cp
                ? cp.getChatRegions() : null;
        if (chatRegions != null && !chatRegions.isEmpty()) {
            regionSet.addAll(chatRegions.namesAt(world, loc.getX(), loc.getY(), loc.getZ()));
        }
        if (regionSet.isEmpty()) {
            REGIONS.remove(id);
        } else {
            REGIONS.put(id, java.util.Set.copyOf(regionSet));
        }
        maybeAutoSwitch(p, chatRegions, world, loc.getX(), loc.getY(), loc.getZ());
        refreshGroups(p);
        if (parties) {
            UUID pid = PartiesHook.currentPartyId(p);
            if (pid != null) {
                PARTIES.put(id, pid);
                String name = PartiesHook.currentPartyName(p);
                if (name != null && !name.isEmpty()) {
                    // Sin < > para que un nombre de party no pueda inyectar tags MiniMessage
                    // al insertarse en el formato del chat.
                    PARTY_NAMES.put(id, name.replace("<", "").replace(">", ""));
                } else {
                    PARTY_NAMES.remove(id);
                }
            } else {
                PARTIES.remove(id);
                PARTY_NAMES.remove(id);
            }
        }
    }

    private void refreshGroups(Player p) {
        var channels = plugin instanceof dev.chatalyst.ChatalystPlugin cp
                ? cp.getChannelManager() : null;
        if (channels == null) return;
        Set<String> placeholders = channels.groupPlaceholders();
        if (placeholders.isEmpty()) {
            GROUPS.remove(p.getUniqueId());
            return;
        }
        Map<String, String> values = new java.util.HashMap<>();
        for (String placeholder : placeholders) {
            String value = dev.chatalyst.hook.Placeholders.papiValue(p, placeholder);
            if (isRealGroup(value, placeholder)) values.put(placeholder, value.trim());
        }
        GROUPS.put(p.getUniqueId(), Map.copyOf(values));
    }

    /** Un placeholder sin resolver o vacio significa "sin grupo", nunca un grupo compartido. */
    static boolean isRealGroup(String value, String placeholder) {
        return value != null && !value.isBlank() && !value.trim().equals(placeholder);
    }

    // Auto-switch por region: entrar en una ChatRegion con Channel te cambia el canal de
    // escritura; salir lo restaura. Solo actua en la TRANSICION (entrar/salir), asi un
    // cambio manual del jugador dentro de la zona se respeta.
    private final Map<UUID, String> autoChannel = new ConcurrentHashMap<>();  // canal auto actual
    private final Map<UUID, String> autoPrevious = new ConcurrentHashMap<>(); // canal a restaurar

    private void maybeAutoSwitch(Player p, ChatRegions chatRegions,
                                 String world, double x, double y, double z) {
        var pl = plugin instanceof dev.chatalyst.ChatalystPlugin cp ? cp : null;
        if (pl == null || chatRegions == null || pl.getChannelManager() == null
                || !pl.getChannelManager().isEnabled()) return;
        UUID id = p.getUniqueId();
        ChatRegions.Region region = chatRegions.autoSwitchRegionAt(world, x, y, z);
        String target = region != null ? region.channel() : null;
        String current = autoChannel.get(id);
        if (java.util.Objects.equals(target, current)) return; // sin transicion

        var channels = pl.getChannelManager();
        if (target != null) {
            Channel ch = channels.byName(target);
            if (ch == null || !ch.canUse(p)) return;
            Channel writing = channels.getWriteChannel(p);
            if (writing == null || writing.name.equals(ch.name)) {
                autoChannel.put(id, target);
                return; // ya esta en ese canal
            }
            if (!channels.setWriteChannel(p, ch)) return;
            autoChannel.put(id, target);
            autoPrevious.put(id, writing.name);
            p.sendMessage(dev.chatalyst.lang.Lang.get(p, "channel.region-auto-on", "name", ch.name));
        } else {
            autoChannel.remove(id);
            String prev = autoPrevious.remove(id);
            if (prev == null) return;
            Channel writing = channels.getWriteChannel(p);
            // Si el jugador cambio de canal manualmente dentro de la zona, se respeta.
            if (writing == null || current == null || !writing.name.equals(current)) return;
            Channel back = channels.byName(prev);
            if (back == null || !back.canUse(p)) back = channels.getDefault();
            if (back == null) return;
            if (!channels.setWriteChannel(p, back)) {
                autoChannel.put(id, current);
                autoPrevious.put(id, prev);
                return;
            }
            p.sendMessage(dev.chatalyst.lang.Lang.get(p, "channel.region-auto-off", "name", back.name));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Refresco inmediato (hilo principal) para que no espere al siguiente tick del task.
        refresh(event.getPlayer(), WorldGuardHook.isAvailable(), PartiesHook.isAvailable());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        REGIONS.remove(id);
        PARTIES.remove(id);
        PARTY_NAMES.remove(id);
        POSITIONS.remove(id);
        GROUPS.remove(id);
        autoChannel.remove(id);
        autoPrevious.remove(id);
    }

    // ---- Lecturas seguras desde el hilo async del chat ----------------------

    public static boolean sharesRegion(UUID a, UUID b) {
        Set<String> ra = REGIONS.getOrDefault(a, Collections.emptySet());
        if (ra.isEmpty()) return false; // sin region: no ve el canal de region
        Set<String> rb = REGIONS.getOrDefault(b, Collections.emptySet());
        for (String id : ra) {
            if (rb.contains(id)) return true;
        }
        return false;
    }

    public static boolean sameParty(UUID a, UUID b) {
        UUID pa = PARTIES.get(a);
        return pa != null && pa.equals(PARTIES.get(b));
    }

    public static boolean sameGroup(String placeholder, UUID a, UUID b) {
        Map<String, String> first = GROUPS.get(a);
        Map<String, String> second = GROUPS.get(b);
        if (first == null || second == null) return false;
        String value = first.get(placeholder);
        return value != null && value.equals(second.get(placeholder));
    }

    /** Nombre del party del jugador (cacheado, sin &lt; &gt;), o "" si no esta en ninguno. */
    public static String partyName(UUID id) {
        return PARTY_NAMES.getOrDefault(id, "");
    }

    /** Mundo cacheado del jugador (seguro desde el chat async), o null si se desconoce. */
    public static String worldOf(UUID id) {
        Pos p = POSITIONS.get(id);
        return p != null ? p.world() : null;
    }

    /** Regiones cacheadas del jugador (WorldGuard + propias con prefijo "chatalyst:"). */
    public static Set<String> regionsOf(UUID id) {
        return REGIONS.getOrDefault(id, Collections.emptySet());
    }

    /** True si ambos estan en el mismo mundo (posiciones cacheadas; false si se desconoce). */
    public static boolean sameWorld(UUID a, UUID b) {
        Pos pa = POSITIONS.get(a);
        Pos pb = POSITIONS.get(b);
        return pa != null && pb != null && pa.world().equals(pb.world());
    }
}
