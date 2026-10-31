package dev.chatalyst.gui;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda el color/gradiente de chat elegido por cada jugador (un tag MiniMessage como
 * "&lt;red&gt;" o "&lt;gradient:#a:#b&gt;"). Persiste via {@link Store} (YAML o base de datos).
 */
public final class ChatColorManager {

    private final Map<UUID, String> colors = new ConcurrentHashMap<>();
    private final Map<UUID, Set<TextStyleOption>> styles = new ConcurrentHashMap<>();
    private final Store colorStore;
    private final Store styleStore;

    public ChatColorManager(StorageManager storage) {
        this(storage.store("colors"), storage.store("color-styles"));
    }

    ChatColorManager(Store colorStore, Store styleStore) {
        this.colorStore = colorStore;
        this.styleStore = styleStore;
    }

    public void loadPlayer(Player p) {
        String tag = colorStore.get(p.getUniqueId().toString());
        if (tag != null && !tag.isEmpty()) colors.put(p.getUniqueId(), tag);
        Set<TextStyleOption> loaded = TextStyleOption.parseIds(styleStore.get(p.getUniqueId().toString()));
        if (!loaded.isEmpty()) styles.put(p.getUniqueId(), EnumSet.copyOf(loaded));
    }

    /** Relee ambos stores, incluyendo la eliminacion de valores que ya no existan. */
    public void reloadPlayer(Player p) {
        colors.remove(p.getUniqueId());
        styles.remove(p.getUniqueId());
        loadPlayer(p);
    }

    public void unloadPlayer(Player p) {
        colors.remove(p.getUniqueId());
        styles.remove(p.getUniqueId());
    }

    /** Tag MiniMessage combinado del color + estilos del jugador, o cadena vacia. */
    public String getColorTag(UUID id) {
        return getBaseColorTag(id) + getStyleTag(id);
    }

    /** Solo el color/gradiente elegido, sin decoraciones. */
    public String getBaseColorTag(UUID id) {
        return colors.getOrDefault(id, "");
    }

    public Set<TextStyleOption> getStyles(UUID id) {
        Set<TextStyleOption> current = styles.get(id);
        return current == null || current.isEmpty()
                ? EnumSet.noneOf(TextStyleOption.class)
                : EnumSet.copyOf(current);
    }

    public String getStyleTag(UUID id) {
        return TextStyleOption.tags(getStyles(id));
    }

    public boolean setColor(UUID id, String tag) {
        if (tag == null || tag.isEmpty()) {
            return clearColor(id);
        }
        if (!colorStore.putDurably(id.toString(), tag)) return false;
        colors.put(id, tag);
        return true;
    }

    public boolean clearColor(UUID id) {
        if (!colorStore.removeDurably(id.toString())) return false;
        colors.remove(id);
        return true;
    }

    public boolean setStyle(UUID id, TextStyleOption style, boolean enabled) {
        String key = id.toString();
        java.util.concurrent.atomic.AtomicReference<Set<TextStyleOption>> updated =
                new java.util.concurrent.atomic.AtomicReference<>(
                        EnumSet.noneOf(TextStyleOption.class));
        boolean persisted = dev.chatalyst.db.StoreCas.updateDurably(styleStore, key, raw -> {
            Set<TextStyleOption> current = TextStyleOption.parseIds(raw);
            Set<TextStyleOption> next = current.isEmpty()
                    ? EnumSet.noneOf(TextStyleOption.class) : EnumSet.copyOf(current);
            if (enabled) next.add(style);
            else next.remove(style);
            updated.set(next);
            return TextStyleOption.serializeIds(next);
        });
        if (!persisted) return false;
        Set<TextStyleOption> applied = updated.get();
        if (applied.isEmpty()) styles.remove(id);
        else styles.put(id, EnumSet.copyOf(applied));
        return true;
    }

    public boolean clearStyles(UUID id) {
        if (!styleStore.removeDurably(id.toString())) return false;
        styles.remove(id);
        return true;
    }

    public void shutdown() {
        colorStore.flushNow();
        styleStore.flushNow();
    }
}
