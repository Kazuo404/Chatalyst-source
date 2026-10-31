package dev.chatalyst.anonymous;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Slots de disfraz GUARDADOS por jugador: el nombre real y la skin firmada de una cara que le
 * gusto, para reutilizarla luego con {@code /anon set <slot>}. Pensado para streamers/VIPs que
 * quieren volver a un mismo personaje en vez de sortear uno nuevo cada vez.
 *
 * <p>Persiste en el Store {@code anonymous_slots} — la MISMA base de datos que el resto del
 * plugin (MySQL, SQLite o YAML segun {@code Database} en config.yml), con UNA fila por jugador
 * que lleva todos sus slots serializados. Sin BD (YAML) funciona igual, guardando en archivo.
 */
public final class AnonymousSlots {

    private static final char US = ''; // separador de campos dentro de un slot
    private static final char RS = ''; // separador entre slots
    private static final String TEXTURES = "textures";

    /** Un slot guardado: su indice, el nombre del disfraz y su skin firmada (nullable). */
    public record Saved(int slot, String name, ProfileProperty skin) {
    }

    private final Store store;

    public AnonymousSlots(StorageManager storage) {
        this.store = storage.store("anonymous_slots");
    }

    /** Todos los slots del jugador, ordenados por indice ascendente. */
    public List<Saved> list(UUID id) {
        return parse(store.get(id.toString()));
    }

    /** El slot pedido, o null si esta vacio. */
    public Saved get(UUID id, int slot) {
        for (Saved s : list(id)) {
            if (s.slot() == slot) return s;
        }
        return null;
    }

    public int count(UUID id) {
        return list(id).size();
    }

    /**
     * Nombres de disfraz que YA tiene guardado ALGUIEN (cualquier jugador), en minusculas. Un
     * disfraz guardado queda RESERVADO: no se reparte al azar a otro ni se puede volver a
     * guardar, para que dos jugadores no acaben con la misma cara. Se recorre la fila de cada
     * jugador; con MySQL compartido tambien ve los slots de los demas servidores de la red.
     */
    public Set<String> reservedNames() {
        Set<String> out = new HashSet<>();
        for (String raw : store.all().values()) {
            for (Saved s : parse(raw)) {
                if (s.name() != null && !s.name().isEmpty()) {
                    out.add(s.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** True si ese nombre de disfraz lo tiene guardado alguien DISTINTO de {@code exceptId}. */
    public boolean savedByOther(String name, UUID exceptId) {
        if (name == null || name.isEmpty()) return false;
        String owner = exceptId == null ? null : exceptId.toString();
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            if (owner != null && owner.equals(e.getKey())) continue;
            for (Saved s : parse(e.getValue())) {
                if (name.equalsIgnoreCase(s.name())) return true;
            }
        }
        return false;
    }

    /** Primer slot libre en {@code [1..max]}, o -1 si todos estan ocupados. */
    public int firstFreeSlot(UUID id, int max) {
        Set<Integer> used = new HashSet<>();
        for (Saved s : list(id)) {
            used.add(s.slot());
        }
        for (int i = 1; i <= max; i++) {
            if (!used.contains(i)) return i;
        }
        return -1;
    }

    /** Guarda (o reemplaza) un slot durablemente. Devuelve false si el backend fallo. */
    public boolean save(UUID id, int slot, String name, ProfileProperty skin) {
        List<Saved> current = new ArrayList<>(list(id));
        current.removeIf(s -> s.slot() == slot);
        current.add(new Saved(slot, name, skin));
        current.sort(Comparator.comparingInt(Saved::slot));
        return store.putDurably(id.toString(), serialize(current));
    }

    /** Borra un slot. Devuelve true tambien si ya estaba vacio (nada que hacer). */
    public boolean delete(UUID id, int slot) {
        List<Saved> current = new ArrayList<>(list(id));
        if (!current.removeIf(s -> s.slot() == slot)) return true;
        return current.isEmpty()
                ? store.removeDurably(id.toString())
                : store.putDurably(id.toString(), serialize(current));
    }

    // ---- Serializacion (base64 de la skin no lleva US/RS, los nombres tampoco) ----------

    private static String serialize(List<Saved> slots) {
        StringBuilder sb = new StringBuilder();
        for (Saved s : slots) {
            if (sb.length() > 0) sb.append(RS);
            String value = s.skin() != null ? s.skin().getValue() : "";
            String sig = s.skin() != null && s.skin().getSignature() != null
                    ? s.skin().getSignature() : "";
            sb.append(s.slot()).append(US)
                    .append(s.name() == null ? "" : s.name()).append(US)
                    .append(value).append(US)
                    .append(sig);
        }
        return sb.toString();
    }

    private static List<Saved> parse(String raw) {
        List<Saved> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String chunk : raw.split(String.valueOf(RS))) {
            String[] f = chunk.split(String.valueOf(US), -1);
            if (f.length < 4) continue;
            try {
                int slot = Integer.parseInt(f[0]);
                String name = f[1];
                String value = f[2];
                String sig = f[3].isEmpty() ? null : f[3];
                ProfileProperty skin = value.isEmpty()
                        ? null : new ProfileProperty(TEXTURES, value, sig);
                out.add(new Saved(slot, name, skin));
            } catch (RuntimeException ignored) {
                // fila corrupta: se salta, como en el resto de stores
            }
        }
        out.sort(Comparator.comparingInt(Saved::slot));
        return out;
    }
}
