package dev.chatalyst.chestlock;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Candados de cofres: shift + clic derecho con un cartel en la mano protege el contenedor.
 *
 * <p>Va por encima de las protecciones de terreno y de los clanes A PROPOSITO: el problema que
 * resuelve es justo el robo entre gente que comparte terreno, a la que la proteccion del
 * terreno deja entrar. Por eso se cancela en LOW (antes que los demas abran o rompan) y no se
 * mira si otro plugin ya lo permitio.
 */
public final class LockListener implements Listener {

    static final String BYPASS = "chatalyst.lock.bypass";
    private static final Set<BlockFace> WALL_FACES = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private final ChatalystPlugin plugin;
    private final LockService locks;

    public LockListener(ChatalystPlugin plugin, LockService locks) {
        this.plugin = plugin;
        this.locks = locks;
    }

    private static boolean isSign(ItemStack item) {
        // Por nombre y no por Tag: los tags de objetos de carteles cambian de nombre entre versiones.
        if (item == null) return false;
        String name = item.getType().name();
        return name.endsWith("_SIGN") && !name.contains("_WALL_");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        LockSettings s = locks.settings();
        if (!s.enabled()) return;
        Block block = e.getClickedBlock();
        if (block == null || !locks.isLockable(block)) return;
        Player p = e.getPlayer();
        Optional<Lock> lock = locks.activeLock(block);

        if (p.isSneaking() && isSign(e.getItem())) {
            e.setCancelled(true);
            if (lock.isPresent()) {
                if (lock.get().owner().equals(p.getUniqueId())) send(p, s.message("Already_Yours", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
                else send(p, s.message("Denied", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
                return;
            }
            if (!s.worldAllowed(block.getWorld().getName())) {
                send(p, s.message("World_Disabled", dev.chatalyst.lang.Lang.isSpanish(p)), new Lock(p.getUniqueId(), p.getName(), Set.of()));
                return;
            }
            if (!locks.regionAllows(block)) {
                send(p, s.message("Need_Region", dev.chatalyst.lang.Lang.isSpanish(p)), new Lock(p.getUniqueId(), p.getName(), Set.of()));
                return;
            }
            // Solo en una proteccion de la que es dueno o miembro: sin esto cualquiera podia entrar
            // a una base ajena y ponerle candado a los cofres del dueno.
            if (s.requireRegion() && !p.hasPermission(BYPASS) && !locks.memberEverywhere(p, block)) {
                send(p, s.message("Not_Your_Region", dev.chatalyst.lang.Lang.isSpanish(p)), new Lock(p.getUniqueId(), p.getName(), Set.of()));
                return;
            }
            Material held = e.getItem().getType();
            if (s.consumeSign() && p.getGameMode() != GameMode.CREATIVE) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                hand.setAmount(hand.getAmount() - 1);
            }
            // 2026-10-15 (KZ): con letrero, se decide al guardar el editor: en blanco = candado,
            // con texto = cartel normal sin candado (p. ej. [shop] para un plugin de tiendas).
            if (s.placeSign() && placePendingSign(p, block, e.getBlockFace(), held)) {
                send(p, s.message("Sign_Choice", dev.chatalyst.lang.Lang.isSpanish(p)), new Lock(p.getUniqueId(), p.getName(), Set.of()));
                return;
            }
            // Sin pared libre para el letrero: candado directo, como siempre.
            lockNow(p, block);
            return;
        }

        if (lock.isPresent() && !lock.get().canAccess(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
            e.setCancelled(true);
            send(p, s.message("Denied", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
        }
    }

    /** Pone el candado ya (sin letrero que decidir) y lo avisa. */
    private void lockNow(Player p, Block block) {
        Lock created = new Lock(p.getUniqueId(), p.getName(), Set.of());
        locks.write(block, created);
        locks.closeUnauthorized(block, created);
        send(p, locks.settings().message("Locked", dev.chatalyst.lang.Lang.isSpanish(p)), created);
        plugin.getLogger().info("Chest lock: " + p.getName() + " locked " + block.getType() + " at "
                + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ());
    }

    /**
     * Cuelga un letrero VACIO y PENDIENTE en la cara clicada (si es una pared libre) y le abre el editor. El
     * cofre aun no tiene candado: lo decide {@link #onSignChange} al guardar. Devuelve false si no habia sitio.
     */
    private boolean placePendingSign(Player p, Block container, BlockFace face, Material held) {
        if (!WALL_FACES.contains(face)) return false;
        Block target = container.getRelative(face);
        if (!target.getType().isAir()) return false;
        Material wall = wallSign(held);
        if (wall == null) return false;
        target.setType(wall, false);
        if (target.getBlockData() instanceof Directional dir) {
            dir.setFacing(face);
            target.setBlockData(dir, false);
        }
        if (!(target.getState() instanceof org.bukkit.block.Sign sign)) return false;
        sign.setWaxed(false);
        sign.getPersistentDataContainer().set(locks.pendingKey, PersistentDataType.STRING, p.getUniqueId().toString());
        sign.update(true, false);
        FoliaTasks.entity(plugin, p, pl -> {
            if (target.getState() instanceof org.bukkit.block.Sign s && locks.pendingOwner(target).isPresent()) {
                pl.openSign(s, Side.FRONT);
            }
        });
        // Si el editor nunca se guarda (se desconecta, se cierra el juego), no queda un letrero a medias:
        // se suelta como objeto y el cofre sigue sin candado.
        FoliaTasks.globalLater(plugin, () -> FoliaTasks.region(plugin, target.getLocation(), () -> {
            if (locks.pendingOwner(target).isPresent()) target.breakNaturally();
        }), 20L * 60 * 3);
        return true;
    }

    /** Quita la marca de pendiente (despues de que el servidor aplique el texto) y, si toca, lo marca de candado. */
    private void settleSign(Block signBlock, java.util.UUID lockOwner) {
        FoliaTasks.region(plugin, signBlock.getLocation(), () -> {
            if (!(signBlock.getState() instanceof org.bukkit.block.Sign sign)) return;
            sign.getPersistentDataContainer().remove(locks.pendingKey);
            if (lockOwner != null) sign.getPersistentDataContainer().set(locks.signKey, PersistentDataType.STRING, lockOwner.toString());
            sign.update(true, false);
        });
    }

    /** Clic derecho a un letrero de candado: el texto es fijo; a quien no es el dueno se le dice de quien es. */
    @EventHandler(priority = EventPriority.LOW)
    public void onLockSignInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (!locks.settings().enabled()) return;
        Block block = e.getClickedBlock();
        if (block == null || !isSignBlock(block) || !locks.isLockSign(block)) return;
        Player p = e.getPlayer();
        Optional<java.util.UUID> owner = locks.signOwner(block);
        e.setCancelled(true);
        if (owner.isPresent() && !owner.get().equals(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
            Block attached = attachedTo(block);
            Optional<Lock> lock = attached == null ? Optional.empty() : locks.lockOf(attached);
            send(p, locks.settings().message("Denied", dev.chatalyst.lang.Lang.isSpanish(p)),
                    lock.orElse(new Lock(owner.get(), "?", Set.of())));
        }
    }

    /**
     * Al guardar el editor de un letrero recien colgado: en blanco = candado con el letrero de siempre
     * (Sign_Lines); con texto = cartel normal, sin candado, y el evento sigue sin cancelar para que otros
     * plugins lo lean (un [shop] de un plugin de tiendas). En los letreros de candado de antes, solo el dueno.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        if (!locks.settings().enabled()) return;
        Block block = e.getBlock();
        Optional<java.util.UUID> pending = locks.pendingOwner(block);
        if (pending.isPresent()) {
            decidePending(e, pending.get());
            return;
        }
        if (!locks.isLockSign(block)) return;
        Player p = e.getPlayer();
        Optional<java.util.UUID> owner = locks.signOwner(block);
        if (owner.isPresent() && !owner.get().equals(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
            e.setCancelled(true);
            return;
        }
        for (net.kyori.adventure.text.Component line : e.lines()) {
            if (line != null && !plain(line).isBlank()) return;
        }
        Block attached = attachedTo(block);
        String ownerName = attached == null ? p.getName()
                : locks.lockOf(attached).map(Lock::ownerName).orElse(p.getName());
        List<String> lines = locks.settings().signLines();
        for (int i = 0; i < 4; i++) {
            e.line(i, i < lines.size() ? MM.full(lines.get(i).replace("{owner}", MM.escape(ownerName)))
                    : net.kyori.adventure.text.Component.empty());
        }
    }

    private void decidePending(SignChangeEvent e, java.util.UUID placer) {
        Player p = e.getPlayer();
        Block block = e.getBlock();
        if (!placer.equals(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        boolean spanish = dev.chatalyst.lang.Lang.isSpanish(p);
        Lock mine = new Lock(p.getUniqueId(), p.getName(), Set.of());
        if (!isBlank(e.lines())) {
            settleSign(block, null);
            send(p, locks.settings().message("Sign_Only", spanish), mine);
            return;
        }
        // En blanco = candado. Se vuelve a mirar todo: entre el clic y el guardado pudo cambiar.
        Block container = attachedTo(block);
        LockSettings s = locks.settings();
        boolean ok = container != null && locks.isLockable(container) && locks.activeLock(container).isEmpty()
                && s.worldAllowed(container.getWorld().getName()) && locks.regionAllows(container)
                && (!s.requireRegion() || p.hasPermission(BYPASS) || locks.memberEverywhere(p, container));
        if (!ok) {
            // Sin candado posible no se deja un letrero en blanco: vuelve como objeto.
            e.setCancelled(true);
            FoliaTasks.region(plugin, block.getLocation(), block::breakNaturally);
            send(p, s.message("Need_Region", spanish), mine);
            return;
        }
        List<String> lines = s.signLines();
        for (int i = 0; i < 4; i++) {
            e.line(i, i < lines.size() ? MM.full(lines.get(i).replace("{owner}", MM.escape(p.getName())))
                    : net.kyori.adventure.text.Component.empty());
        }
        settleSign(block, p.getUniqueId());
        lockNow(p, container);
    }

    private static boolean isBlank(List<net.kyori.adventure.text.Component> lines) {
        for (net.kyori.adventure.text.Component line : lines) {
            if (line != null && !plain(line).isBlank()) return false;
        }
        return true;
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static boolean isSignBlock(Block b) {
        return b.getType().name().endsWith("_SIGN");
    }

    /** El bloque en el que esta colgado un letrero de pared (el de detras de su cara). */
    private static Block attachedTo(Block sign) {
        if (sign.getBlockData() instanceof Directional dir) return sign.getRelative(dir.getFacing().getOppositeFace());
        return null;
    }

    static Material wallSign(Material held) {
        String name = held.name();
        String wall = name.endsWith("_HANGING_SIGN") ? name.replace("_HANGING_SIGN", "_WALL_SIGN")
                : name.endsWith("_SIGN") ? name.replace("_SIGN", "_WALL_SIGN") : null;
        Material m = wall == null ? null : Material.matchMaterial(wall);
        return m != null && m.isBlock() ? m : Material.matchMaterial("OAK_WALL_SIGN");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!locks.settings().enabled()) return;
        Block block = e.getBlock();
        Player p = e.getPlayer();
        if (isSignBlock(block) && locks.isLockSign(block)) {
            Optional<java.util.UUID> owner = locks.signOwner(block);
            if (owner.isPresent() && !owner.get().equals(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
                e.setCancelled(true);
                return;
            }
            return;
        }
        if (!locks.isLockable(block)) return;
        Optional<Lock> lock = locks.activeLock(block);
        if (lock.isPresent() && !lock.get().owner().equals(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
            e.setCancelled(true);
            send(p, locks.settings().message("Denied_Break", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
        }
    }

    /**
     * El dueno o un admin rompen el letrero: el candado se va con el (KZ 2026-10-08). En MONITOR
     * para no quitarlo si otro plugin cancelo la rotura y el letrero sigue ahi.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignBroken(BlockBreakEvent e) {
        if (!locks.settings().enabled()) return;
        Block block = e.getBlock();
        if (!isSignBlock(block) || !locks.isLockSign(block)) return;
        Optional<java.util.UUID> owner = locks.signOwner(block);
        Block attached = attachedTo(block);
        if (attached == null || !locks.isLockable(attached)) return;
        Optional<Lock> lock = locks.lockOf(attached);
        if (lock.isPresent() && (owner.isEmpty() || lock.get().owner().equals(owner.get()))) {
            locks.remove(attached);
            Player p = e.getPlayer();
            send(p, locks.settings().message("Unlocked", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
        }
    }

    /** Un cofre pegado a uno protegido se une y comparte inventario: solo lo puede poner el dueno. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!locks.settings().enabled()) return;
        Block placed = e.getBlockPlaced();
        Material type = placed.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST && !type.name().endsWith("COPPER_CHEST")) return;
        Player p = e.getPlayer();
        boolean anyLocked = false;
        for (BlockFace face : WALL_FACES) {
            Block near = placed.getRelative(face);
            if (near.getType() != type) continue;
            Optional<Lock> lock = locks.activeLock(near);
            if (lock.isEmpty()) continue;
            anyLocked = true;
            // TODOS los vecinos: si cualquiera es de otro, no se pone (no solo el primero).
            if (!lock.get().owner().equals(p.getUniqueId()) && !p.hasPermission(BYPASS)) {
                e.setCancelled(true);
                send(p, locks.settings().message("Denied_Merge", dev.chatalyst.lang.Lang.isSpanish(p)), lock.get());
                return;
            }
        }
        if (!anyLocked) return;
        // Cuando el juego ya los haya unido: la mitad nueva hereda el candado de SU pareja real,
        // y solo se escribe en la mitad nueva (nunca se pisa el dueno de otra).
        FoliaTasks.region(plugin, placed.getLocation(), () -> {
            if (placed.getType() != type) return;
            for (Block part : locks.parts(placed)) {
                if (part.equals(placed)) continue;
                locks.lockOf(part).ifPresent(partner -> {
                    if (locks.lockOf(placed).isEmpty()) locks.writeOne(placed, partner);
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (locks.settings().enabled()) e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (locks.settings().enabled()) e.blockList().removeIf(this::protectedBlock);
    }

    /** El Wither y el dragon rompen bloques al moverse sin explosion: tampoco un cofre protegido. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (!locks.settings().enabled() || e.getEntity() instanceof Player) return;
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    private boolean protectedBlock(Block b) {
        if (isSignBlock(b)) return locks.isLockSign(b);
        return locks.settings().isLockable(b.getType()) && locks.activeLock(b).isPresent();
    }

    /** Tolvas y vagonetas tolva: no sacan nada de un contenedor protegido. Meter si pueden. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        LockSettings s = locks.settings();
        if (!s.enabled() || !s.blockHoppers()) return;
        InventoryHolder holder = e.getSource().getHolder(false);
        Block block = null;
        if (holder instanceof BlockInventoryHolder bih) block = bih.getBlock();
        else if (holder instanceof org.bukkit.block.DoubleChest dc && dc.getLeftSide(false) instanceof BlockInventoryHolder l) block = l.getBlock();
        if (block == null || !s.isLockable(block.getType())) return;
        Optional<Lock> sourceLock = locks.activeLock(block);
        if (sourceLock.isEmpty()) return;
        // Una tolva del propio candado (protegida por el mismo dueno) si puede vaciarlo: asi el
        // dueno conserva sus clasificadores.
        InventoryHolder dest = e.getDestination().getHolder(false);
        if (dest instanceof BlockInventoryHolder d && s.isLockable(d.getBlock().getType())) {
            Optional<Lock> destLock = locks.activeLock(d.getBlock());
            if (destLock.isPresent() && destLock.get().owner().equals(sourceLock.get().owner())) return;
        }
        e.setCancelled(true);
    }

    private void send(Player p, String text, Lock lock) {
        if (text == null || text.isBlank()) return;
        p.sendMessage(MM.full(text.replace("{owner}", MM.escape(lock.ownerName()))));
    }
}
