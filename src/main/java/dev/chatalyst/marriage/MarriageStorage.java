package dev.chatalyst.marriage;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Almacen compartido del matrimonio (/marry storage): un cofre al que los dos conyuges
 * tienen acceso. Antes se llamaba "banco" y se abria con /marry bank; ese nombre es
 * ahora de la cuenta de dinero ({@link MarriageBank}).
 *
 * <p>El namespace del store sigue siendo {@code marriage_bank} A PROPOSITO: renombrarlo
 * dejaria huerfanos los cofres que ya existen, y aqui lo unico que no se puede perder son
 * los items de la gente.
 *
 * <p><b>Lo critico es no duplicar items.</b> Por eso hay UNA sola instancia de
 * {@link Inventory} por pareja en memoria. En Folia esa instancia no se puede modificar a la
 * vez desde dos regiones, por lo que solo un conyuge la abre a la vez; el otro recibe un aviso.
 * Crear dos copias tampoco seria seguro: al cerrarlas la ultima escritura clonaria/perderia
 * items de la otra.
 *
 * <p>Se persiste cuando se va el ULTIMO espectador (y en el apagado del servidor), momento
 * en el que la instancia sale de memoria para no acumular inventarios abiertos.
 *
 * <p>El store es {@code perServerStore}: con la base compartida en red cada backend tiene
 * sus cofres. Compartido, dos conyuges en servidores DISTINTOS abririan instancias distintas
 * (gana el ultimo cierre) y un servidor sin los plugins de items no podria leerlos.
 */
public final class MarriageStorage implements Listener {

    private static final int SLOTS_PER_ROW = 9;

    /** Marca el inventario como almacen; sin MenuHolder los clicks fluyen y se pueden mover items. */
    private static final class StorageHolder implements InventoryHolder {
        private final String key;
        private Inventory inventory;

        private StorageHolder(String key) {
            this.key = key;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final MarriageManager marriages;
    private final Store store;
    /** Instancia viva por pareja: la clave del matrimonio -> el cofre que ambos abren. */
    private final Map<String, Inventory> open = new ConcurrentHashMap<>();
    /** Items guardados que NO caben en las filas actuales; se conservan al persistir. */
    private final Map<String, java.util.List<ItemStack>> overflow = new ConcurrentHashMap<>();
    /**
     * Aperturas EN VUELO por pareja. openInventory se agenda para el tick siguiente (el
     * EntityScheduler nunca corre en linea), asi que entre reservar la instancia y abrirla de
     * verdad hay un hueco. Si el conyuge cerraba justo en ese hueco, la instancia salia del
     * mapa y la siguiente apertura creaba un SEGUNDO cofre sobre la misma fila: dos cofres
     * vivos, y al cerrarlos gana el ultimo (items duplicados o perdidos).
     */
    private final Map<String, Integer> opening = new ConcurrentHashMap<>();
    /** Espectador que posee temporalmente el almacen; serializa regiones distintas en Folia. */
    private final Map<String, UUID> viewing = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile int rows = 3;

    public MarriageStorage(JavaPlugin plugin, MarriageManager marriages, StorageManager storage) {
        this(plugin, marriages, storage.perServerStore("marriage_bank"));
    }

    MarriageStorage(JavaPlugin plugin, MarriageManager marriages, Store store) {
        this.plugin = plugin;
        this.marriages = marriages;
        this.store = store;
    }

    public void load() {
        var cfg = plugin.getConfig();
        boolean nextEnabled = cfg.getBoolean("Marriage.Perks.Storage.Enabled", true);
        enabled = nextEnabled;
        rows = Math.max(1, Math.min(6, configuredRows(cfg)));
        if (!nextEnabled) closeViewers();
    }

    /**
     * Filas del cofre. Una config anterior al renombrado solo trae {@code Perks.Bank.Rows}:
     * si el dueño no ha escrito aun {@code Perks.Storage.Rows}, manda la suya antigua y no
     * el default del jar, o un cofre de 6 filas se abriria con 3.
     */
    static int configuredRows(org.bukkit.configuration.ConfigurationSection cfg) {
        if (cfg.contains("Marriage.Perks.Storage.Rows", true)) {
            return cfg.getInt("Marriage.Perks.Storage.Rows", 3);
        }
        if (cfg.contains("Marriage.Perks.Bank.Rows", true)) {
            return cfg.getInt("Marriage.Perks.Bank.Rows", 3);
        }
        return cfg.getInt("Marriage.Perks.Storage.Rows", 3);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Clave estable de la pareja: los dos UUID ORDENADOS. Da igual quien pregunte, los dos
     * llegan a la misma fila del store y a la misma instancia en memoria.
     */
    static String key(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    private String keyFor(UUID id) {
        MarriageManager.Marriage marriage = marriages.marriage(id);
        return marriage == null ? null : key(id, marriage.partner());
    }

    /** True si la pareja aun guarda algo (bloquea el divorcio para no perder items). */
    public boolean hasItems(UUID id) {
        // Con el almacen apagado NO se bloquea el divorcio: nadie podria abrirlo para vaciarlo
        // y la pareja quedaria casada para siempre. La fila se conserva igual (clear() no
        // borra un almacen con contenido), asi que los items siguen ahi si se vuelve a activar.
        if (!enabled) return false;
        String key = keyFor(id);
        if (key == null) return false;
        java.util.List<ItemStack> pending = overflow.get(key);
        if (pending != null && !pending.isEmpty()) return true;
        Inventory live = open.get(key);
        // El inventario puede pertenecer a la region Folia del conyuge que lo esta mirando.
        // No se inspecciona desde la region de quien pide divorcio: mientras este vivo se
        // falla cerrado y se exige cerrarlo, incluso si visualmente parece vacio.
        if (live != null) return true;
        String raw = store.get(key);
        return raw != null && !raw.isEmpty();
    }

    public void open(Player player) {
        if (!enabled) {
            player.sendMessage(Lang.get(player, "marriage.storage-disabled"));
            return;
        }
        String key = keyFor(player.getUniqueId());
        if (key == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID previous = viewing.putIfAbsent(key, playerId);
        if (previous != null) {
            player.sendMessage(Lang.get(player, "marriage.storage-in-use"));
            return;
        }
        // computeIfAbsent: si el conyuge ya lo tiene abierto se reutiliza SU instancia; si no,
        // se carga del store. Nunca se crean dos cofres para la misma pareja.
        opening.merge(key, 1, Integer::sum);
        Inventory inventory;
        try {
            inventory = open.computeIfAbsent(key, this::create);
        } catch (RuntimeException failure) {
            released(key);
            viewing.remove(key, playerId);
            plugin.getLogger().severe("Could not open marriage storage " + key + ": "
                    + failure.getMessage());
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        FoliaTasks.entity(plugin, player, p -> {
            try {
                if (!enabled) {
                    viewing.remove(key, playerId);
                    return;
                }
                p.openInventory(inventory);
            } finally {
                released(key);
            }
        }, () -> {
            released(key);
            viewing.remove(key, playerId);
        }); // el jugador se fue antes de abrir: se suelta igual
    }

    private void closeViewers() {
        for (Map.Entry<String, UUID> entry : java.util.List.copyOf(viewing.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getValue());
            if (player == null) {
                viewing.remove(entry.getKey(), entry.getValue());
                continue;
            }
            String key = entry.getKey();
            FoliaTasks.entity(plugin, player, current -> {
                Inventory top = current.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof StorageHolder holder && holder.key.equals(key)) {
                    current.closeInventory(); // onClose persiste antes de soltar el lock
                } else {
                    viewing.remove(key, current.getUniqueId());
                }
            }, () -> viewing.remove(key, entry.getValue()));
        }
    }

    private void released(String key) {
        opening.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
    }

    private Inventory create(String key) {
        StorageHolder holder = new StorageHolder(key);
        Inventory inventory = Bukkit.createInventory(holder, rows * SLOTS_PER_ROW,
                // Titulo en el idioma por defecto: la instancia es UNA para los dos, asi que
                // no puede seguir el idioma de cada espectador.
                MM.full(Lang.raw("marriage.storage-title")));
        holder.inventory = inventory;
        java.util.List<ItemStack> pending = new java.util.ArrayList<>();
        String stored = store.get(key);
        java.util.List<ItemStack> readable = readAll(stored, ItemStack::deserializeBytes);
        for (ItemStack item : readable) {
            // addItem devuelve lo que NO cupo (pasa si el dueño reduce Rows). Se guarda
            // aparte y se vuelve a escribir al persistir: si se ignorara, el primer guardado
            // reescribiria la fila con solo lo que cabe y esos items desaparecerian.
            pending.addAll(inventory.addItem(item).values());
        }
        if (pending.isEmpty()) {
            overflow.remove(key);
        } else {
            overflow.put(key, pending);
            plugin.getLogger().warning("Marriage storage " + key + " holds " + pending.size()
                    + " item stack(s) that no longer fit in " + rows + " row(s). They are kept"
                    + " safe and will reappear if Marriage.Perks.Storage.Rows is raised again.");
        }
        return inventory;
    }

    // Al cerrar el ULTIMO espectador se guarda y se suelta la instancia. Con el otro conyuge
    // aun mirando NO se guarda: el cofre sigue vivo y se guardara cuando el se vaya.
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof StorageHolder holder)) return;
        boolean saved = persist(holder.key, inventory);
        viewing.remove(holder.key, event.getPlayer().getUniqueId());
        // La instancia solo se suelta si el guardado SALIO BIEN. Si el backend fallo, esta
        // copia en memoria es la unica que tiene los items de verdad: soltarla los perderia
        // y la siguiente apertura leeria la fila vieja del store.
        // Con una apertura en vuelo tampoco se suelta: quien esta a punto de abrirla debe
        // encontrarse ESTA, no una copia nueva.
        if (saved && !opening.containsKey(holder.key)) {
            open.remove(holder.key, inventory);
        }
    }

    private boolean persist(String key, Inventory inventory) {
        boolean saved;
        synchronized (inventory) {
            String encoded = encode(inventory.getContents(), overflow.get(key));
            saved = encoded.isEmpty() ? store.removeDurably(key)
                    : store.putDurably(key, encoded);
        }
        if (!saved) {
            plugin.getLogger().warning("Could not persist the marriage storage " + key
                    + "; it stays open in memory and will be retried on the next close"
                    + " and at shutdown.");
        }
        return saved;
    }

    /** Guarda todos los almacenes abiertos (apagado del servidor). */
    public void shutdown() {
        int failed = 0;
        for (Map.Entry<String, Inventory> entry : open.entrySet()) {
            if (!persist(entry.getKey(), entry.getValue())) failed++;
        }
        // Aqui ya no hay reintento posible: se avisa a nivel SEVERE en vez de dejarlo como un
        // warning mas, porque significa items perdidos de verdad y el dueño debe enterarse.
        if (failed > 0) {
            plugin.getLogger().severe(failed + " marriage storage(s) could NOT be saved during"
                    + " shutdown; their contents are lost. Check the storage backend.");
        }
        open.clear();
        overflow.clear();
        opening.clear();
        viewing.clear();
        store.flushNow();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Map.Entry<String, UUID> entry : java.util.List.copyOf(viewing.entrySet())) {
            if (!entry.getValue().equals(id)) continue;
            Inventory inventory = open.get(entry.getKey());
            boolean saved = inventory == null || persist(entry.getKey(), inventory);
            viewing.remove(entry.getKey(), id);
            if (saved && inventory != null && !opening.containsKey(entry.getKey())) {
                open.remove(entry.getKey(), inventory);
            }
        }
    }

    /**
     * Borra el almacen de una pareja al divorciarse. Se RE-comprueba que siga vacio: entre el
     * chequeo del comando y esta llamada, el conyuge (que esta conectado) pudo meter algo.
     * Ante la duda se conserva la fila: un almacen huerfano lo arregla un admin, unos items
     * borrados no los recupera nadie.
     */
    void clear(UUID a, UUID b) {
        String key = key(a, b);
        Inventory live = open.get(key);
        // Igual que hasItems(): nunca leer un Inventory propiedad de otra region.
        if (live != null) return;
        java.util.List<ItemStack> pending = overflow.get(key);
        if (pending != null && !pending.isEmpty()) return;
        // Con el almacen APAGADO (o simplemente sin instancia viva) los items solo estan en el
        // store: se re-comprueba la fila igual que hasItems() antes de borrarla, o el divorcio
        // se llevaria por delante un almacen que el gate creia proteger. Un banco huerfano lo
        // arregla un admin; unos items borrados no los recupera nadie.
        String raw = store.get(key);
        if (raw != null && !raw.isEmpty()) return;
        open.remove(key);
        overflow.remove(key);
        store.removeDurably(key);
    }

    static String encode(ItemStack[] contents) {
        return encode(contents, null);
    }

    /** {@code extra} son los items que no cabian: se re-escriben para no perderlos. */
    static String encode(ItemStack[] contents, java.util.List<ItemStack> extra) {
        StringBuilder out = new StringBuilder();
        for (ItemStack item : contents) {
            append(out, item);
        }
        if (extra != null) {
            for (ItemStack item : extra) {
                append(out, item);
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        if (out.length() > 0) out.append(';');
        out.append(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
    }

    /** Cuantos stacks hay guardados, se puedan leer o no. */
    static int countStored(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        int count = 0;
        for (String b64 : raw.split(";")) {
            if (!b64.isEmpty()) count++;
        }
        return count;
    }

    static java.util.List<ItemStack> decode(String raw) {
        return decode(raw, ItemStack::deserializeBytes);
    }

    /**
     * Todos los stacks guardados o una excepcion. Si alguno no se puede leer hoy, el almacen
     * NO se abre: al cerrarlo se reescribe la fila con lo que hay en el cofre, y ese objeto se
     * perderia para siempre sin una linea en el log. Se queda tal cual hasta poder leerlo.
     */
    static <T> java.util.List<T> readAll(String stored,
                                         java.util.function.Function<byte[], T> reader) {
        java.util.List<T> readable = decode(stored, reader);
        if (readable.size() != countStored(stored)) {
            throw new IllegalStateException("it holds an item this server cannot read right"
                    + " now; the storage stays closed and untouched until it can");
        }
        return readable;
    }

    /**
     * Solo los stacks legibles; {@link #readAll} compara con {@link #countStored} y no abre
     * el cofre si falta alguno. Un {@code null} cuenta como ilegible: Paper lo devuelve, en vez
     * de lanzar, con items de un plugin (EcoEnchants) que este servidor no tiene.
     */
    static <T> java.util.List<T> decode(String raw,
                                        java.util.function.Function<byte[], T> reader) {
        java.util.List<T> out = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String b64 : raw.split(";")) {
            try {
                T item = reader.apply(Base64.getDecoder().decode(b64));
                if (item != null) out.add(item);
            } catch (Exception ignored) {
                // stack ilegible (salto de version de MC): create() deja el cofre cerrado
            }
        }
        return out;
    }
}
