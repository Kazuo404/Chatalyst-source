package dev.chatalyst.chestlock;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leer y escribir candados en los bloques.
 *
 * <p>Un cofre doble son DOS bloques con dos contenedores de datos: el candado se escribe en las
 * dos mitades y basta con que una lo tenga para que el cofre entero cuente como protegido. Asi
 * no hay forma de abrir "la otra mitad".
 */
public final class LockService {

    private final ChatalystPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey ownerNameKey;
    private final NamespacedKey trustedKey;
    final NamespacedKey signKey;
    /** Letrero recien colgado cuyo editor sigue abierto: aun no se sabe si sera candado o cartel normal. */
    final NamespacedKey pendingKey;
    private volatile LockSettings settings;

    public LockService(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "lock_owner");
        this.ownerNameKey = new NamespacedKey(plugin, "lock_owner_name");
        this.trustedKey = new NamespacedKey(plugin, "lock_trusted");
        this.signKey = new NamespacedKey(plugin, "lock_sign");
        this.pendingKey = new NamespacedKey(plugin, "lock_pending");
        reload();
    }

    public void reload() {
        settings = LockSettings.read(plugin.getConfig());
    }

    public LockSettings settings() {
        return settings;
    }

    public boolean isLockable(Block block) {
        return block != null && settings.isLockable(block.getType()) && block.getState(false) instanceof TileState;
    }

    /** Los bloques que forman el contenedor: uno, o los dos de un cofre doble. */
    public List<Block> parts(Block block) {
        List<Block> out = new ArrayList<>(2);
        out.add(block);
        if (block.getState(false) instanceof Chest chest
                && chest.getInventory().getHolder(false) instanceof DoubleChest dc) {
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(false), dc.getRightSide(false)}) {
                if (side instanceof Chest c && !c.getBlock().equals(block)) out.add(c.getBlock());
            }
        }
        return out;
    }

    /** El candado del contenedor (de cualquiera de sus mitades), o vacio si no tiene. */
    public Optional<Lock> lockOf(Block block) {
        if (!isLockable(block)) return Optional.empty();
        for (Block part : parts(block)) {
            Optional<Lock> lock = read(part);
            if (lock.isPresent()) return lock;
        }
        return Optional.empty();
    }

    /** Para PONER un candado: TODAS las mitades dentro de una region (si la config lo pide). */
    public boolean regionAllows(Block block) {
        if (!settings.requireRegion()) return true;
        for (Block part : parts(block)) {
            java.util.Set<String> r = dev.chatalyst.hook.WorldGuardHook.regionsAtOrUnknown(part.getLocation());
            if (r == null || r.isEmpty()) return false;
        }
        return true;
    }

    /** Para poner un candado: el jugador es dueno/miembro y puede abrir cofres en todas las mitades. */
    public boolean memberEverywhere(org.bukkit.entity.Player player, Block block) {
        for (Block part : parts(block)) {
            if (!dev.chatalyst.hook.WorldGuardHook.isMemberAt(player, part.getLocation())) return false;
        }
        return true;
    }

    /**
     * El candado que VALE. Si la config pide region y se CONFIRMA que ya no queda ninguna en
     * ninguna mitad (quitaron la proteccion), el candado se borra y el cofre queda libre. Si no se
     * puede saber (WorldGuard caido, regiones sin cargar), el candado se conserva.
     */
    public Optional<Lock> activeLock(Block block) {
        Optional<Lock> lock = lockOf(block);
        if (lock.isEmpty() || !settings.requireRegion()) return lock;
        for (Block part : parts(block)) {
            java.util.Set<String> r = dev.chatalyst.hook.WorldGuardHook.regionsAtOrUnknown(part.getLocation());
            if (r == null || !r.isEmpty()) return lock;
        }
        remove(block);
        return Optional.empty();
    }

    /** Escribe el candado en UN bloque (sin tocar su otra mitad). */
    public void writeOne(Block part, Lock lock) {
        if (!(part.getState() instanceof TileState tile)) return;
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, lock.owner().toString());
        pdc.set(ownerNameKey, PersistentDataType.STRING, lock.ownerName());
        if (lock.trusted().isEmpty()) pdc.remove(trustedKey);
        else pdc.set(trustedKey, PersistentDataType.STRING, Lock.encodeTrusted(lock.trusted()));
        tile.update(true, false);
    }

    /** Cierra el inventario a quien lo tenga abierto y ya no pueda (candado nuevo o acceso quitado). */
    public void closeUnauthorized(Block block, Lock lock) {
        if (!(block.getState(false) instanceof org.bukkit.block.Container c)) return;
        for (org.bukkit.entity.HumanEntity viewer : new java.util.ArrayList<>(c.getInventory().getViewers())) {
            if (!(viewer instanceof org.bukkit.entity.Player p)) continue;
            if (lock.canAccess(p.getUniqueId()) || p.hasPermission("chatalyst.lock.bypass")) continue;
            dev.chatalyst.util.FoliaTasks.entity(plugin, p, org.bukkit.entity.Player::closeInventory);
        }
    }

    private Optional<Lock> read(Block block) {
        if (!(block.getState(false) instanceof TileState tile)) return Optional.empty();
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (owner == null) return Optional.empty();
        try {
            return Optional.of(new Lock(UUID.fromString(owner),
                    pdc.getOrDefault(ownerNameKey, PersistentDataType.STRING, "?"),
                    Lock.decodeTrusted(pdc.get(trustedKey, PersistentDataType.STRING))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Pone (o actualiza) el candado en todas las mitades del contenedor. */
    public void write(Block block, Lock lock) {
        for (Block part : parts(block)) {
            if (!(part.getState() instanceof TileState tile)) continue;
            PersistentDataContainer pdc = tile.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, lock.owner().toString());
            pdc.set(ownerNameKey, PersistentDataType.STRING, lock.ownerName());
            if (lock.trusted().isEmpty()) pdc.remove(trustedKey);
            else pdc.set(trustedKey, PersistentDataType.STRING, Lock.encodeTrusted(lock.trusted()));
            tile.update(true, false);
        }
    }

    /** Quita el candado de todas las mitades. */
    public void remove(Block block) {
        for (Block part : parts(block)) {
            if (!(part.getState() instanceof TileState tile)) continue;
            PersistentDataContainer pdc = tile.getPersistentDataContainer();
            pdc.remove(ownerKey);
            pdc.remove(ownerNameKey);
            pdc.remove(trustedKey);
            tile.update(true, false);
        }
    }

    /** Si el bloque es uno de los letreros que pone el candado. */
    public boolean isLockSign(Block block) {
        BlockState state = block.getState(false);
        return state instanceof org.bukkit.block.Sign sign
                && sign.getPersistentDataContainer().has(signKey, PersistentDataType.STRING);
    }

    /** El dueno guardado en uno de nuestros letreros. */
    public Optional<UUID> signOwner(Block block) {
        return uuidIn(block, signKey);
    }

    /** Quien colgo el letrero pendiente (editor abierto, aun sin decidir candado o cartel normal). */
    public Optional<UUID> pendingOwner(Block block) {
        return uuidIn(block, pendingKey);
    }

    private static Optional<UUID> uuidIn(Block block, NamespacedKey key) {
        if (!(block.getState(false) instanceof org.bukkit.block.Sign sign)) return Optional.empty();
        String raw = sign.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
