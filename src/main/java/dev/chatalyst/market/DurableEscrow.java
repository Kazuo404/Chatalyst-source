package dev.chatalyst.market;

import dev.chatalyst.db.Store;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Journal por-stack para trades/negociaciones transitorios. */
final class DurableEscrow {

    private static final char SEP = '\u001F';

    private final JavaPlugin plugin;
    private final Store store;
    private final MarketManager market;

    DurableEscrow(JavaPlugin plugin, Store store, MarketManager market) {
        this.plugin = plugin;
        this.store = store;
        this.market = market;
    }

    /** Marca, journaliza y retira un slot. null significa que nada cambio de propietario. */
    ItemStack deposit(Player player, int slot, ItemStack source) {
        ItemStack original = source.clone();
        ItemStack marked = market.markEscrowItem(original);
        String marker = market.escrowMarker(marked);
        if (marker == null) return null;

        player.getInventory().setItem(slot, marked.clone());
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            player.getInventory().setItem(slot, original);
            return null;
        }

        String raw = encode(new Entry(player.getUniqueId(), marked));
        if (!store.compareAndPutDurably(marker, null, raw)) {
            player.getInventory().setItem(slot, original);
            saveQuietly(player);
            return null;
        }

        ItemStack current = player.getInventory().getItem(slot);
        if (marker.equals(market.escrowMarker(current))) {
            player.getInventory().setItem(slot, null);
        }
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            // El journal conserva el stack. recoverAll()/claim deduplican el marker si el
            // playerdata anterior aun contiene la copia marcada.
            plugin.getLogger().severe("Could not finish trade escrow for " + player.getName()
                    + ": " + failure.getMessage());
            // El caller no recibira el stack (deposit devuelve null), asi que intentamos
            // sacarlo inmediatamente del journal hacia su alijo. Si tambien falla, el
            // journal permanece y recoverAll lo completa en el siguiente arranque.
            release(marked, player.getUniqueId());
            return null;
        }
        return marked;
    }

    /** Cambia durablemente el dueño final, lo pasa al claim ledger y consume el journal. */
    boolean release(ItemStack item, UUID owner) {
        String marker = market.escrowMarker(item);
        if (marker == null) return market.stashFor(owner, java.util.List.of(item));
        return release(marker, owner, item);
    }

    /** Resuelve por marker desde un settlement; null en el store significa ya completado. */
    boolean release(String marker, UUID owner) {
        return release(marker, owner, null);
    }

    private boolean release(String marker, UUID owner, ItemStack fallback) {
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = store.get(marker);
            if (observed == null) {
                return fallback == null || market.stashFor(owner, java.util.List.of(fallback));
            }
            Entry entry = decode(observed);
            if (entry == null) return false;
            String reassigned = encode(new Entry(owner, entry.item()));
            if (!store.compareAndPutDurably(marker, observed, reassigned)) continue;
            if (!market.stashFor(owner, java.util.List.of(entry.item()))) return false;
            return store.compareAndRemoveDurably(marker, reassigned);
        }
        return false;
    }

    /** Todos los trades desaparecen tras reinicio: cada journal vuelve a su ultimo dueño. */
    void recoverAll() {
        recoverAll(java.util.Set.of());
    }

    /** Recupera todo salvo markers retenidos por una transaccion Vault no reconciliada. */
    void recoverAll(java.util.Set<String> blockedMarkers) {
        for (Map.Entry<String, String> raw : store.all().entrySet()) {
            if (blockedMarkers != null && blockedMarkers.contains(raw.getKey())) continue;
            Entry entry = decode(raw.getValue());
            if (entry == null) {
                // Journal ilegible: se retira para no reprocesarlo por siempre, pero SIEMPRE
                // con rastro en el log — representa el stack de alguien y esta es la unica
                // pista forense que quedara de el.
                // Ilegible HOY no es corrupto: puede faltar el plugin o el datapack de un
                // encantamiento. Se deja guardado y se reintenta en el siguiente arranque;
                // borrarlo era perder el objeto de alguien por un fallo que podia ser temporal.
                plugin.getLogger().severe("A trade escrow journal could not be read (key "
                        + raw.getKey() + ") and was left stored; it is retried on the next"
                        + " start. Raw value: " + raw.getValue());
                continue;
            }
            if (market.stashFor(entry.owner(), java.util.List.of(entry.item()))) {
                store.compareAndRemoveDurably(raw.getKey(), raw.getValue());
            }
        }
    }

    private void saveQuietly(Player player) {
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save inventory rollback for "
                    + player.getName() + ": " + failure.getMessage());
        }
    }

    private static String encode(Entry entry) {
        return entry.owner() + String.valueOf(SEP) + MarketListing.itemToB64(entry.item());
    }

    private static Entry decode(String raw) {
        if (raw == null) return null;
        String[] fields = raw.split(String.valueOf(SEP), 2);
        if (fields.length != 2) return null;
        try {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(fields[1]));
            return new Entry(UUID.fromString(fields[0]), item);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private record Entry(UUID owner, ItemStack item) {
    }
}
