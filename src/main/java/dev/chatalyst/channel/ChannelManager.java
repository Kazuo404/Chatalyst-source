package dev.chatalyst.channel;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona los canales y el estado de cada jugador (canal activo y canales silenciados),
 * con persistencia en channels-data.yml.
 */
public final class ChannelManager {

    private final JavaPlugin plugin;

    /**
     * Configuracion de canales publicada como snapshot INMUTABLE que se reemplaza entero
     * en cada load() (swap de un volatile). El chat asincrono lee canales mientras un
     * /reload recarga la config: con el snapshot siempre ve un conjunto coherente — todo
     * lo viejo o todo lo nuevo — nunca un mapa a medio rellenar (mensajes perdidos) ni
     * una ConcurrentModificationException iterando aliases.
     */
    private record Snapshot(boolean enabled, String defaultChannel, Map<String, Channel> channels) {
        private static final Snapshot EMPTY = new Snapshot(false, "global", Map.of());
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    // Modo exclusivo (Channels.Exclusive): solo se recibe el canal de escritura activo.
    private volatile boolean exclusive;

    // Estado por jugador
    private final Map<UUID, String> writeChannel = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> muted = new ConcurrentHashMap<>();
    // Suscripciones de lectura (/channel listen): se reciben aunque no sea el canal activo,
    // incluso en modo exclusivo. Eleccion explicita del jugador.
    private final Map<UUID, Set<String>> listening = new ConcurrentHashMap<>();
    private final Map<UUID, Object> stateLocks = new ConcurrentHashMap<>();

    // Persistencia en el Store "channels" (YAML o base de datos). Valor por jugador:
    // "<canal_activo>|<silenciado1>,...|<escuchado1>,...".
    private final dev.chatalyst.db.Store store;

    public ChannelManager(JavaPlugin plugin, dev.chatalyst.db.StorageManager storage) {
        this.plugin = plugin;
        this.store = storage.store("channels");
        migrateLegacy();
    }

    /** Migracion one-time del viejo channels-data.yml al Store. */
    private void migrateLegacy() {
        File old = new File(plugin.getDataFolder(), "channels-data.yml");
        if (!old.exists()) return;
        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(old);
            boolean durable = true;
            for (String key : data.getKeys(false)) {
                if (store.get(key) != null) continue;
                String w = data.getString(key + ".write", "");
                List<String> m = data.getStringList(key + ".muted");
                String migrated = (w == null ? "" : w) + "|" + String.join(",", m);
                if (!dev.chatalyst.db.StoreCas.putIfAbsentDurably(store, key, migrated)) {
                    durable = false;
                    break;
                }
            }
            if (!durable) {
                plugin.getLogger().warning("Channel migration did not reach durable storage; "
                        + "channels-data.yml was preserved and will be retried.");
                return;
            }
            if (old.renameTo(new File(plugin.getDataFolder(), "channels-data.yml.migrated"))) {
                plugin.getLogger().info("Migrated channels-data.yml to the storage backend.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not migrate channels-data.yml: " + t.getMessage());
        }
    }

    public void load() {
        boolean nextEnabled = plugin.getConfig().getBoolean("Channels.Enabled", true);
        String nextDefault = plugin.getConfig().getString("Channels.Default", "global").toLowerCase(Locale.ROOT);
        exclusive = plugin.getConfig().getBoolean("Channels.Exclusive", false);

        ConfigurationSection list = plugin.getConfig().getConfigurationSection("Channels.List");
        Set<String> integrationChannels = dev.chatalyst.hook.TradePluginsHook.detectedChannels();
        Map<String, Channel> next = loadEnabledChannels(list, integrationChannels);
        if (!next.containsKey(nextDefault) && !next.isEmpty()) {
            nextDefault = next.keySet().iterator().next();
        }
        // Publicacion atomica: los lectores async cambian de snapshot de golpe.
        snapshot = new Snapshot(nextEnabled, nextDefault, Collections.unmodifiableMap(next));
    }

    /**
     * Carga solo canales activos y conserva el orden del YAML. Es puro para poder probar que
     * un canal apagado desaparece de comandos, aliases, router y GUI desde la misma fuente.
     */
    static Map<String, Channel> loadEnabledChannels(ConfigurationSection list) {
        return loadEnabledChannels(list, Set.of());
    }

    static Map<String, Channel> loadEnabledChannels(ConfigurationSection list,
                                                    Set<String> integrationChannels) {
        if (list == null) return new LinkedHashMap<>();
        Map<String, Channel> enabled = new LinkedHashMap<>();
        for (String key : list.getKeys(false)) {
            ConfigurationSection sec = list.getConfigurationSection(key);
            if (sec == null) continue;
            String name = key.toLowerCase(Locale.ROOT);
            boolean configured = sec.getBoolean("Enabled", true);
            boolean integration = sec.getBoolean("Auto_Enable_With_Market_Plugins", false)
                    && integrationChannels.contains(name);
            if (!configured && !integration) continue;
            Channel channel = Channel.fromConfig(name, sec);
            String problem = channel.problem;
            if (problem == null && channel.type == ChannelType.PARTY
                    && !dev.chatalyst.hook.PartiesHook.isAvailable()) {
                problem = "Type PARTY needs the Parties plugin";
            }
            if (problem != null) {
                // Sin Parties es lo normal, no un fallo: el canal viene en la config de fabrica.
                if (channel.problem == null) noteSkipped(name, problem);
                else warnSkipped(name, problem);
                continue;
            }
            if (sec.getBoolean("Proxy", false) && !channel.proxy) {
                var plugin = dev.chatalyst.ChatalystPlugin.get();
                if (plugin != null) {
                    plugin.getLogger().warning("Channel '" + name + "': Proxy ignored, only GLOBAL"
                            + " channels cross servers.");
                }
            }
            enabled.put(channel.name, channel);
        }
        return enabled;
    }

    private static void warnSkipped(String name, String problem) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin != null) {
            plugin.getLogger().warning("Channel '" + name + "' not loaded: " + problem + ".");
        }
    }

    private static void noteSkipped(String name, String problem) {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin != null) {
            plugin.getLogger().info("Channel '" + name + "' not loaded: " + problem + ".");
        }
    }

    /** Placeholders de los canales GROUP activos, para cachearlos en el hilo principal. */
    public java.util.Set<String> groupPlaceholders() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Channel channel : snapshot.channels().values()) {
            if (channel.type == ChannelType.GROUP) out.add(channel.groupPlaceholder);
        }
        return out;
    }

    public boolean isEnabled() {
        Snapshot s = snapshot;
        return s.enabled() && !s.channels().isEmpty();
    }

    public Channel getDefault() {
        Snapshot s = snapshot;
        return s.channels().get(s.defaultChannel());
    }

    public Channel byName(String name) {
        if (name == null) return null;
        Map<String, Channel> channels = snapshot.channels();
        // El mapa se llena con channel.name en su CAJA ORIGINAL (no en minusculas), asi que un
        // get(toLowerCase) fallaba con cualquier canal cuyo nombre llevara mayusculas — y eso
        // rompia la resolucion por nombre (Discord por canal, escrituras, rutas). Se prueba la
        // clave tal cual y, si no, un barrido case-insensitive.
        Channel direct = channels.get(name);
        if (direct != null) return direct;
        for (Channel channel : channels.values()) {
            if (channel.name.equalsIgnoreCase(name)) return channel;
        }
        return null;
    }

    /** El primer canal de tipo FREQUENCY (el chat local por radio), o null si no hay ninguno. */
    public Channel firstFrequencyChannel() {
        for (Channel ch : snapshot.channels().values()) {
            if (ch.type == ChannelType.FREQUENCY) return ch;
        }
        return null;
    }

    public Channel byAlias(String input) {
        if (input == null) return null;
        for (Channel c : snapshot.channels().values()) {
            if (c.matchesAlias(input)) return c;
        }
        return null;
    }

    public List<Channel> all() {
        return new ArrayList<>(snapshot.channels().values());
    }

    // ---- Estado por jugador --------------------------------------------------

    public Channel getWriteChannel(Player p) {
        // Un solo snapshot para toda la decision: canal activo y default coherentes entre si.
        Snapshot s = snapshot;
        String name = writeChannel.get(p.getUniqueId());
        Channel ch = name != null ? s.channels().get(name) : null;
        if (ch == null || !ch.canUse(p)) {
            ch = s.channels().get(s.defaultChannel());
        }
        return ch;
    }

    // ---- Redireccion por mensaje (router) -------------------------------------

    // Canal destino de UN solo mensaje, puesto por el ChannelRouterListener (prioridad LOW)
    // y consumido por el ChatListener (NORMAL) dentro del MISMO AsyncChatEvent. No cambia el
    // canal de escritura del jugador.
    private record PendingRoute(String channel, boolean bypassSenderPermission) {
    }

    private final java.util.Map<java.util.UUID, PendingRoute> routedNext = new ConcurrentHashMap<>();

    /** Marca que el proximo mensaje del jugador (el evento en curso) salga por {@code ch}. */
    public void routeNextMessage(Player p, Channel ch) {
        routedNext.put(p.getUniqueId(), new PendingRoute(ch.name, false));
    }

    /** Routes a support request into a restricted staff channel without granting membership. */
    public void routeSupportMessage(Player p, Channel ch) {
        routedNext.put(p.getUniqueId(), new PendingRoute(ch.name, true));
    }

    /** True si el jugador ya tiene una redireccion pendiente (ej. /f fijo la frecuencia). */
    public boolean hasPendingRoute(java.util.UUID id) {
        return routedNext.containsKey(id);
    }

    /** Devuelve y limpia la redireccion pendiente del jugador, o null si no hay. */
    public Channel consumeRoutedMessage(Player p) {
        PendingRoute route = routedNext.remove(p.getUniqueId());
        if (route == null) return null;
        Channel ch = snapshot.channels().get(route.channel());
        return ch != null && (route.bypassSenderPermission() || ch.canUse(p)) ? ch : null;
    }

    // Canal por el que salio el ULTIMO mensaje de cada jugador (lo marca ChatListener en
    // NORMAL y lo leen los relays en MONITOR, mismo hilo del evento): asi Discord puede
    // enrutar por canal aunque el router haya movido el mensaje.
    private final java.util.Map<java.util.UUID, String> lastDelivered = new ConcurrentHashMap<>();

    public void markDelivered(Player p, Channel ch) {
        lastDelivered.put(p.getUniqueId(), ch.name);
    }

    /** Marca que el ultimo mensaje NO salio por un canal (chat plano sin canales). */
    public void markDeliveredNone(Player p) {
        lastDelivered.remove(p.getUniqueId());
    }

    /** Nombre del canal del ultimo mensaje entregado del jugador, o "". */
    public String lastDeliveredChannel(Player p) {
        return lastDelivered.getOrDefault(p.getUniqueId(), "");
    }

    public boolean setWriteChannel(Player p, Channel ch) {
        UUID id = p.getUniqueId();
        synchronized (stateLock(id)) {
            String previous = writeChannel.put(id, ch.name);
            if (persist(id)) return true;
            if (previous == null) writeChannel.remove(id);
            else writeChannel.put(id, previous);
            plugin.getLogger().severe("Could not durably update channel state for " + id);
            return false;
        }
    }

    public boolean isMuted(Player p, Channel ch) {
        Set<String> set = muted.get(p.getUniqueId());
        return set != null && set.contains(ch.name);
    }

    public void toggleMuted(Player p, Channel ch) {
        toggleMutedResult(p, ch);
    }

    public StateChange toggleMutedResult(Player p, Channel ch) {
        UUID id = p.getUniqueId();
        synchronized (stateLock(id)) {
            Set<String> set = muted.computeIfAbsent(id, u -> ConcurrentHashMap.newKeySet());
            boolean wasMuted = set.remove(ch.name);
            if (!wasMuted) set.add(ch.name);
            if (persist(id)) return new StateChange(!wasMuted, true);
            if (wasMuted) set.add(ch.name);
            else set.remove(ch.name);
            plugin.getLogger().severe("Could not durably update channel mute for " + id);
            return new StateChange(wasMuted, false);
        }
    }

    public boolean isListening(Player p, Channel ch) {
        Set<String> set = listening.get(p.getUniqueId());
        return set != null && set.contains(ch.name);
    }

    /** Alterna la suscripcion de lectura del canal. Devuelve true si quedo suscrito. */
    public boolean toggleListening(Player p, Channel ch) {
        return toggleListeningResult(p, ch).state();
    }

    public StateChange toggleListeningResult(Player p, Channel ch) {
        UUID id = p.getUniqueId();
        synchronized (stateLock(id)) {
            Set<String> set = listening.computeIfAbsent(id, u -> ConcurrentHashMap.newKeySet());
            boolean wasOn = set.remove(ch.name);
            boolean nowOn = !wasOn;
            if (nowOn) set.add(ch.name);
            if (persist(id)) return new StateChange(nowOn, true);
            if (wasOn) set.add(ch.name);
            else set.remove(ch.name);
            plugin.getLogger().severe("Could not durably update channel listen for " + id);
            return new StateChange(wasOn, false);
        }
    }

    public record StateChange(boolean state, boolean persisted) {
    }

    /**
     * Filtro de espectador independiente del emisor: ¿este jugador escucha este canal?
     * Cubre permiso, mute de canal, Auto_Listen y el modo exclusivo (Channels.Exclusive:
     * solo se recibe el canal activo; chatalyst.channel.seeall lo oye todo, para staff).
     * Tambien lo usa el proxy para los mensajes que llegan de otros servidores.
     */
    public boolean listens(Player viewer, Channel ch) {
        if (viewer == null || ch == null || !viewer.isOnline()) return false;
        if (!ch.canUse(viewer)) return false;
        if (isMuted(viewer, ch)) return false;
        if (exclusive && !ch.forceListen && !viewer.hasPermission("chatalyst.channel.seeall")) {
            // Canales de verdad: en el general solo se ve el general. Auto_Listen deja de
            // aplicar; el historial (/channel history) es la forma de ojear otros canales.
            // Excepciones: canales Force_Listen (ej. staff), el permiso seeall (moderacion)
            // y la suscripcion explicita de lectura (/channel listen).
            Channel active = getWriteChannel(viewer);
            if (active != null && active.name.equals(ch.name)) return true;
            if (isListening(viewer, ch)) return true;
            // El canal POR DEFECTO (el general del servidor) siempre se oye: estar en trade
            // no debe aislarte del chat principal — sin esto, cualquiera con otro canal
            // activo "pierde" el general y parece que el chat esta roto. El mute explicito
            // (/channel mute global) ya se respeto arriba.
            return ch.name.equals(snapshot.defaultChannel());
        }
        return ch.autoListen || ch.name.equals(writeChannel.get(viewer.getUniqueId()))
                || isListening(viewer, ch);
    }

    /** True si {@code viewer} debe recibir un mensaje enviado por {@code sender} en {@code ch}. */
    public boolean canSee(Player viewer, Player sender, Channel ch) {
        return sender != null && canSee(viewer, sender.getUniqueId(), ch);
    }

    /** Variante con snapshot de identidad, segura cuando el emisor vive en otra region. */
    public boolean canSee(Player viewer, UUID senderId, Channel ch) {
        // El chat es asincrono: viewer o sender pueden haberse desconectado a mitad de proceso.
        if (viewer == null || senderId == null || ch == null) return false;
        if (!viewer.isOnline()) return false;
        // El emisor siempre ve su propio mensaje, aunque el router lo haya movido a un canal
        // que no es su canal activo (si no, escribiria "vendo X" y no veria nada).
        if (viewer.getUniqueId().equals(senderId)) return true;
        if (!listens(viewer, ch)) return false;
        switch (ch.type) {
            case WORLD:
                // Posiciones cacheadas en el hilo principal: el chat async no puede llamar
                // a getWorld()/getLocation() (ver RegionPartyTracker).
                return RegionPartyTracker.sameWorld(viewer.getUniqueId(), senderId);
            case FREQUENCY:
                // Chat local por radio: solo se oyen quienes estan en la MISMA frecuencia
                // (sin importar la distancia). Staff con bypass oye todas las frecuencias.
                if (viewer.hasPermission("chatalyst.bypass.range")) return true;
                return FrequencyManager.same(viewer.getUniqueId(), senderId);
            case REGION:
                // Con WorldGuard o con regiones propias (/cht region), solo comparten region
                // (leido de la cache que se actualiza en el hilo principal). Sin ninguna
                // fuente de regiones, se comporta como un canal normal.
                if (!dev.chatalyst.hook.WorldGuardHook.isAvailable() && !hasChatRegions()) return true;
                return RegionPartyTracker.sharesRegion(viewer.getUniqueId(), senderId);
            case PARTY:
                // Sin confirmacion del plugin de party no se entrega a nadie mas.
                return dev.chatalyst.hook.PartiesHook.isAvailable()
                        && RegionPartyTracker.sameParty(viewer.getUniqueId(), senderId);
            case GROUP:
                return RegionPartyTracker.sameGroup(ch.groupPlaceholder,
                        viewer.getUniqueId(), senderId);
            case GLOBAL:
            default:
                return true;
        }
    }

    private static boolean hasChatRegions() {
        var pl = dev.chatalyst.ChatalystPlugin.get();
        return pl != null && pl.getChatRegions() != null && !pl.getChatRegions().isEmpty();
    }

    // ---- Persistencia --------------------------------------------------------

    /** Restaura el estado guardado sin politica de entrada (recargas con gente online). */
    public void loadPlayer(Player p) {
        loadPlayer(p, false);
    }

    /**
     * Carga el estado del jugador. Con {@code joining} (entrada real al servidor) y
     * Channels.On_Join en "default" (el valor de fabrica), TODOS empiezan escribiendo en
     * el canal por defecto — el general — aunque se fueran en trade/staff; "last" recuerda
     * el ultimo canal. Los mutes y las suscripciones listen se restauran siempre.
     */
    public void loadPlayer(Player p, boolean joining) {
        String raw = store.get(p.getUniqueId().toString());
        if (raw == null || raw.isEmpty()) return;
        boolean restoreWrite = !joining
                || "last".equalsIgnoreCase(plugin.getConfig().getString("Channels.On_Join", "default"));
        // Formato "w|m1,m2|l1,l2"; el tercer campo (listen) puede faltar en datos antiguos.
        String[] parts = raw.split("\\|", 3);
        String w = parts[0];
        String m = parts.length > 1 ? parts[1] : "";
        String l = parts.length > 2 ? parts[2] : "";
        if (restoreWrite && !w.isEmpty() && snapshot.channels().containsKey(w)) writeChannel.put(p.getUniqueId(), w);
        if (!m.isEmpty()) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.addAll(List.of(m.split(",")));
            muted.put(p.getUniqueId(), set);
        }
        if (!l.isEmpty()) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.addAll(List.of(l.split(",")));
            listening.put(p.getUniqueId(), set);
        }
    }

    public void unloadPlayer(Player p) {
        // El estado ya esta en el store (se actualiza en cada cambio); aqui liberamos memoria.
        writeChannel.remove(p.getUniqueId());
        muted.remove(p.getUniqueId());
        listening.remove(p.getUniqueId());
        stateLocks.remove(p.getUniqueId());
        // Tambien la marca del relay de Discord: sin esto quedaba una entrada por cada
        // jugador que chateo y se fue, creciendo sin limite hasta el reinicio.
        lastDelivered.remove(p.getUniqueId());
        // Y una redireccion que quedo sin consumir (salio entre el router y la entrega).
        routedNext.remove(p.getUniqueId());
    }

    private Object stateLock(UUID id) {
        return stateLocks.computeIfAbsent(id, ignored -> new Object());
    }

    private boolean persist(UUID id) {
        String w = writeChannel.get(id);
        Set<String> m = muted.get(id);
        Set<String> l = listening.get(id);
        boolean noMuted = m == null || m.isEmpty();
        boolean noListen = l == null || l.isEmpty();
        // Escritura diferida, no durable: es una PREFERENCIA (en que canal escribe), no una
        // sancion. Con YAML "durable" es volcar el archivo entero en el hilo del jugador, y un
        // canal automatico por region lo hacia cada vez que alguien cruzaba la frontera.
        if (w == null && noMuted && noListen) {
            store.remove(id.toString());
            return true;
        }
        store.put(id.toString(),
                (w == null ? "" : w) + "|" + (noMuted ? "" : String.join(",", m))
                        + "|" + (noListen ? "" : String.join(",", l)));
        return true;
    }

    /** Volcado final sincrono al apagar el plugin (garantiza no perder cambios recientes). */
    public void shutdown() {
        store.flushNow();
    }
}
