package dev.chatalyst.toggle;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda las preferencias que cada jugador cambio respecto al valor predeterminado (/toggle).
 * Persiste via {@link Store} (YAML o base de datos) como tokens explicitos: {@code +translate}
 * significa activado y {@code -pm} desactivado. Los ids antiguos sin signo se siguen leyendo
 * como "desactivado", para migrar sin activar accidentalmente la traduccion a nadie.
 */
public final class ToggleManager {

    private final Map<UUID, Map<ToggleType, Boolean>> preferences = new ConcurrentHashMap<>();
    private final Store store;

    public ToggleManager(StorageManager storage) {
        this(storage.store("toggles"));
    }

    /** Direct store constructor used by deterministic persistence tests. */
    public ToggleManager(Store store) {
        this.store = store;
    }

    /** True si el jugador tiene la feature activada (recibe). */
    public boolean isEnabled(Player p, ToggleType type) {
        return isEnabled(p.getUniqueId(), type);
    }

    public boolean isEnabled(UUID playerId, ToggleType type) {
        Map<ToggleType, Boolean> saved = preferences.get(playerId);
        if (saved == null) return type.enabledByDefault();
        synchronized (saved) {
            return saved.getOrDefault(type, type.enabledByDefault());
        }
    }

    /** Alterna la feature. Devuelve true si queda activada. */
    public boolean toggle(Player p, ToggleType type) {
        return toggleResult(p, type).enabled();
    }

    public ToggleResult toggleResult(Player p, ToggleType type) {
        return toggleResult(p.getUniqueId(), type);
    }

    boolean toggle(UUID playerId, ToggleType type) {
        return toggleResult(playerId, type).enabled();
    }

    ToggleResult toggleResult(UUID playerId, ToggleType type) {
        Map<ToggleType, Boolean> saved = preferences.computeIfAbsent(playerId,
                u -> new EnumMap<>(ToggleType.class));
        synchronized (saved) {
            boolean current = saved.getOrDefault(type, type.enabledByDefault());
            boolean hadExplicit = saved.containsKey(type);
            Boolean oldExplicit = saved.get(type);
            boolean next = !current;
            if (next == type.enabledByDefault()) {
                saved.remove(type);
            } else {
                saved.put(type, next);
            }
            if (persist(playerId, saved)) return new ToggleResult(next, true);
            if (hadExplicit) saved.put(type, oldExplicit);
            else saved.remove(type);
            if (saved.isEmpty()) preferences.remove(playerId, saved);
            return new ToggleResult(current, false);
        }
    }

    public void loadPlayer(Player p) {
        load(p.getUniqueId());
    }

    void load(UUID playerId) {
        preferences.remove(playerId);
        String value = store.get(playerId.toString());
        if (value == null || value.isEmpty()) return;
        Map<ToggleType, Boolean> saved = new EnumMap<>(ToggleType.class);
        for (String raw : value.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            boolean explicit = token.charAt(0) == '+' || token.charAt(0) == '-';
            boolean enabled = explicit && token.charAt(0) == '+';
            ToggleType type = ToggleType.from(explicit ? token.substring(1) : token);
            if (type == null) continue;

            // El formato anterior guardaba ids sin signo, todos con significado "apagado".
            if (!explicit) enabled = false;
            if (enabled != type.enabledByDefault()) saved.put(type, enabled);
        }
        if (!saved.isEmpty()) preferences.put(playerId, saved);
    }

    public void unloadPlayer(Player p) {
        preferences.remove(p.getUniqueId());
    }

    private boolean persist(UUID id, Map<ToggleType, Boolean> saved) {
        List<String> list = new ArrayList<>();
        synchronized (saved) {
            for (Map.Entry<ToggleType, Boolean> entry : saved.entrySet()) {
                list.add((entry.getValue() ? "+" : "-") + entry.getKey().id());
            }
        }
        if (list.isEmpty()) {
            return store.removeDurably(id.toString());
        } else {
            return store.putDurably(id.toString(), String.join(",", list));
        }
    }

    public record ToggleResult(boolean enabled, boolean persisted) {
    }

    public void shutdown() {
        store.flushNow();
    }
}
