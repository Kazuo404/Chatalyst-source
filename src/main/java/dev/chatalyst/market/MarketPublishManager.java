package dev.chatalyst.market;

import dev.chatalyst.db.Store;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

/**
 * Estado de la GUI de publicacion tipo cofre: cada jugador tiene un borrador con los items
 * que ha metido (cada uno con su precio) y la configuracion comun (venta/subasta, duracion,
 * publicacion programada y descripcion), mas un flag de "escribiendo por chat" (precio de un
 * item o el texto de la descripcion). Todo transitorio: se limpia al publicar o al salir.
 */
public final class MarketPublishManager {

    private static final String DRAFT_V2 = "v2|";
    private static final long MAX_PERSISTED_MINUTES = 30L * 24L * 60L;

    /** Un item del cofre con su precio y su descripcion PROPIA (ambos se editan en la GUI
     *  por-item). La descripcion vacia = se publica el item tal cual, sin linea extra. */
    public static final class PricedItem {
        private final ItemStack item;
        public double price;
        public String description = "";

        public PricedItem(ItemStack item, double price) {
            this.item = item;
            this.price = price;
        }

        public PricedItem(ItemStack item, double price, String description) {
            this(item, price);
            this.description = description == null ? "" : description;
        }

        public ItemStack item() {
            return item;
        }

        public double price() {
            return price;
        }

        public String description() {
            return description == null ? "" : description;
        }
    }

    /** Que esta escribiendo el jugador por chat. */
    public enum Composing { PRICE, DESCRIPTION }

    /** Borrador de publicacion (el cofre + la config del LOTE: venta/subasta, duracion, delay).
     *  El precio y la descripcion son de cada item, no del lote (se editan en el menu por-item). */
    public static final class Draft {
        public boolean auction;             // false = venta, true = subasta (para todo el lote)
        public long durationMinutes;        // 0 = duracion por defecto de config
        public long publishDelayMinutes;    // 0 = publicar ya; >0 = programado
        public final List<PricedItem> items = new ArrayList<>();
        // Item cuyo precio O descripcion se esta escribiendo por chat (indice), -1 = ninguno.
        public int editingItem = -1;
        public Composing composing;
        // El proximo cierre NO debe devolver el cofre (vista previa: se reabre despues).
        public boolean keepItems;
    }

    /** Duraciones ofrecidas por la GUI (minutos). El manager las capa a Auction.Max. */
    public static final long[] DURATION_MINUTES = {60, 360, 720, 1440, 4320, 10080};
    /** Retrasos de publicacion ofrecidos por la GUI (minutos; 0 = ahora). */
    public static final long[] DELAY_MINUTES = {0, 15, 30, 60, 180, 360};
    /** Maximo de items en el cofre (las 3 filas superiores de la GUI). */
    public static final int MAX_ITEMS = 27;

    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    /** Quien esta escribiendo por chat y desde cuando (ver {@link #isComposing(UUID, long)}). */
    private final Map<UUID, Long> composing = new ConcurrentHashMap<>();
    static final long COMPOSE_TIMEOUT_MILLIS = 120_000L;
    private final Store store;

    public MarketPublishManager(Store store) {
        this.store = store;
    }

    public Draft draft(UUID id) {
        return drafts.computeIfAbsent(id, u -> {
            PersistedDraft persisted = decodeDraft(store.get(u.toString()));
            Draft draft = new Draft();
            draft.auction = persisted.auction();
            draft.durationMinutes = persisted.durationMinutes();
            draft.publishDelayMinutes = persisted.publishDelayMinutes();
            draft.items.addAll(persisted.items());
            return draft;
        });
    }

    /** El borrador actual sin crear uno nuevo (null si no hay). Para el gancho de desconexion. */
    public Draft peek(UUID id) {
        return drafts.get(id);
    }

    public void startComposing(UUID id) {
        composing.put(id, System.currentTimeMillis());
    }

    public boolean isComposing(UUID id) {
        return isComposing(id, System.currentTimeMillis());
    }

    /**
     * La captura de chat caduca. Sin plazo, quien pulsaba "precio exacto" y se iba a otra
     * cosa tenia su SIGUIENTE mensaje de chat — aunque fuera horas despues — tragado y
     * convertido en el precio de un item, sin que nadie mas lo leyera.
     */
    boolean isComposing(UUID id, long now) {
        Long since = composing.get(id);
        if (since == null) return false;
        if (now - since <= COMPOSE_TIMEOUT_MILLIS) return true;
        composing.remove(id, since);
        return false;
    }

    public void endComposing(UUID id) {
        composing.remove(id);
    }

    public boolean addDurably(UUID id, PricedItem item) {
        boolean stored = dev.chatalyst.db.StoreCas.updateDurably(store, id.toString(), raw -> {
            PersistedDraft current = decodeDraft(raw);
            if (current.damaged()) return null;
            if (current.items().size() >= MAX_ITEMS) return null;
            List<PricedItem> items = new ArrayList<>(current.items());
            items.add(item);
            return encodeDraft(current.withItems(items));
        });
        Draft cached = drafts.get(id);
        if (stored && cached != null) {
            synchronized (cached) {
                cached.items.add(item);
            }
        }
        return stored;
    }

    public PricedItem removeDurably(UUID id, int index) {
        PricedItem[] removed = new PricedItem[1];
        boolean stored = dev.chatalyst.db.StoreCas.updateDurably(store, id.toString(), raw -> {
            PersistedDraft current = decodeDraft(raw);
            if (current.damaged()) return null;
            List<PricedItem> items = new ArrayList<>(current.items());
            if (index < 0 || index >= items.size()) return null;
            removed[0] = items.remove(index);
            return encodeDraft(current.withItems(items));
        });
        if (!stored) return null;
        Draft draft = drafts.get(id);
        if (draft != null) {
            synchronized (draft) {
                if (index >= 0 && index < draft.items.size()) draft.items.remove(index);
            }
        }
        return removed[0];
    }

    /** Persiste precio y descripcion juntos: la GUI nunca confirma un cambio solo en memoria. */
    public boolean updateItemDurably(UUID id, int index, double price, String description) {
        if (id == null || index < 0 || !Double.isFinite(price) || price < 0) return false;
        String safeDescription = dev.chatalyst.util.Sanitize.limit(
                dev.chatalyst.util.Sanitize.stripInvisible(
                        description == null ? "" : description),
                MarketManager.MAX_DESCRIPTION_LENGTH);
        boolean stored = dev.chatalyst.db.StoreCas.updateDurably(store, id.toString(), raw -> {
            PersistedDraft current = decodeDraft(raw);
            if (current.damaged()) return null;
            List<PricedItem> items = new ArrayList<>(current.items());
            if (index >= items.size()) return null;
            PricedItem previous = items.get(index);
            items.set(index, new PricedItem(previous.item(), price, safeDescription));
            return encodeDraft(current.withItems(items));
        });
        if (!stored) return false;
        Draft cached = drafts.get(id);
        if (cached != null) {
            synchronized (cached) {
                if (index < cached.items.size()) {
                    PricedItem entry = cached.items.get(index);
                    entry.price = price;
                    entry.description = safeDescription;
                }
            }
        }
        return true;
    }

    /** Persiste los controles del lote (tipo, duracion y programacion) con el journal. */
    public boolean updateSettingsDurably(UUID id, boolean auction, long durationMinutes,
                                         long publishDelayMinutes) {
        if (id == null) return false;
        long duration = boundedMinutes(durationMinutes);
        long delay = boundedMinutes(publishDelayMinutes);
        boolean stored = dev.chatalyst.db.StoreCas.updateDurably(store, id.toString(), raw -> {
            PersistedDraft current = decodeDraft(raw);
            if (current.damaged()) return null;
            return encodeDraft(new PersistedDraft(auction, duration, delay, current.items()));
        });
        if (!stored) return false;
        Draft cached = drafts.get(id);
        if (cached != null) {
            synchronized (cached) {
                cached.auction = auction;
                cached.durationMinutes = duration;
                cached.publishDelayMinutes = delay;
            }
        }
        return true;
    }

    public boolean forgetDurably(UUID id) {
        // Un borrador con algo ilegible dentro NO se borra: borrarlo es perder ese objeto.
        if (decodeDraft(store.get(id.toString())).damaged()) return false;
        if (!store.removeDurably(id.toString())) return false;
        drafts.remove(id);
        composing.remove(id);
        return true;
    }

    public void forget(UUID id) {
        drafts.remove(id);
        composing.remove(id);
    }

    public boolean recover(PlayerRecovery recovery, UUID id) {
        PersistedDraft stored = decodeDraft(store.get(id.toString()));
        // Devolver solo lo legible y luego borrar la fila tiraria el resto. Se deja entero
        // hasta que el servidor pueda leerlo todo.
        if (stored.damaged()) return false;
        List<PricedItem> persisted = stored.items();
        for (PricedItem entry : persisted) {
            if (!recovery.recover(entry.item())) return false;
        }
        return forgetDurably(id);
    }

    /**
     * Snapshot de los stacks que todavia pertenecen a un borrador durable. El claim ledger
     * lo usa para no entregar una copia con el mismo marker durante un handoff incompleto.
     */
    List<ItemStack> activeEscrowItems() {
        List<ItemStack> active = new ArrayList<>();
        for (String raw : store.all().values()) {
            for (PricedItem entry : decodeDraft(raw).items()) {
                active.add(entry.item().clone());
            }
        }
        return List.copyOf(active);
    }

    @FunctionalInterface
    public interface PlayerRecovery {
        boolean recover(ItemStack item);
    }

    // Fila: precio,descripcionEnBase64,itemEnBase64. La descripcion va en Base64 porque es
    // texto libre y podria contener las comas o el ';' que separan campos y filas. Ni el
    // Double ni los Base64 contienen comas, asi que split(",") reparte los 3 campos limpio.
    private static String encodeItems(List<PricedItem> items) {
        List<String> rows = new ArrayList<>(items.size());
        for (PricedItem item : items) {
            String descB64 = Base64.getEncoder().encodeToString(
                    item.description().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            rows.add(Double.toString(item.price()) + "," + descB64 + ","
                    + MarketListing.itemToB64(item.item()));
        }
        return String.join(";", rows);
    }

    /** Borradores dañados ya avisados, para no repetir el aviso en cada lectura. */
    private static final java.util.Set<String> DAMAGED_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param lost se pone a true si alguna fila no se pudo leer. Un objeto que hoy no
     *     deserializa (falta el plugin o el datapack de su encantamiento) no esta roto, y
     *     antes se saltaba en silencio: la siguiente escritura del borrador lo dejaba fuera
     *     para siempre y, al caerse una fila del medio, los indices dejaban de coincidir con
     *     los de la GUI y se retiraba un objeto distinto del que se habia pulsado.
     */
    private static List<PricedItem> decodeItems(String raw, boolean[] lost) {
        List<PricedItem> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) return items;
        for (String row : raw.split(";")) {
            if (row.isEmpty()) continue;
            // -1 conserva un campo de descripcion vacio en medio ("1.0,,itemB64").
            String[] parts = row.split(",", -1);
            try {
                if (parts.length >= 3) {                    // formato nuevo: precio,desc,item
                    double price = Double.parseDouble(parts[0]);
                    String desc = new String(Base64.getDecoder().decode(parts[1]),
                            java.nio.charset.StandardCharsets.UTF_8);
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[2]));
                    items.add(new PricedItem(item, price, desc));
                } else if (parts.length == 2 && !parts[0].isEmpty()) { // formato viejo: precio,item
                    double price = Double.parseDouble(parts[0]);
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[1]));
                    items.add(new PricedItem(item, price));
                } else {
                    lost[0] = true;
                }
            } catch (RuntimeException unreadable) {
                lost[0] = true;
                if (DAMAGED_REPORTED.add(row)) {
                    java.util.logging.Logger.getLogger("Chatalyst").severe(
                            "Market publish draft: an item could not be read. The draft is"
                            + " kept as stored and locked until the server can read it: "
                            + unreadable);
                }
            }
        }
        return items;
    }

    private static String encodeDraft(PersistedDraft draft) {
        String items = Base64.getEncoder().encodeToString(
                encodeItems(draft.items()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return DRAFT_V2 + (draft.auction() ? "1" : "0") + "|"
                + boundedMinutes(draft.durationMinutes()) + "|"
                + boundedMinutes(draft.publishDelayMinutes()) + "|" + items;
    }

    private static PersistedDraft decodeDraft(String raw) {
        if (raw == null || raw.isBlank()) return PersistedDraft.empty();
        boolean[] lost = {false};
        if (!raw.startsWith(DRAFT_V2)) {
            return new PersistedDraft(false, 0, 0, decodeItems(raw, lost), lost[0]);
        }
        String[] parts = raw.split("\\|", 5);
        if (parts.length != 5 || !"v2".equals(parts[0])) return PersistedDraft.unreadable();
        try {
            boolean auction = "1".equals(parts[1]);
            long duration = boundedMinutes(Long.parseLong(parts[2]));
            long delay = boundedMinutes(Long.parseLong(parts[3]));
            String itemRows = new String(Base64.getDecoder().decode(parts[4]),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new PersistedDraft(auction, duration, delay,
                    decodeItems(itemRows, lost), lost[0]);
        } catch (IllegalArgumentException failure) {
            return PersistedDraft.unreadable();
        }
    }

    private static long boundedMinutes(long minutes) {
        return Math.max(0, Math.min(MAX_PERSISTED_MINUTES, minutes));
    }

    /** {@code damaged}: lo guardado tiene algo que hoy no se puede leer; no se reescribe. */
    private record PersistedDraft(boolean auction, long durationMinutes,
                                  long publishDelayMinutes, List<PricedItem> items,
                                  boolean damaged) {
        private PersistedDraft {
            items = items == null ? List.of() : List.copyOf(items);
        }

        PersistedDraft(boolean auction, long durationMinutes, long publishDelayMinutes,
                       List<PricedItem> items) {
            this(auction, durationMinutes, publishDelayMinutes, items, false);
        }

        static PersistedDraft empty() {
            return new PersistedDraft(false, 0, 0, List.of());
        }

        /** Habia algo guardado y no se entiende: tratarlo como vacio lo sobrescribiria. */
        static PersistedDraft unreadable() {
            return new PersistedDraft(false, 0, 0, List.of(), true);
        }

        PersistedDraft withItems(List<PricedItem> replacement) {
            return new PersistedDraft(auction, durationMinutes, publishDelayMinutes, replacement);
        }
    }
}
