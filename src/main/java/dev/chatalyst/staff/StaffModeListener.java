package dev.chatalyst.staff;

import dev.chatalyst.command.PlayerManageMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.RepeatThrottle;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Handles staff kit actions and prevents temporary staff items from leaking or being moved. */
public final class StaffModeListener implements Listener {

    private final StaffModerationModule module;
    private final StaffModeManager mode;
    private final RepeatThrottle notices = new RepeatThrottle();

    public StaffModeListener(StaffModerationModule module, StaffModeManager mode) {
        this.module = module;
        this.mode = mode;
    }

    // LOWEST y sin ignoreCancelled a proposito: la proteccion de spawn o WorldGuard cancela el
    // interact antes que nosotros, y con ignoreCancelled el kit de staff quedaba muerto cerca del
    // spawn. Corremos primero y procesamos aunque el evento ya venga cancelado.
    //
    // Solo se cancela el clic cuando lo que hay en la mano ES una herramienta del kit. Antes se
    // cancelaba SIEMPRE, con lo que el modo staff apagaba el mundo entero: ni un cofre, ni un
    // cartel de la tienda, ni el NPC de los kits respondian mientras estabas dentro. Cancelarlo
    // todo tampoco protegia nada — para eso estan onBreak/onPlace, que es lo unico que el modo
    // staff necesita impedir de verdad.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!mode.isActive(player) || event.getHand() != EquipmentSlot.HAND) return;
        String action = mode.action(event.getItem());
        if (action == null) return;
        event.setCancelled(true);
        switch (action) {
            case StaffModeManager.DASHBOARD -> StaffDashboardMenu.open(module, player);
            case StaffModeManager.VANISH -> {
                if (!StaffAccess.has(player, "chatalyst.command.vanish")) return;
                module.vanish().toggle(player);
                mode.giveKit(player);
                player.sendMessage(Lang.get(player, module.vanish().isOwned(player)
                        ? "staff-moderation.vanish-on" : "staff-moderation.vanish-off"));
            }
            case StaffModeManager.EXIT -> {
                if (mode.exit(player)) {
                    player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-off"));
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        Player staff = event.getPlayer();
        if (!mode.isActive(staff) || event.getHand() != EquipmentSlot.HAND) return;
        String action = mode.action(staff.getInventory().getItemInMainHand());
        if (action == null) return;
        // Con una herramienta del kit en la mano, NINGUNA entidad la recibe: un marco o un
        // zorro se quedaban la brujula o la estrella del kit, que acababa en manos de cualquiera
        // (y faltaba al restaurar el inventario).
        event.setCancelled(true);
        if (!(event.getRightClicked() instanceof Player target)) return;
        if (StaffModeManager.INSPECT.equals(action)) {
            PlayerManageMenu.open(module.plugin(), staff, target.getUniqueId(), 0);
        } else if (StaffModeManager.FREEZE.equals(action)) {
            // Namespaced: siempre el freeze DE CHATALYST, gane quien gane la etiqueta.
            String command = module.freezes().isFrozen(target.getUniqueId())
                    ? "chatalyst:unfreeze " : "chatalyst:freeze ";
            staff.performCommand(command + target.getName());
        }
    }

    /**
     * Lo que el modo staff protege es EL KIT, no el inventario.
     *
     * <p>Cancelar todo click fuera de un menu de Chatalyst dejaba el modo staff sin poder usar
     * ninguna GUI ajena: la tienda, los kits, cualquier menu de otro plugin se abria y no
     * respondia a nada. Y no hacia falta — lo unico irrecuperable es que una herramienta del
     * kit salga del inventario temporal (se perderia al restaurar el inventario real). Asi que
     * la regla es la misma en todas partes, incluida la sesion de edicion de invsee/echest:
     * fluye todo salvo lo que toque una pieza del kit.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // MenuListener owns Chatalyst GUI clicks. This listener only protects the normal
        // player inventory, so staff tools never swallow dashboard button actions.
        if (event.getInventory().getHolder() instanceof MenuHolder) return;
        if (!(event.getWhoClicked() instanceof Player player) || !mode.isActive(player)) return;
        if (touchesKitItem(event, player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) return;
        if (!(event.getWhoClicked() instanceof Player player) || !mode.isActive(player)) return;
        // Arrastrar reparte lo que hay EN EL CURSOR: si eso no es del kit, no puede sacarlo.
        if (mode.action(event.getOldCursor()) != null) event.setCancelled(true);
    }

    /** True si el click involucra un item del kit (slot clicado, cursor, o swap de hotbar). */
    private boolean touchesKitItem(InventoryClickEvent event, Player player) {
        if (mode.action(event.getCurrentItem()) != null) return true;
        if (mode.action(event.getCursor()) != null) return true;
        if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                && event.getHotbarButton() >= 0
                && mode.action(player.getInventory().getItem(event.getHotbarButton())) != null) {
            return true;
        }
        return event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && mode.action(player.getInventory().getItemInOffHand()) != null;
    }

    /**
     * El soporte de armadura tiene su propio evento (no hereda los manejadores del de
     * entidades): clic derecho con una pieza del kit se la colgaba encima.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (!mode.isActive(event.getPlayer())) return;
        if (mode.action(event.getPlayerItem()) != null
                || mode.action(event.getArmorStandItem()) != null) {
            event.setCancelled(true);
        }
    }

    /** Con el bypass del vanish el modo staff tampoco frena el mundo (el kit sigue protegido). */
    private static boolean bypass(Player player) {
        return StaffAccess.has(player, VanishObserverListener.BYPASS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!mode.isActive(event.getPlayer())) return;
        // Un item del kit nunca sale del inventario temporal: se perderia al restaurar.
        if (bypass(event.getPlayer())
                && mode.action(event.getItemDrop().getItemStack()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!mode.isActive(event.getPlayer())) return;
        if (mode.action(event.getMainHandItem()) != null
                || mode.action(event.getOffHandItem()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * El modo staff mira, no construye.
     *
     * <p>Antes esto lo cubria de rebote el cancelar TODO clic derecho/izquierdo, que era
     * tambien lo que impedia abrir un cofre o usar la tienda. Ahora el mundo responde con
     * normalidad y lo unico que se sigue impidiendo es lo que deja marca: romper y poner.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (mode.isActive(event.getPlayer()) && !bypass(event.getPlayer())) {
            refuse(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (mode.isActive(event.getPlayer()) && !bypass(event.getPlayer())) {
            refuse(event, event.getPlayer());
        }
    }

    /** Cancela y avisa una vez por ventana: minar un bloque dispara el evento sin parar. */
    private void refuse(Cancellable event, Player player) {
        event.setCancelled(true);
        if (notices.register(player.getUniqueId().toString(), 1_500L) >= 0) {
            player.sendActionBar(Lang.get(player, "staff-moderation.staff-mode-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && mode.isActive(player)
                && !bypass(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && mode.isActive(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageOther(EntityDamageByEntityEvent event) {
        Player player = StaffActor.player(event.getDamager());
        if (player != null && mode.isActive(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * El daño ya se cancela, pero se puede morir igual: /kill, otro plugin que pone la vida a 0,
     * el void. Sin esto el kit caia al suelo (herramientas de staff en manos de cualquiera) junto
     * con lo que se hubiera sacado de un invsee. En modo staff no se suelta nada: el inventario
     * real esta a salvo en el snapshot y el kit se queda puesto.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (!mode.isActive(event.getPlayer())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (mode.isActive(event.getPlayer())) mode.exit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(module.plugin(), task -> {
            if (player.isOnline()) mode.restoreStale(player);
        }, null, 1L);
    }
}
