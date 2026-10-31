package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Edicion en vivo del inventario o del ender chest de otro jugador, reservada a los rangos
 * con {@code chatalyst.command.invsee.edit} / {@code chatalyst.command.echest.edit} (se
 * conceden por LuckPerms; sin ellos las vistas siguen siendo de solo lectura). Folia no
 * permite tocar el inventario real desde otra region, asi que se edita una copia y al
 * CERRAR se escribe entera de vuelta en la region del objetivo: la ultima escritura gana.
 * Cada apertura y cada aplicacion quedan en la auditoria.
 */
public final class InventoryEditSession implements Listener {

    public enum Kind { INVENTORY, ENDER_CHEST }

    /** Marca el inventario como sesion de edicion; sin MenuHolder, los clicks fluyen libres. */
    private static final class EditHolder implements InventoryHolder {
        private final Kind kind;
        private final UUID targetId;
        private final String targetName;
        private Inventory inventory;
        // Estado exacto al abrir: si al cerrar nada cambio, NO se escribe de vuelta. Sin esta
        // comparacion, mirar-y-cerrar sobreescribia el contenido real con el snapshot viejo y
        // pisaba (hasta borrar) lo que el objetivo hubiera guardado mientras tanto.
        private ItemStack[] original;

        private EditHolder(Kind kind, UUID targetId, String targetName) {
            this.kind = kind;
            this.targetId = targetId;
            this.targetName = targetName;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** True si este inventario es una sesion de edicion invsee/echest de Chatalyst. */
    public static boolean isEditSession(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof EditHolder;
    }

    // Sesion de inventario (45): 0-35 almacenamiento, 36-39 armadura (botas→casco),
    // 40 segunda mano, 41-44 relleno bloqueado. Ender chest: 27 planos.
    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_START = 36;
    private static final int OFFHAND = 40;
    private static final int BLOCKED_START = 41;
    private static final int INVENTORY_SIZE = 45;

    private final StaffModerationModule module;

    InventoryEditSession(StaffModerationModule module) {
        this.module = module;
    }

    static String editPermission(Kind kind) {
        return kind == Kind.ENDER_CHEST
                ? "chatalyst.command.echest.edit" : "chatalyst.command.invsee.edit";
    }

    /** Copia el contenido en la region del objetivo y abre la sesion en la del staff. */
    public void open(Player viewer, UUID targetId) {
        open(viewer, targetId, Kind.INVENTORY);
    }

    public void open(Player viewer, UUID targetId, Kind kind) {
        Player online = Bukkit.getPlayer(targetId);
        Runnable missing = () -> FoliaTasks.entity(module.plugin(), viewer,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        if (online == null) {
            missing.run();
            return;
        }
        // La proteccion vive AQUI, en la unica puerta de la sesion editable: /invsee, el menu
        // del jugador y la vuelta desde una shulker pasan todos por este metodo. Antes solo el
        // comando la comprobaba y el boton del menu la saltaba. Un objetivo protegido (OP
        // exento, chatalyst.bypass.invsee.edit) se abre en la vista de solo lectura.
        java.util.concurrent.CompletableFuture<Boolean> access =
                viewer.getUniqueId().equals(targetId)
                        ? java.util.concurrent.CompletableFuture.completedFuture(true)
                        : StaffAccess.canAffectAsync(viewer, online,
                                "chatalyst.bypass.invsee.edit");
        access.whenComplete((allowed, failure) -> {
            if (failure == null && Boolean.TRUE.equals(allowed)) {
                openEditable(viewer, online, targetId, kind, missing);
            } else {
                FoliaTasks.entity(module.plugin(), viewer, current -> {
                    if (kind == Kind.ENDER_CHEST) {
                        TargetEnderChestMenu.open(module, current, targetId);
                    } else {
                        TargetInventoryMenu.open(module, current, targetId, 0);
                    }
                });
            }
        });
    }

    private void openEditable(Player viewer, Player online, UUID targetId, Kind kind,
                              Runnable missing) {
        FoliaTasks.entity(module.plugin(), online, target -> {
            String name = target.getName();
            boolean vanished = dev.chatalyst.util.Vanish.isVanished(target);
            ItemStack[] snapshot = kind == Kind.ENDER_CHEST
                    ? clone(target.getEnderChest().getContents())
                    : fullInventory(target);
            FoliaTasks.entity(module.plugin(), viewer, staff -> {
                if (vanished && !StaffAccess.canSeeVanished(staff)) {
                    staff.sendMessage(Lang.get(staff, "common.player-offline"));
                    return;
                }
                EditHolder holder = new EditHolder(kind, targetId, name);
                Inventory inventory = Bukkit.createInventory(holder,
                        kind == Kind.ENDER_CHEST ? 27 : INVENTORY_SIZE,
                        Lang.get(staff, kind == Kind.ENDER_CHEST
                                ? "staff-moderation.echest-edit-title"
                                : "staff-moderation.invsee-edit-title",
                                "player", MM.escape(name)));
                holder.inventory = inventory;
                inventory.setContents(snapshot);
                if (kind == Kind.INVENTORY) {
                    // Paneles bloqueados con la guia de la fila: hacen visible que la
                    // armadura PUESTA (36-39) y la segunda mano (40) tambien se editan.
                    for (int slot = BLOCKED_START; slot < INVENTORY_SIZE; slot++) {
                        inventory.setItem(slot, dev.chatalyst.gui.MenuItems.item(
                                org.bukkit.Material.GRAY_STAINED_GLASS_PANE,
                                Lang.raw(staff, "staff-moderation.invsee-edit-guide"),
                                Lang.rawList(staff,
                                        "staff-moderation.invsee-edit-guide-lore")));
                    }
                }
                holder.original = clone(inventory.getContents());
                staff.openInventory(inventory);
                module.audit(staff, kind == Kind.ENDER_CHEST
                        ? "echest edit open" : "invsee edit open", name);
            });
        }, missing);
    }

    /** 0-35 almacenamiento, 36-39 armadura tal como la da Bukkit (botas→casco), 40 offhand. */
    private static ItemStack[] fullInventory(Player target) {
        ItemStack[] out = new ItemStack[INVENTORY_SIZE];
        ItemStack[] storage = target.getInventory().getStorageContents();
        for (int i = 0; i < Math.min(STORAGE_SIZE, storage.length); i++) {
            out[i] = clone(storage[i]);
        }
        ItemStack[] armor = target.getInventory().getArmorContents();
        for (int i = 0; i < Math.min(4, armor.length); i++) {
            out[ARMOR_START + i] = clone(armor[i]);
        }
        out[OFFHAND] = clone(target.getInventory().getItemInOffHand());
        return out;
    }

    /**
     * Slots de relleno intocables, y CLICK DERECHO sobre una shulker para ver su interior.
     * En una sesion de edicion los clicks NO estan cancelados (es un inventario real), asi
     * que el boton izquierdo sigue cogiendo y moviendo items como siempre; solo el derecho
     * con el cursor VACIO se reinterpreta como "inspeccionar" — con items en el cursor, el
     * derecho conserva su comportamiento vanilla de soltar de uno en uno.
     */
    @EventHandler
    public void onEditClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditHolder holder)) return;
        if (holder.kind == Kind.INVENTORY
                && event.getClickedInventory() == event.getInventory()
                && event.getRawSlot() >= BLOCKED_START) {
            event.setCancelled(true);
            return;
        }
        if (!event.isRightClick() || event.getCursor() != null
                && !event.getCursor().getType().isAir()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (dev.chatalyst.gui.ShulkerViewMenu.contentsOf(clicked) == null) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player staff)) return;
        UUID targetId = holder.targetId;
        Kind kind = holder.kind;
        String targetName = holder.targetName;
        // Al abrir la vista se cierra la sesion, y ese cierre APLICA lo editado hasta ahora
        // (onClose). La flecha de "volver" reabre una sesion nueva con el estado fresco.
        dev.chatalyst.gui.ShulkerViewMenu.tryOpen(staff, targetName, clicked,
                () -> open(staff, targetId, kind));
    }

    @EventHandler
    public void onEditDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditHolder holder)
                || holder.kind != Kind.INVENTORY) return;
        for (int raw : event.getRawSlots()) {
            if (raw >= BLOCKED_START && raw < INVENTORY_SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Al cerrar, el estado editado reemplaza el contenido real en la region del objetivo. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditHolder holder)) return;
        if (!(event.getPlayer() instanceof Player staff)) return;
        ItemStack[] edited = clone(event.getInventory().getContents());
        if (unchanged(holder.original, edited)) {
            // Solo mirar no escribe nada: el contenido real del objetivo queda intacto
            // aunque haya cambiado desde que se abrio la sesion.
            return;
        }
        Player target = Bukkit.getPlayer(holder.targetId);
        Runnable lost = () -> FoliaTasks.entity(module.plugin(), staff, current -> {
            // El write-back se descarta, asi que el intercambio con el staff se REVIERTE:
            // sin esto, lo que saco de la copia se duplicaria (el objetivo conserva los
            // originales) y lo que metio en ella se destruiria con la sesion.
            reconcileLostEdit(current, holder, edited);
            current.sendMessage(Lang.get(current, "staff-moderation.edit-lost",
                    "player", MM.escape(holder.targetName)));
            module.plugin().getLogger().warning("Inventory edit for " + holder.targetName
                    + " by " + current.getName() + " was discarded: target went offline.");
        });
        if (target == null) {
            lost.run();
            return;
        }
        FoliaTasks.entity(module.plugin(), target, current -> {
            // Fusion por slot contra lo que el objetivo tiene AHORA (ver SlotMerge): escribir la
            // copia entera borraba lo ganado durante la sesion y devolvia, duplicado, lo gastado.
            boolean ender = holder.kind == Kind.ENDER_CHEST;
            ItemStack[] live = ender ? clone(current.getEnderChest().getContents())
                    : fullInventory(current);
            int slots = ender ? live.length : BLOCKED_START;
            SlotMerge.Result<ItemStack> merge = SlotMerge.merge(holder.original, edited, live,
                    slots, InventoryEditSession::same);
            ItemStack[] out = merge.merged();
            if (ender) {
                current.getEnderChest().setContents(out);
            } else {
                ItemStack[] storage = new ItemStack[STORAGE_SIZE];
                System.arraycopy(out, 0, storage, 0, STORAGE_SIZE);
                ItemStack[] armor = new ItemStack[4];
                System.arraycopy(out, ARMOR_START, armor, 0, 4);
                current.getInventory().setStorageContents(storage);
                current.getInventory().setArmorContents(armor);
                current.getInventory().setItemInOffHand(out[OFFHAND]);
            }
            module.audit(staff, ender ? "echest edit apply" : "invsee edit apply",
                    holder.targetName);
            // Lo que el objetivo no recibio (o no perdio) de la edicion vuelve al staff, o se le
            // retira: ver SlotMerge.staffBalance. Cubre los slots en conflicto (manda el
            // objetivo) y tambien los que los dos dejaron igual por caminos distintos.
            java.util.Map<ItemStack, Integer> balance = SlotMerge.staffBalance(
                    holder.original, edited, live, out, slots,
                    item -> item.getType().isAir() ? null : item.asOne(),
                    ItemStack::getAmount);
            if (balance.isEmpty()) return;
            int contested = 0;
            for (int i = 0; i < slots; i++) {
                if (!same(holder.original[i], edited[i]) && !same(holder.original[i], live[i])) {
                    contested++;
                }
            }
            int conflictCount = Math.max(1, contested);
            FoliaTasks.entity(module.plugin(), staff, actor -> {
                settle(actor, holder.targetName, balance);
                actor.sendMessage(Lang.get(actor, "staff-moderation.edit-conflict",
                        "player", MM.escape(holder.targetName), "slots", conflictCount));
            });
        }, lost);
    }

    /** Mismo contenido (tipo, cantidad y meta); vacio y aire cuentan como lo mismo. */
    private static boolean same(ItemStack a, ItemStack b) {
        boolean emptyA = a == null || a.getType().isAir();
        boolean emptyB = b == null || b.getType().isAir();
        if (emptyA || emptyB) return emptyA == emptyB;
        return a.equals(b);
    }

    /**
     * Revierte, en el lado del staff, el intercambio de una edicion descartada. Lo que el
     * staff METIO en la sesion se le devuelve (se perderia con la copia); lo que SACO se le
     * retira, porque el objetivo conserva los originales y dejarselo seria duplicar. Si algo
     * sacado ya no esta en su inventario (lo tiro o guardo), queda constancia en el log.
     */
    private void reconcileLostEdit(Player staff, EditHolder holder, ItemStack[] edited) {
        if (holder.original == null) return;
        reconcile(staff, holder.targetName, holder.original, edited);
    }

    /** Revierte en el staff el intercambio entre {@code original} y {@code edited}. */
    private void reconcile(Player staff, String targetName, ItemStack[] original,
                           ItemStack[] edited) {
        java.util.Map<ItemStack, Integer> before = countItems(original);
        java.util.Map<ItemStack, Integer> balance = new java.util.HashMap<>(countItems(edited));
        before.forEach((item, count) -> balance.merge(item, -count, Integer::sum));
        settle(staff, targetName, balance);
    }

    /** Aplica en el staff un balance item -> cantidad: positivo se le da, negativo se le quita. */
    private void settle(Player staff, String targetName,
                        java.util.Map<ItemStack, Integer> balance) {
        for (java.util.Map.Entry<ItemStack, Integer> entry : balance.entrySet()) {
            int added = entry.getValue();
            for (int left = added; left > 0; ) {
                int amount = Math.min(Math.max(1, entry.getKey().getMaxStackSize()), left);
                dev.chatalyst.util.InventoryUtil.giveOrDrop(
                        staff, entry.getKey().asQuantity(amount));
                left -= amount;
            }
        }
        for (java.util.Map.Entry<ItemStack, Integer> entry : balance.entrySet()) {
            int taken = -entry.getValue();
            if (taken <= 0) continue;
            var leftover = staff.getInventory()
                    .removeItemAnySlot(entry.getKey().asQuantity(taken));
            for (ItemStack missing : leftover.values()) {
                module.plugin().getLogger().warning("Discarded edit for " + targetName
                        + ": " + missing.getAmount() + "x " + missing.getType() + " taken by "
                        + staff.getName() + " could not be reclaimed from their inventory.");
                module.audit(staff, "invsee edit dupe-risk", targetName + " "
                        + missing.getAmount() + "x " + missing.getType());
            }
        }
    }

    /** Multiconjunto item->cantidad (ignora aire); la clave normaliza el stack a 1. */
    private static java.util.Map<ItemStack, Integer> countItems(ItemStack[] items) {
        java.util.Map<ItemStack, Integer> out = new java.util.HashMap<>();
        if (items == null) return out;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            out.merge(item.asOne(), item.getAmount(), Integer::sum);
        }
        return out;
    }

    private static boolean unchanged(ItemStack[] original, ItemStack[] edited) {
        if (original == null || original.length != edited.length) return false;
        for (int i = 0; i < original.length; i++) {
            if (!java.util.Objects.equals(original[i], edited[i])) return false;
        }
        return true;
    }

    private static ItemStack clone(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = clone(items[i]);
        return copy;
    }
}
