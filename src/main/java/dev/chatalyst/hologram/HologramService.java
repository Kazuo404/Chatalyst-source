package dev.chatalyst.hologram;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.announce.AnnounceAnimator;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Los carteles flotantes de este servidor: definiciones, entidades vivas y su reloj.
 *
 * <p>Reglas de rendimiento:
 * <ul>
 *   <li>Un solo reloj global cada {@code Update_Interval_Ticks}. Por cada cartel con su chunk
 *       cargado decide si toca comprobar quien hay cerca (cada {@code Presence_Check_Ticks},
 *       con una busqueda espacial del propio mundo: nada de jugadores x carteles) o repintar.</li>
 *   <li>Solo se repinta un cartel animado con alguien cerca, y solo lo que cambio.</li>
 *   <li>Todo lo que toca entidades corre en el hilo de la region del cartel (FoliaTasks).</li>
 * </ul>
 *
 * <p>Las entidades no se guardan con el mundo ({@code setPersistent(false)}) y llevan una marca
 * con la sesion: lo que quede de un reload o de un crash se reconoce como huerfano y se borra.
 */
public final class HologramService {

    /** Que jugadores ven una entidad ademas de la regla por defecto. */
    enum Visibility { NORMAL, HIDE_FROM_BEDROCK, BEDROCK_ONLY }

    private final ChatalystPlugin plugin;
    private final HologramStore store;
    private final NamespacedKey key;
    private final String session = Long.toString(System.nanoTime() ^ System.currentTimeMillis(), 36);
    private final Map<String, Hologram> holograms = new ConcurrentSkipListMap<>();
    private final Map<String, HologramInstance> instances = new ConcurrentHashMap<>();
    /** Toda entidad viva nuestra: lo que lleve la marca y no este aqui es un huerfano. */
    private final Map<UUID, Tracked> entities = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong();
    /**
     * Carteles por chunk ("mundo|x|z" -> ids). Los eventos de carga/descarga de chunk llegan a
     * cientos por segundo con gente volando: mirarlos aqui es O(1) en vez de recorrer todos los
     * carteles en cada uno. Se rehace entero solo cuando cambia la lista de carteles (raro).
     */
    private volatile Map<String, List<String>> byChunk = Map.of();
    private volatile HoloSettings settings;
    private ScheduledTask ticker;
    /**
     * Preguntado una vez: FoliaTasks.isFolia() busca una clase por nombre, y en Paper eso es
     * una excepcion con su traza en cada llamada — el reloj la pagaba en cada pasada.
     */
    private final boolean folia = FoliaTasks.isFolia();

    private record Tracked(Entity entity, HologramInstance owner, Visibility visibility) {
    }

    HologramService(ChatalystPlugin plugin, HoloSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = new HologramStore(plugin);
        this.key = new NamespacedKey(plugin, "hologram");
    }

    // ---- Ciclo de vida -------------------------------------------------------------------

    void start() {
        for (Hologram hologram : store.load()) holograms.put(hologram.id(), hologram);
        reindex();
        removeOrphansInLoadedWorlds();
        for (Hologram hologram : holograms.values()) spawnIfLoaded(hologram);
        int period = settings.updateTicks();
        ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> tick(period), period, period);
        if (!holograms.isEmpty()) {
            plugin.getLogger().info("Loaded " + holograms.size() + " hologram(s).");
        }
    }

    void stop() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        // En Folia no se pueden tocar regiones al apagar; las entidades no se guardan con el
        // mundo y el siguiente arranque barre las que quedasen cargadas.
        if (!FoliaTasks.isFolia()) {
            for (HologramInstance instance : instances.values()) {
                try {
                    instance.remove();
                } catch (RuntimeException ignored) {
                    // Apagando: lo que no se pueda quitar desaparece al descargar el chunk.
                }
            }
        }
        instances.clear();
        entities.clear();
        store.save(holograms.values());
    }

    void reloadSettings(HoloSettings next) {
        boolean periodChanged = next.updateTicks() != settings.updateTicks();
        settings = next;
        if (periodChanged && ticker != null) {
            ticker.cancel();
            int period = next.updateTicks();
            ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    task -> tick(period), period, period);
        }
        // Los estilos con nombre pueden haber cambiado: se repinta todo.
        holograms.keySet().forEach(this::rebuild);
    }

    /** Relee data/holograms.yml (por si el dueño lo edito a mano). */
    int reloadFromDisk() {
        for (String id : List.copyOf(instances.keySet())) despawn(id);
        holograms.clear();
        for (Hologram hologram : store.load()) holograms.put(hologram.id(), hologram);
        reindex();
        for (Hologram hologram : holograms.values()) spawnIfLoaded(hologram);
        return holograms.size();
    }

    private static String chunkKey(String world, int x, int z) {
        return world + '|' + x + '|' + z;
    }

    /** Rehace el indice por chunk. Solo al crear, mover, borrar o recargar carteles. */
    private void reindex() {
        Map<String, List<String>> next = new java.util.HashMap<>();
        for (Hologram hologram : holograms.values()) {
            Hologram.Position p = hologram.position();
            next.computeIfAbsent(chunkKey(p.world(), p.chunkX(), p.chunkZ()),
                    k -> new ArrayList<>()).add(hologram.id());
        }
        next.replaceAll((k, v) -> List.copyOf(v));
        byChunk = Map.copyOf(next);
    }

    HoloSettings settings() {
        return settings;
    }

    NamespacedKey key() {
        return key;
    }

    String session() {
        return session;
    }

    ChatalystPlugin plugin() {
        return plugin;
    }

    // ---- Imagenes (pixel art) -------------------------------------------------------------

    private volatile ImageLibrary images;
    /** Cargas en marcha (fuente@ancho): una sola peticion aunque se reconstruya mil veces. */
    private final Set<String> waitingImages = ConcurrentHashMap.newKeySet();
    /**
     * Cargas que fallaron (fuente@ancho -> motivo). No se reintentan solas: reintentar al
     * reconstruir era un bucle (falla -> reconstruye -> vuelve a pedir -> falla...). Se olvidan
     * con /cartel recargar.
     */
    private final Map<String, String> imageFailures = new ConcurrentHashMap<>();

    void images(ImageLibrary library) {
        this.images = library;
    }

    ImageLibrary images() {
        return images;
    }

    ImageSettings imageSettings() {
        ImageLibrary library = images;
        return library == null ? ImageSettings.DEFAULT : library.settings();
    }

    private String imageKey(ImageSource.PixelLine spec) {
        return spec.source().value() + "@" + imageSettings().pixelWidth(spec.width());
    }

    /**
     * El pixel art de una linea si ya esta listo; si no, null y se pide en segundo plano (al
     * llegar se reconstruyen los carteles que lo usan). Nunca bloquea.
     */
    ImageLibrary.PixelArtImage pixelArt(HoloLine line) {
        ImageLibrary library = images;
        ImageSource.PixelLine spec = ImageSource.parsePixelLine(line.content());
        if (library == null || spec == null) return null;
        String key = imageKey(spec);
        if (imageFailures.containsKey(key)) return null;
        var future = library.pixelArt(spec.source(), imageSettings().pixelWidth(spec.width()));
        if (future.isDone() && !future.isCompletedExceptionally()) return future.getNow(null);
        if (waitingImages.add(key)) {
            future.whenComplete((art, failure) -> {
                waitingImages.remove(key);
                if (failure != null) {
                    String reason = ImageLibrary.reason(failure);
                    imageFailures.put(key, reason);
                    plugin.getLogger().warning("Hologram image " + spec.source().shown() + ": "
                            + reason);
                }
                FoliaTasks.global(plugin, () -> rebuildUsing(spec.source().value()));
            });
        }
        return null;
    }

    /** Motivo por el que no se pudo cargar la imagen de una linea, o null. */
    String imageFailure(HoloLine line) {
        ImageSource.PixelLine spec = ImageSource.parsePixelLine(line.content());
        return spec == null ? "invalid image" : imageFailures.get(imageKey(spec));
    }

    /** Reconstruye los carteles que pintan esa imagen (acaba de cargarse o de fallar). */
    private void rebuildUsing(String source) {
        for (Hologram hologram : holograms.values()) {
            for (HoloLine line : hologram.lines()) {
                ImageSource.PixelLine spec = line.isImage()
                        ? ImageSource.parsePixelLine(line.content()) : null;
                if (spec != null && spec.source().value().equals(source)) {
                    rebuild(hologram.id());
                    break;
                }
            }
        }
    }

    /** Olvida fallos y cache de imagenes (tras /cartel recargar). */
    void forgetImages() {
        imageFailures.clear();
        ImageLibrary library = images;
        if (library != null) library.clear();
    }

    // ---- Definiciones ----------------------------------------------------------------------

    public Hologram get(String id) {
        return id == null ? null : holograms.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public Collection<Hologram> all() {
        return List.copyOf(holograms.values());
    }

    boolean full() {
        return holograms.size() >= settings.maxHolograms();
    }

    /** Alta de uno nuevo; false si el id ya existe. */
    boolean create(Hologram hologram) {
        if (holograms.putIfAbsent(hologram.id(), hologram) != null) return false;
        reindex();
        persist();
        spawnIfLoaded(hologram);
        return true;
    }

    /** Aplica un cambio y reconstruye; devuelve el resultado o null si no existe. */
    Hologram update(String id, UnaryOperator<Hologram> change) {
        Hologram[] result = new Hologram[1];
        Hologram previous = get(id);
        if (previous == null) return null;
        holograms.computeIfPresent(previous.id(), (ignored, current) -> {
            Hologram next = change.apply(current);
            result[0] = next == null ? current : next;
            return result[0];
        });
        if (result[0] == null) return null;
        persist();
        Hologram before = previous;
        // Si cambio de sitio, las entidades viejas viven en otra region: se quitan alli.
        if (!before.position().equals(result[0].position())) {
            reindex();
            despawn(before.id());
            spawnIfLoaded(result[0]);
        } else {
            rebuild(result[0].id());
        }
        return result[0];
    }

    boolean delete(String id) {
        Hologram removed = holograms.remove(id);
        if (removed == null) return false;
        reindex();
        despawn(removed.id());
        persist();
        return true;
    }

    private void persist() {
        store.saveAsync(holograms.values());
    }

    // ---- Entidades -------------------------------------------------------------------------

    private Location location(Hologram hologram) {
        World world = plugin.getServer().getWorld(hologram.position().world());
        if (world == null) return null;
        Hologram.Position p = hologram.position();
        return new Location(world, p.x(), p.y(), p.z());
    }

    private void spawnIfLoaded(Hologram hologram) {
        Location at = location(hologram);
        if (at == null) return;
        FoliaTasks.region(plugin, at, () -> {
            if (!at.getWorld().isChunkLoaded(hologram.position().chunkX(),
                    hologram.position().chunkZ())) return;
            spawnHere(hologram, at.getWorld());
        });
    }

    /** En el hilo de la region, con el chunk cargado. */
    private void spawnHere(Hologram current, World world) {
        Hologram latest = holograms.get(current.id());
        if (latest == null) return;
        HologramInstance old = instances.remove(latest.id());
        if (old != null) old.remove();
        HologramInstance instance = new HologramInstance(this, latest, world);
        instances.put(latest.id(), instance);
        try {
            instance.spawn(tick.get());
        } catch (RuntimeException failure) {
            instances.remove(latest.id(), instance);
            instance.remove();
            plugin.getLogger().warning("Could not spawn hologram " + latest.id() + ": "
                    + failure.getMessage());
        }
    }

    private void rebuild(String id) {
        Hologram hologram = holograms.get(id);
        if (hologram == null) return;
        Location at = location(hologram);
        if (at == null) return;
        FoliaTasks.region(plugin, at, () -> {
            if (!at.getWorld().isChunkLoaded(hologram.position().chunkX(),
                    hologram.position().chunkZ())) {
                HologramInstance stale = instances.remove(id);
                if (stale != null) stale.remove();
                return;
            }
            spawnHere(hologram, at.getWorld());
        });
    }

    private void despawn(String id) {
        HologramInstance instance = instances.remove(id);
        if (instance == null) return;
        FoliaTasks.region(plugin, instance.base, instance::remove);
    }

    /** Un chunk se cargo: aparecen sus carteles (un tick despues, con el chunk ya listo). */
    void onChunkLoad(Chunk chunk) {
        List<String> here = byChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (here == null) return;
        for (String id : here) {
            Hologram hologram = holograms.get(id);
            if (hologram != null && !instances.containsKey(id)) spawnIfLoaded(hologram);
        }
    }

    /** Un chunk se descarga: sus entidades no se guardan, pero se sueltan del registro. */
    void onChunkUnload(Chunk chunk) {
        List<String> here = byChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (here == null) return;
        for (String id : here) {
            HologramInstance instance = instances.get(id);
            if (instance == null) continue;
            Hologram.Position p = instance.hologram.position();
            // El indice es por id: si se movio a otro chunk a la vez, no se toca.
            if (p.chunkX() == chunk.getX() && p.chunkZ() == chunk.getZ()
                    && instances.remove(id, instance)) {
                instance.remove();
            }
        }
    }

    /** Lleva nuestra marca pero no es de esta sesion o ya no esta registrada: fuera. */
    boolean isOrphan(Entity entity) {
        // Primero el tipo: al cargar un chunk llegan TODOS sus mobs, y leer el PDC de cada uno
        // para buscar carteles era trabajo tirado. Solo estos tipos pueden ser nuestros.
        if (!candidate(entity)) return false;
        String mark = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return mark != null && !entities.containsKey(entity.getUniqueId());
    }

    boolean isOurs(Entity entity) {
        return candidate(entity)
                && entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** Las unicas clases de entidad que crea un cartel. */
    private static boolean candidate(Entity entity) {
        return entity instanceof org.bukkit.entity.Display
                || entity instanceof org.bukkit.entity.Interaction
                || entity instanceof org.bukkit.entity.ArmorStand;
    }

    private void removeOrphansInLoadedWorlds() {
        if (FoliaTasks.isFolia()) {
            // Cada chunk es de su region: se barre al cargarse (EntitiesLoadEvent).
            return;
        }
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOrphan(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover hologram entities.");
        }
    }

    void track(Entity entity, HologramInstance owner, Visibility visibility) {
        if (entity == null) return;
        entities.put(entity.getUniqueId(), new Tracked(entity, owner, visibility));
        if (visibility == Visibility.NORMAL) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isBedrock(player.getUniqueId())) applyVisibility(player, entity, visibility);
        }
    }

    void untrack(Entity entity) {
        if (entity != null) entities.remove(entity.getUniqueId());
    }

    void showTo(Player player, Entity entity) {
        onPlayer(player, p -> p.showEntity(plugin, entity));
    }

    /** Un jugador de Bedrock entra: se le ocultan o enseñan las entidades que toquen. */
    void onJoin(Player player) {
        if (!isBedrock(player.getUniqueId())) return;
        for (Tracked tracked : entities.values()) {
            if (tracked.visibility() != Visibility.NORMAL) {
                applyVisibility(player, tracked.entity(), tracked.visibility());
            }
        }
    }

    void onQuit(Player player) {
        UUID id = player.getUniqueId();
        String prefix = id + "|";
        cooldowns.keySet().removeIf(entry -> entry.startsWith(prefix));
        for (HologramInstance instance : instances.values()) {
            if (!instance.personal()) continue;
            Runnable drop = () -> {
                if (instances.get(instance.hologram.id()) == instance) instance.dropViewer(id);
            };
            if (folia) FoliaTasks.region(plugin, instance.base, drop);
            else drop.run();
        }
    }

    private void applyVisibility(Player player, Entity entity, Visibility visibility) {
        onPlayer(player, p -> {
            if (visibility == Visibility.HIDE_FROM_BEDROCK) p.hideEntity(plugin, entity);
            else if (visibility == Visibility.BEDROCK_ONLY) p.showEntity(plugin, entity);
        });
    }

    private void onPlayer(Player player, java.util.function.Consumer<Player> action) {
        if (folia) FoliaTasks.entity(plugin, player, action);
        else action.accept(player);
    }

    /** Floodgate crea los UUID de Bedrock con los 64 bits altos a cero (como el resto de Chatalyst). */
    static boolean isBedrock(UUID id) {
        return dev.chatalyst.staff.autoclick.ClickTracker.isBedrock(id);
    }

    // ---- Reloj ---------------------------------------------------------------------------

    private void tick(int period) {
        long now = tick.addAndGet(period);
        HoloSettings current = settings;
        for (HologramInstance instance : instances.values()) {
            boolean presenceDue = now - instance.lastPresence >= current.presenceTicks();
            boolean paint = instance.active && instance.needsTick(now);
            if (!presenceDue && !paint) continue;
            // needsTick tiene efecto (marca el refresco de placeholders): se pregunta UNA vez.
            Runnable work = () -> {
                if (instances.get(instance.hologram.id()) != instance) return;
                if (presenceDue) presence(instance, now);
                if (paint && instance.active) instance.render(now, false);
            };
            // En Paper el reloj global YA es el hilo principal: programar una tarea por cartel y
            // tick era una asignacion y un salto al siguiente tick por cada uno, para nada.
            if (folia) FoliaTasks.region(plugin, instance.base, work);
            else work.run();
        }
        // Solo vale para esta pasada: no se guardan mundos ni jugadores de un tick a otro.
        playersByWorld.clear();
    }

    /**
     * Jugadores de cada mundo en ESTA pasada del reloj (solo Paper, donde todo corre en el hilo
     * principal). Se pide una vez por mundo y pasada, no una por cartel.
     */
    private final Map<World, List<Player>> playersByWorld = new java.util.IdentityHashMap<>();
    private final Location scratch = new Location(null, 0, 0, 0);

    /**
     * Quien hay cerca. En Paper se recorre la lista de jugadores del mundo (decenas) en vez de
     * pedir al mundo una busqueda espacial por cartel: esa busqueda recorre TODAS las entidades
     * de la caja (mobs de una granja, marcos, las de otros carteles) para quedarse con los
     * jugadores, y con cientos de carteles era el grueso del coste del reloj. En Folia no se
     * pueden leer jugadores de otras regiones, asi que alli sigue la busqueda de la region.
     */
    private void presence(HologramInstance instance, long now) {
        instance.lastPresence = now;
        World world = instance.base.getWorld();
        if (instance.broken()) {
            Hologram.Position p = instance.hologram.position();
            if (!world.isChunkLoaded(p.chunkX(), p.chunkZ())) {
                // El chunk se fue sin que llegara su aviso: rehacerlo aqui lo recrearia en
                // falso una vez por comprobacion. Se suelta y vuelve con el chunk.
                if (instances.remove(instance.hologram.id(), instance)) instance.remove();
                return;
            }
            // Alguien mato una entidad (/kill @e...): se rehace entero.
            spawnHere(instance.hologram, world);
            return;
        }
        double range = Math.min(instance.hologram.look().viewRange(),
                instance.personal() ? instance.hologram.look().viewRange()
                        : settings.activationRange());
        List<Player> nearby;
        if (folia) {
            nearby = new ArrayList<>(instance.base.getNearbyPlayers(range));
        } else {
            nearby = new ArrayList<>();
            double max = range * range;
            for (Player player : playersByWorld.computeIfAbsent(world, World::getPlayers)) {
                player.getLocation(scratch);
                if (withinRange(scratch.getX() - instance.base.getX(),
                        scratch.getY() - instance.base.getY(),
                        scratch.getZ() - instance.base.getZ(), max)) {
                    nearby.add(player);
                }
            }
        }
        instance.active = !nearby.isEmpty();
        instance.syncViewers(nearby, now);
    }

    static boolean withinRange(double dx, double dy, double dz, double maxSquared) {
        return dx * dx + dy * dy + dz * dz <= maxSquared;
    }

    // ---- Texto -------------------------------------------------------------------------------

    /** Estilo con nombre, placeholders (si hay jugador) y emojis de ItemsAdder. */
    String prepareText(String raw, Player viewer) {
        String text = HoloText.expandStyle(raw, settings.styles());
        if (viewer != null && HoloText.personal(text)) {
            text = dev.chatalyst.hook.Placeholders.apply(viewer, text);
        }
        return dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(text);
    }

    /**
     * Fotogramas del ciclo de una linea ya preparada (0 si no esta animada). Solo depende del
     * texto: quien repinta lo guarda y no lo vuelve a medir hasta que el texto cambie.
     */
    int cycleOf(Hologram hologram, HoloLine line, String prepared) {
        try {
            return HoloAnimation.cycleOf(hologram.animationOf(line), prepared);
        } catch (RuntimeException broken) {
            return 0;
        }
    }

    /**
     * Numero de fotograma en este tick para un ciclo ya medido (-1 si no esta animada). Una
     * division: con esto el repintado se salta las lineas cuyo texto y fotograma no cambiaron,
     * que es casi siempre (un fotograma dura varios ticks).
     */
    static int frameOf(int cycle, int speed, long now) {
        return cycle <= 0 ? -1 : HoloAnimation.frameAt(now, speed, cycle);
    }

    /** Fotograma de una linea ya preparada. */
    Component renderLine(Hologram hologram, HoloLine line, String prepared, long now) {
        String animation = hologram.animationOf(line);
        try {
            if ("none".equals(animation)) return MM.full(Legacy.toMini(prepared));
            HoloText.Split split = HoloText.split(prepared);
            int cycle = HoloAnimation.cycleLength(animation,
                    HoloText.visibleLength(split.message()), 4);
            int frame = HoloAnimation.frameAt(now, hologram.motion().speed(), cycle);
            return AnnounceAnimator.frame(animation, split.template(), split.message(), frame);
        } catch (RuntimeException broken) {
            return Component.text(MM.strip(prepared));
        }
    }

    // ---- Clic ----------------------------------------------------------------------------------

    /** El id del cartel de una entidad de clic nuestra, o null. */
    String clickTarget(Entity entity) {
        Tracked tracked = entities.get(entity.getUniqueId());
        if (tracked == null || !(entity instanceof org.bukkit.entity.Interaction)) return null;
        return tracked.owner().hologram.id();
    }

    void click(Player player, String id) {
        Hologram hologram = get(id);
        if (hologram == null || !hologram.action().active()) return;
        HoloAction action = hologram.action();
        String cooldownKey = player.getUniqueId() + "|" + hologram.id();
        long now = System.currentTimeMillis();
        if (!player.hasPermission("chatalyst.hologram.cooldown.bypass")) {
            Long last = cooldowns.get(cooldownKey);
            if (last != null && now - last < action.cooldown() * 1000L) return;
        }
        cooldowns.put(cooldownKey, now);
        String value = action.resolved(player.getName());
        switch (action.type()) {
            case PLAYER -> onPlayer(player, p -> p.performCommand(value));
            case CONSOLE -> FoliaTasks.global(plugin, () -> plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), value));
            case MESSAGE -> onPlayer(player, p -> p.sendMessage(
                    MM.full(dev.chatalyst.hook.Placeholders.apply(p, value))));
            case NONE -> {
            }
        }
    }

    // ---- Consultas para comandos y menus -----------------------------------------------------

    /** Carteles a menos de {@code radius} bloques, del mas cercano al mas lejano. */
    List<Hologram> near(Location at, double radius) {
        Hologram.Position from = new Hologram.Position(at.getWorld().getName(), at.getX(),
                at.getY(), at.getZ(), 0f);
        double max = radius * radius;
        return holograms.values().stream()
                .filter(h -> h.position().distanceSquared(from) <= max)
                .sorted(java.util.Comparator.comparingDouble(h -> h.position().distanceSquared(from)))
                .toList();
    }

    /** Si el cartel tiene entidades en el mundo ahora mismo. */
    boolean spawned(String id) {
        return instances.containsKey(id);
    }

    Location locationOf(Hologram hologram) {
        Location at = location(hologram);
        if (at != null) at.setYaw(hologram.position().yaw());
        return at;
    }

    /** Ids validos para autocompletar. */
    Set<String> ids() {
        return Set.copyOf(holograms.keySet());
    }
}
