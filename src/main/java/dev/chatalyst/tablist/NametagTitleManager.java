package dev.chatalyst.tablist;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Personalizacion del nametag fancy por jugador: un {@code Title} (renglon flotante ENCIMA
 * del tag, ej. "REY DE TODO") y dos iconos opcionales, {@code Icon_Left} e {@code Icon_Right},
 * que flanquean el tag (ej. un escudo de guardian). Los tres son MiniMessage y admiten
 * {@code :tokens:} de ItemsAdder; salen por los placeholders {nametag_title},
 * {nametag_icon_left} y {nametag_icon_right}.
 *
 * <p>Se guardan en el Store "nametag" como {@code title US iconLeft US iconRight}. El diseño
 * (CAS durable por campo, cache al final) es el mismo que {@link dev.chatalyst.tag.TagManager}:
 * asi dos ediciones concurrentes de campos distintos no se pisan.
 */
public final class NametagTitleManager {

    private static final char US = ''; // separador de campos (nunca llega del chat)
    private static final int FIELDS = 3;
    public static final int TITLE = 0;
    public static final int ICON_LEFT = 1;
    public static final int ICON_RIGHT = 2;

    private final Store store;
    private final Map<UUID, String[]> cache = new ConcurrentHashMap<>();

    public NametagTitleManager(StorageManager storage) {
        this(storage.store("nametag"));
    }

    /** Para tests y stores a medida. */
    public NametagTitleManager(Store store) {
        this.store = store;
        // Con la base compartida en red, el titulo cambiado en otro servidor llegaba a su
        // Store pero no a esta cache: el cartel seguia con el viejo hasta un reload.
        store.addInvalidationListener(keys -> {
            for (String key : keys) {
                try {
                    reload(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                    // clave corrupta
                }
            }
        });
    }

    /**
     * La cache se rellena SIEMPRE leyendo el Store, dentro de un compute atomico por jugador.
     * Antes cada set publicaba su propia copia tras el CAS: dos cambios seguidos (titulo e
     * icono) podian publicarse al reves y dejar en pantalla un campo que ya no estaba guardado.
     */
    private void reload(UUID id) {
        cache.compute(id, (key, ignored) -> {
            String stored = store.get(key.toString());
            return stored == null ? null : split(stored);
        });
    }

    public void load() {
        cache.clear();
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            try {
                cache.put(UUID.fromString(e.getKey()), split(e.getValue()));
            } catch (IllegalArgumentException ignore) {
                // clave corrupta: se ignora, como en el resto de stores
            }
        }
    }

    /** Un campo del jugador ("" si no tiene). {@code field} = TITLE / ICON_LEFT / ICON_RIGHT. */
    public String field(UUID id, int field) {
        String[] v = cache.get(id);
        return v != null ? v[field] : "";
    }

    public String title(UUID id) {
        return field(id, TITLE);
    }

    public String iconLeft(UUID id) {
        return field(id, ICON_LEFT);
    }

    public String iconRight(UUID id) {
        return field(id, ICON_RIGHT);
    }

    /**
     * Guarda un campo ("" = quitarlo) con CAS durable; si los tres quedan vacios, borra la
     * fila entera. Devuelve false si no consiguio persistir (y resincroniza el cache).
     */
    public boolean set(UUID id, int field, String value) {
        String key = id.toString();
        String safe = value == null ? "" : value;
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = store.get(key);
            String[] current = observed == null ? blank() : split(observed);
            String[] next = current.clone();
            next[field] = safe;
            boolean empty = isEmpty(next);
            boolean persisted;
            if (empty) {
                persisted = observed == null || store.compareAndRemoveDurably(key, observed);
            } else {
                persisted = store.compareAndPutDurably(key, observed, join(next));
            }
            if (!persisted) continue;
            reload(id);
            return true;
        }
        reload(id);
        return false;
    }

    /** Borra los tres campos de un jugador. */
    public boolean clear(UUID id) {
        String key = id.toString();
        // Con reintentos, como set(): un solo intento fallaba en cuanto otro cambio se cruzaba.
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = store.get(key);
            if (observed == null || store.compareAndRemoveDurably(key, observed)) {
                reload(id);
                return true;
            }
        }
        reload(id);
        return false;
    }

    private static String[] blank() {
        return new String[]{"", "", ""};
    }

    private static boolean isEmpty(String[] v) {
        for (String s : v) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    private static String join(String[] v) {
        return v[TITLE] + US + v[ICON_LEFT] + US + v[ICON_RIGHT];
    }

    static String[] split(String raw) {
        String[] out = blank();
        int start = 0;
        for (int i = 0; i < FIELDS; i++) {
            int sep = raw.indexOf(US, start);
            if (sep < 0) {
                out[i] = raw.substring(start);
                break;
            }
            out[i] = raw.substring(start, sep);
            start = sep + 1;
        }
        return out;
    }
}
