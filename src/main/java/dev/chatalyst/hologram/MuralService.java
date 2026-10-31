package dev.chatalyst.hologram;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Murales de mapas: imagen troceada en marcos invisibles y fijos (no se rompen, no se giran,
 * no se les saca el mapa), con mapas que pinta {@link MuralRenderer}.
 *
 * <p>Mismas reglas que los carteles flotantes:
 * <ul>
 *   <li>Los marcos no se guardan con el mundo ({@code setPersistent(false)}) y llevan una
 *       marca con la sesion: se crean al cargar su chunk y lo que quede de un reload o un crash
 *       se reconoce como huerfano y se borra. Un mural puede ocupar varios chunks: cada marco
 *       vive y muere con el suyo.</li>
 *   <li>Los ids de mapa se guardan y se reutilizan: un reinicio no crea mapas nuevos.</li>
 *   <li>Un GIF solo se anima con jugadores cerca, y al cambiar de fotograma solo se reenvian
 *       los mapas que cambian, y solo a esos jugadores. Un mural fijo no cuesta nada por tick:
 *       el juego manda el mapa cuando alguien se acerca.</li>
 * </ul>
 */
final class MuralService {

    /** Un mural vivo: sus mapas, sus marcos cargados y su imagen (cuando termina de cargar). */
    static final class Live {
        final Mural mural;
        final MapView[] views;
        final ItemFrame[] frames;
        volatile ImageLibrary.MuralArt art;
        volatile int frame;
        volatile String failure;
        final long start = System.currentTimeMillis();
        List<Player> viewers = List.of();
        long lastPresence = -1_000_000L;

        Live(Mural mural) {
            this.mural = mural;
            this.views = new MapView[mural.width() * mural.height()];
            this.frames = new ItemFrame[views.length];
        }
    }

    private record TileRef(String id, int tile) {
    }

    private final ChatalystPlugin plugin;
    private final ImageLibrary images;
    private final NamespacedKey key;
    private final String session = Long.toString(System.nanoTime() ^ 0x5DEECE66DL, 36);
    private final File file;
    private final Object writeLock = new Object();
    private final AtomicReference<List<Mural>> pendingWrite = new AtomicReference<>();
    private final Map<String, Mural> murals = new ConcurrentSkipListMap<>();
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    /** Marcos vivos: lo que lleve la marca y no este aqui es un huerfano. */
    private final Map<UUID, String> entities = new ConcurrentHashMap<>();
    private volatile Map<String, List<TileRef>> byChunk = Map.of();
    private final AtomicLong tick = new AtomicLong();
    private final boolean folia = FoliaTasks.isFolia();
    private ScheduledTask ticker;

    MuralService(ChatalystPlugin plugin, ImageLibrary images) {
        this.plugin = plugin;
        this.images = images;
        this.key = new NamespacedKey(plugin, "mural");
        this.file = new File(new File(plugin.getDataFolder(), "data"), "murals.yml");
    }

    // ---- Ciclo de vida -------------------------------------------------------------------

    void start() {
        for (Mural mural : load()) murals.put(mural.id(), mural);
        reindex();
        removeOrphansInLoadedWorlds();
        for (Mural mural : murals.values()) activate(mural);
        ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> tick(), 1, 1);
        if (!murals.isEmpty()) plugin.getLogger().info("Loaded " + murals.size() + " mural(s).");
    }

    void stop() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        if (!folia) {
            for (Live current : live.values()) {
                for (ItemFrame frame : current.frames) {
                    if (frame != null) frame.remove();
                }
                detachRenderers(current);
            }
        }
        live.clear();
        entities.clear();
        write(List.copyOf(murals.values()));
    }

    int reloadFromDisk() {
        for (String id : List.copyOf(live.keySet())) deactivate(id);
        murals.clear();
        for (Mural mural : load()) murals.put(mural.id(), mural);
        reindex();
        for (Mural mural : murals.values()) activate(mural);
        return murals.size();
    }

    // ---- Definiciones ----------------------------------------------------------------------

    Mural get(String id) {
        return id == null ? null : murals.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    Collection<Mural> all() {
        return List.copyOf(murals.values());
    }

    java.util.Set<String> ids() {
        return java.util.Set.copyOf(murals.keySet());
    }

    /** Alta; false si el id ya existe. {@code onReady} recibe null o el motivo del fallo. */
    boolean create(Mural mural, Consumer<String> onReady) {
        if (murals.putIfAbsent(mural.id(), mural) != null) return false;
        reindex();
        Live current = activate(mural);
        persist();
        whenReady(current, onReady);
        return true;
    }

    boolean delete(String id) {
        Mural removed = murals.remove(id);
        if (removed == null) return false;
        reindex();
        deactivate(removed.id());
        persist();
        return true;
    }

    /** Lo mueve (otro sitio u orientacion) conservando la imagen y sus mapas. */
    boolean move(Mural moved, Consumer<String> onReady) {
        if (!murals.containsKey(moved.id())) return false;
        deactivate(moved.id());
        murals.put(moved.id(), moved);
        reindex();
        Live current = activate(moved);
        persist();
        whenReady(current, onReady);
        return true;
    }

    String failure(String id) {
        Live current = live.get(id);
        return current == null ? null : current.failure;
    }

    boolean ready(String id) {
        Live current = live.get(id);
        return current != null && current.art != null;
    }

    private void whenReady(Live current, Consumer<String> onReady) {
        if (onReady == null || current == null) return;
        images.mural(current.mural.source(), current.mural.size(), current.mural.fit(),
                        current.mural.dither())
                .whenComplete((art, failure) -> FoliaTasks.global(plugin, () -> onReady.accept(
                        failure == null ? null : ImageLibrary.reason(failure))));
    }

    // ---- Activacion: mapas, imagen y marcos ------------------------------------------------

    /** En el hilo principal (crea o reutiliza MapView). */
    private Live activate(Mural mural) {
        World world = plugin.getServer().getWorld(mural.world());
        if (world == null) return null;
        Live current = new Live(mural);
        List<Integer> ids = new ArrayList<>(current.views.length);
        boolean changedIds = mural.mapIds().size() != current.views.length;
        for (int tile = 0; tile < current.views.length; tile++) {
            MapView view = null;
            if (tile < mural.mapIds().size()) view = Bukkit.getMap(mural.mapIds().get(tile));
            if (view == null) {
                view = Bukkit.createMap(world);
                changedIds = true;
            }
            for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
                view.removeRenderer(renderer);
            }
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.setLocked(true);
            view.addRenderer(new MuralRenderer(current, tile));
            current.views[tile] = view;
            ids.add(view.getId());
        }
        if (changedIds) {
            Mural updated = mural.withMapIds(ids);
            murals.computeIfPresent(mural.id(), (k, v) -> updated);
            persist();
        }
        live.put(mural.id(), current);
        images.mural(mural.source(), mural.size(), mural.fit(), mural.dither())
                .whenComplete((art, failure) -> {
                    if (failure != null) {
                        current.failure = ImageLibrary.reason(failure);
                        plugin.getLogger().warning("Mural " + mural.id() + ": " + current.failure);
                        return;
                    }
                    current.art = art;
                    // Quien ya estuviera mirando ve la imagen sin esperar al siguiente envio.
                    FoliaTasks.global(plugin, () -> resend(current, null));
                });
        spawnLoadedTiles(current, world);
        return current;
    }

    private void deactivate(String id) {
        Live current = live.remove(id);
        if (current == null) return;
        for (int tile = 0; tile < current.frames.length; tile++) {
            ItemFrame frame = current.frames[tile];
            current.frames[tile] = null;
            if (frame == null) continue;
            entities.remove(frame.getUniqueId());
            if (folia) FoliaTasks.region(plugin, frame.getLocation(), frame::remove);
            else frame.remove();
        }
        detachRenderers(current);
    }

    private static void detachRenderers(Live current) {
        for (MapView view : current.views) {
            if (view == null) continue;
            for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
                if (renderer instanceof MuralRenderer) view.removeRenderer(renderer);
            }
        }
    }

    private void spawnLoadedTiles(Live current, World world) {
        List<int[]> blocks = current.mural.blocks();
        for (int tile = 0; tile < blocks.size(); tile++) {
            int[] b = blocks.get(tile);
            if (!world.isChunkLoaded(b[0] >> 4, b[2] >> 4)) continue;
            int index = tile;
            Location at = new Location(world, b[0], b[1], b[2]);
            if (folia) FoliaTasks.region(plugin, at, () -> spawnTile(current, index));
            else spawnTile(current, index);
        }
    }

    /** Un marco con su mapa. En el hilo de la region, con el chunk cargado. */
    private void spawnTile(Live current, int tile) {
        if (live.get(current.mural.id()) != current) return;
        ItemFrame existing = current.frames[tile];
        if (existing != null && existing.isValid()) return;
        World world = plugin.getServer().getWorld(current.mural.world());
        if (world == null) return;
        int[] b = current.mural.blocks().get(tile);
        Location at = new Location(world, b[0], b[1], b[2]);
        BlockFace face = BlockFace.valueOf(current.mural.face().name());
        Class<? extends ItemFrame> type = current.mural.glow() ? GlowItemFrame.class : ItemFrame.class;
        try {
            ItemFrame frame = world.spawn(at, type, spawned -> {
                spawned.setFacingDirection(face, true);
                spawned.setPersistent(false);
                spawned.setFixed(true);
                spawned.setVisible(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                map.editMeta(MapMeta.class, meta -> meta.setMapView(current.views[tile]));
                spawned.setItem(map, false);
                spawned.setRotation(Rotation.values()[Math.floorMod(b[3], 4)]);
                spawned.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                        session + "|" + current.mural.id() + "|" + tile);
            });
            current.frames[tile] = frame;
            entities.put(frame.getUniqueId(), current.mural.id());
        } catch (RuntimeException failure) {
            // Sin apoyo detras (alguien rompio la pared) u ocupado por otro marco.
            plugin.getLogger().warning("Mural " + current.mural.id() + ": could not place map "
                    + (tile + 1) + " at " + b[0] + " " + b[1] + " " + b[2] + ": "
                    + failure.getMessage());
        }
    }

    // ---- Chunks y huerfanos ----------------------------------------------------------------

    private static String chunkKey(String world, int x, int z) {
        return world + '|' + x + '|' + z;
    }

    private void reindex() {
        Map<String, List<TileRef>> next = new HashMap<>();
        for (Mural mural : murals.values()) {
            List<int[]> blocks = mural.blocks();
            for (int tile = 0; tile < blocks.size(); tile++) {
                int[] b = blocks.get(tile);
                next.computeIfAbsent(chunkKey(mural.world(), b[0] >> 4, b[2] >> 4),
                        k -> new ArrayList<>()).add(new TileRef(mural.id(), tile));
            }
        }
        next.replaceAll((k, v) -> List.copyOf(v));
        byChunk = Map.copyOf(next);
    }

    void onChunkLoad(Chunk chunk) {
        List<TileRef> here = byChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(),
                chunk.getZ()));
        if (here == null) return;
        // Un tick despues, con el chunk ya listo (como los carteles).
        Location at = new Location(chunk.getWorld(), chunk.getX() << 4, 64, chunk.getZ() << 4);
        FoliaTasks.region(plugin, at, () -> {
            if (!chunk.isLoaded()) return;
            for (TileRef ref : here) {
                Live current = live.get(ref.id());
                if (current != null) spawnTile(current, ref.tile());
            }
        });
    }

    void onChunkUnload(Chunk chunk) {
        List<TileRef> here = byChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(),
                chunk.getZ()));
        if (here == null) return;
        for (TileRef ref : here) {
            Live current = live.get(ref.id());
            if (current == null) continue;
            ItemFrame frame = current.frames[ref.tile()];
            current.frames[ref.tile()] = null;
            if (frame != null) {
                entities.remove(frame.getUniqueId());
                frame.remove();
            }
        }
    }

    boolean isOurs(Entity entity) {
        return entity instanceof ItemFrame
                && entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    boolean isOrphan(Entity entity) {
        return isOurs(entity) && !entities.containsKey(entity.getUniqueId());
    }

    private void removeOrphansInLoadedWorlds() {
        if (folia) return; // en Folia se barren al cargar cada chunk (EntitiesLoadEvent)
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
                if (isOrphan(frame)) {
                    frame.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) plugin.getLogger().info("Removed " + removed + " leftover mural frames.");
    }

    // ---- Animacion ---------------------------------------------------------------------------

    private void tick() {
        long now = tick.incrementAndGet();
        ImageSettings settings = images.settings();
        for (Live current : live.values()) {
            ImageLibrary.MuralArt art = current.art;
            if (art == null || !art.animated()) continue;
            if (now - current.lastPresence >= 20) {
                current.lastPresence = now;
                current.viewers = viewers(current, settings.muralRange());
            }
            if (current.viewers.isEmpty()) continue;
            int frame = GifTiming.frameAt(System.currentTimeMillis() - current.start,
                    art.delays(), settings.muralMaxFps());
            if (frame == current.frame) continue;
            current.frame = frame;
            resend(current, art.changed()[frame]);
        }
    }

    /** Jugadores del mismo mundo a menos de {@code range} bloques del ancla del mural. */
    private List<Player> viewers(Live current, int range) {
        World world = plugin.getServer().getWorld(current.mural.world());
        if (world == null) return List.of();
        double max = (double) range * range;
        List<Player> out = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            double dx = at.getX() - current.mural.x();
            double dy = at.getY() - current.mural.y();
            double dz = at.getZ() - current.mural.z();
            if (dx * dx + dy * dy + dz * dz <= max) out.add(player);
        }
        return out;
    }

    /** Reenvia los mapas marcados (todos si {@code tiles} es null) a quien esta cerca. */
    private void resend(Live current, boolean[] tiles) {
        List<Player> targets = current.viewers.isEmpty()
                ? viewers(current, images.settings().muralRange()) : current.viewers;
        if (targets.isEmpty()) return;
        for (int tile = 0; tile < current.views.length; tile++) {
            if (tiles != null && (tile >= tiles.length || !tiles[tile])) continue;
            MapView view = current.views[tile];
            if (view == null) continue;
            for (Player player : targets) {
                if (folia) FoliaTasks.entity(plugin, player, p -> p.sendMap(view));
                else if (player.isOnline()) player.sendMap(view);
            }
        }
    }

    // ---- Guardado ------------------------------------------------------------------------------

    private List<Mural> load() {
        List<Mural> out = new ArrayList<>();
        if (!file.exists()) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("murals");
        if (root == null) return out;
        int maxSide = images.settings().muralMaxSide();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            Mural mural = section == null ? null : Mural.decode(id, plain(section), maxSide);
            if (mural == null) {
                plugin.getLogger().warning("Skipped an unreadable mural in data/murals.yml: " + id);
                continue;
            }
            out.add(mural);
        }
        return out;
    }

    private void persist() {
        pendingWrite.set(List.copyOf(murals.values()));
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                synchronized (writeLock) {
                    List<Mural> latest = pendingWrite.getAndSet(null);
                    if (latest != null) write(latest);
                }
            });
        } catch (RuntimeException shuttingDown) {
            write(List.copyOf(murals.values()));
        }
    }

    private void write(List<Mural> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Murales de mapas de este servidor (Chatalyst). Se crean en el juego con",
                "/cartel mural; si tocas esto a mano, usa /cartel recargar despues.",
                "\"maps\" son los ids de mapa que se reutilizan: no los cambies."));
        for (Mural mural : snapshot) yaml.createSection("murals." + mural.id(), mural.encode());
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                File temp = new File(file.getParentFile(), "murals.yml.tmp");
                yaml.save(temp);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                plugin.getLogger().warning("Could not save data/murals.yml: " + failure.getMessage());
            }
        }
    }

    private static Map<String, Object> plain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : section.getKeys(false)) {
            Object value = section.get(k);
            out.put(k, value instanceof ConfigurationSection nested ? plain(nested) : value);
        }
        return out;
    }

    // ---- Consultas ------------------------------------------------------------------------------

    Location locationOf(Mural mural) {
        World world = plugin.getServer().getWorld(mural.world());
        return world == null ? null : new Location(world, mural.x() + 0.5, mural.y(), mural.z() + 0.5);
    }
}
