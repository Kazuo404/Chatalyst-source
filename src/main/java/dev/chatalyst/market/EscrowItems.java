package dev.chatalyst.market;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Identidad durable y oculta para hacer idempotentes los traspasos de ItemStack. */
final class EscrowItems {

    private final NamespacedKey markerKey;

    EscrowItems(JavaPlugin plugin) {
        markerKey = new NamespacedKey(plugin, "escrow_id");
    }

    ItemStack marked(ItemStack source) {
        ItemStack item = source.clone();
        if (marker(item) == null) {
            item.editMeta(meta -> meta.getPersistentDataContainer().set(
                    markerKey, PersistentDataType.STRING, UUID.randomUUID().toString()));
        }
        return item;
    }

    String marker(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(markerKey, PersistentDataType.STRING);
    }

    void clearMarker(ItemStack item) {
        if (marker(item) == null) return;
        item.editMeta(meta -> meta.getPersistentDataContainer().remove(markerKey));
    }

    boolean inventoryContains(Player player, String id) {
        if (id == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (id.equals(marker(item))) return true;
        }
        return false;
    }

    boolean removeFromInventory(Player player, String id) {
        if (id == null) return false;
        ItemStack[] contents = player.getInventory().getContents();
        boolean removed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            if (!id.equals(marker(contents[slot]))) continue;
            player.getInventory().setItem(slot, null);
            removed = true;
        }
        return removed;
    }

    void clearMarkers(Player player, Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (ids.contains(marker(item))) clearMarker(item);
        }
    }

    void clearOrphans(Player player, Set<String> activeIds) {
        Set<String> active = activeIds == null ? Set.of() : new HashSet<>(activeIds);
        for (ItemStack item : player.getInventory().getContents()) {
            String marker = marker(item);
            if (marker != null && !active.contains(marker)) clearMarker(item);
        }
    }
}
