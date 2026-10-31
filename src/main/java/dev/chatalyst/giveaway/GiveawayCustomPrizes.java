package dev.chatalyst.giveaway;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Premios de sorteo hechos EN EL JUEGO, sin tocar {@code giveaway.yml}.
 *
 * <p>El catalogo de {@code Giveaway_Prizes} describe objetos con Material/Name/Lore, y eso no
 * alcanza para lo que el staff quiere regalar de verdad: una espada encantada a mano, un objeto
 * de ItemsAdder, un libro firmado, una mochila con cosas dentro. Aqui cada objeto se guarda
 * ENTERO ({@link ItemStack#serializeAsBytes()}), con todos sus componentes, y vuelve identico.
 *
 * <p>Se guardan en el Store {@code giveaway_custom}: en produccion es la MySQL compartida, asi
 * que un premio creado en Survival existe tambien en Lobby. Cada premio es un JSON pequeno.
 */
public final class GiveawayCustomPrizes {

    /** Mas que esto en un solo premio ya no es un premio, es vaciar un almacen. */
    public static final int MAX_ITEMS = 54;
    public static final int MAX_COMMANDS = 20;
    public static final int MAX_WINNERS = 10;
    public static final int MAX_DESCRIPTION = 8;
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Lo que se guarda de cada premio. Los objetos van en Base64 de sus bytes de Paper. */
    public static final class Custom {
        public String id;
        public String name;
        public List<String> description = new ArrayList<>();
        public String icon;
        public List<String> items = new ArrayList<>();
        public List<String> commands = new ArrayList<>();
        public int winners = 1;
        public String createdBy;
        public long createdAt;

        Custom() {
        }

        Custom(String id, String createdBy) {
            this.id = id;
            this.name = id;
            this.createdBy = createdBy;
            this.createdAt = System.currentTimeMillis();
        }

        /** Nunca null en las listas aunque el JSON venga de una version vieja. */
        Custom sane() {
            if (description == null) description = new ArrayList<>();
            if (items == null) items = new ArrayList<>();
            if (commands == null) commands = new ArrayList<>();
            winners = Math.max(1, Math.min(MAX_WINNERS, winners));
            if (name == null || name.isBlank()) name = id;
            return this;
        }

        /**
         * Los objetos ya descodificados. Descodificar es caro (cada uno pasa por el conversor
         * de datos del juego) y esto se pregunta por cada jugador que mira la ruleta y por cada
         * tecla del autocompletado; se hace una vez por copia del premio. Gson no lo guarda.
         */
        private transient volatile List<ItemStack> decoded;
        private transient volatile Material iconCache;

        /** Solo para LEER (nombres, cantidades, icono): son las instancias compartidas. */
        List<ItemStack> viewStacks() {
            List<ItemStack> cache = decoded;
            if (cache == null) {
                List<ItemStack> out = new ArrayList<>();
                for (String b64 : items) {
                    ItemStack item = decode(b64);
                    if (item != null) out.add(item);
                }
                decoded = cache = List.copyOf(out);
            }
            return cache;
        }

        /** Copias propias de cada objeto, para entregarlas o meterlas en un inventario. */
        public List<ItemStack> itemStacks() {
            List<ItemStack> shared = viewStacks();
            List<ItemStack> out = new ArrayList<>(shared.size());
            for (ItemStack item : shared) out.add(item.clone());
            return out;
        }

        /**
         * Los objetos guardados que este servidor NO sabe leer (de un plugin que hoy no esta,
         * o de una version mas nueva), tal cual estan guardados. Quien reescribe la lista los
         * tiene que conservar: si no, abrir y cerrar el cofre los borraba para siempre.
         */
        List<String> unreadableItems() {
            List<String> out = new ArrayList<>();
            for (String b64 : items) {
                if (b64 != null && !b64.isBlank() && decode(b64) == null) out.add(b64);
            }
            return out;
        }

        public Material iconMaterial() {
            Material cached = iconCache;
            if (cached != null) return cached;
            ItemStack shown = decode(icon);
            if (shown == null) {
                List<ItemStack> stacks = viewStacks();
                shown = stacks.isEmpty() ? null : stacks.get(0);
            }
            cached = shown == null ? Material.SHULKER_BOX : shown.getType();
            iconCache = cached;
            return cached;
        }

        public boolean deliversSomething() {
            return !items.isEmpty() || !commands.isEmpty();
        }
    }

    private static volatile GiveawayCustomPrizes instance;

    private final Store store;
    private final Gson gson = new Gson();

    GiveawayCustomPrizes(StorageManager storage) {
        this.store = storage.store("giveaway_custom");
        instance = this;
    }

    /** La instancia viva, para quien no la recibe por constructor (GiveawayPrize es un record). */
    static Optional<GiveawayCustomPrizes> get() {
        return Optional.ofNullable(instance);
    }

    static void clear(GiveawayCustomPrizes which) {
        if (instance == which) instance = null;
    }

    public static boolean validId(String raw) {
        return raw != null && ID.matcher(raw).matches();
    }

    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Una copia NUEVA del premio, para quien vaya a cambiarla y guardarla. */
    public Optional<Custom> find(String rawId) {
        String id = normalize(rawId);
        if (!validId(id)) return Optional.empty();
        return parse(store.get(id));
    }

    /** El JSON de un premio y lo que salio de leerlo: mientras el JSON no cambie, vale. */
    private record Parsed(String json, Custom custom) {
    }

    private final Map<String, Parsed> parsed = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    /**
     * El premio para LEERLO (nombre, descripcion, objetos, ganadores); lo devuelto se comparte
     * y no se modifica — para cambiarlo, {@link #update}.
     *
     * <p>Leer un premio era interpretar su JSON entero (con hasta 54 objetos en Base64 dentro)
     * y descodificar sus objetos, y se hacia por cada jugador y cada segundo de la cuenta atras
     * (la barra nombra el premio), por cada jugador al abrir la ruleta y por cada tecla del
     * autocompletado. Ahora se hace una vez por cambio del premio.
     */
    public Optional<Custom> peek(String rawId) {
        String id = normalize(rawId);
        if (!validId(id)) return Optional.empty();
        return cached(id, store.get(id));
    }

    private Optional<Custom> cached(String id, String json) {
        if (json == null || json.isBlank()) {
            parsed.remove(id);
            return Optional.empty();
        }
        Parsed known = parsed.get(id);
        if (known != null && known.json().equals(json)) return Optional.of(known.custom());
        Optional<Custom> fresh = parse(json);
        if (fresh.isPresent()) parsed.put(id, new Parsed(json, fresh.get()));
        else parsed.remove(id);
        return fresh;
    }

    /** Todos, para leer (mismas instancias compartidas que {@link #peek}). */
    public List<Custom> all() {
        List<Custom> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            String id = entry.getKey();
            if (!validId(id)) continue;
            cached(id, entry.getValue()).ifPresent(custom -> {
                // El id de dentro manda sobre la clave: es el que usa el resto del sorteo.
                if (seen.add(custom.id)) out.add(custom);
            });
            seen.add(id);
        }
        parsed.keySet().retainAll(seen);
        out.sort(Comparator.comparing(c -> c.id));
        return out;
    }

    /** Resultado de {@link #update}. */
    public enum Update { MISSING, SKIPPED, SAVED, FAILED }

    /**
     * Lee el premio, le aplica el cambio y lo guarda, de una vez. Dos cambios seguidos (un
     * comando y el cierre del cofre, o dos comandos) corren en hilos distintos; sin esto los
     * dos leian la misma version y el ultimo en guardar se llevaba por delante al otro.
     *
     * @param change devuelve false para no guardar nada
     */
    public Update update(String rawId, java.util.function.Predicate<Custom> change) {
        synchronized (writeLock) {
            Custom custom = find(rawId).orElse(null);
            if (custom == null) return Update.MISSING;
            if (!change.test(custom)) return Update.SKIPPED;
            return save(custom) ? Update.SAVED : Update.FAILED;
        }
    }

    public Custom create(String rawId, String by) {
        return new Custom(normalize(rawId), by);
    }

    public boolean save(Custom custom) {
        custom.sane();
        return store.putDurably(custom.id, gson.toJson(custom));
    }

    public boolean delete(String rawId) {
        String id = normalize(rawId);
        if (!validId(id)) return false;
        synchronized (writeLock) {
            return store.removeDurably(id);
        }
    }

    private Optional<Custom> parse(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            Custom custom = gson.fromJson(json, Custom.class);
            return custom == null || !validId(custom.id) ? Optional.empty()
                    : Optional.of(custom.sane());
        } catch (JsonSyntaxException broken) {
            return Optional.empty();
        }
    }

    // ---- objetos -----------------------------------------------------------------------

    public static String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack decode(String b64) {
        if (b64 == null || b64.isBlank()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (RuntimeException corrupt) {
            // Un objeto de un plugin que ya no esta, o de una version futura: se salta ese
            // objeto, no el premio entero.
            return null;
        }
    }
}
