package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /chestview: mirar cualquier contenedor del mundo SIN poder tocarlo.
 *
 * <p>Con el modo activo, el clic derecho sobre un cofre, barril, horno, tolva, shulker,
 * vagoneta con cofre o animal con alforjas no lo abre: se cancela antes de que el bloque se
 * entere y se muestra una COPIA de solo lectura ({@link SilentContainerView}). Sin tapa, sin
 * sonido, sin "en uso", y sin forma de sacar nada — exactamente lo que hace el vanish, pero sin
 * tener que esconderse para mirar.
 *
 * <p>Corre en {@code LOWEST} y SIN {@code ignoreCancelled}: lo normal es querer mirar un cofre
 * DENTRO de una proteccion (una claim, una region de WorldGuard), y esas protecciones cancelan
 * el clic antes. Mirar no deja rastro en el mundo, asi que la proteccion no se esta saltando:
 * nadie ha abierto nada. Lo que si queda es la auditoria: quien miro que contenedor y donde.
 *
 * <p>Permiso: {@code chatalyst.command.chestview}. El modo no se guarda: al salir se apaga.
 */
final class ContainerInspectMode implements Listener, CommandExecutor, TabCompleter {

    static final String PERMISSION = "chatalyst.command.chestview";

    private final StaffModerationModule module;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    ContainerInspectMode(StaffModerationModule module) {
        this.module = module;
    }

    boolean isActive(Player player) {
        return active.contains(player.getUniqueId());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)
                || !StaffCommandSupport.require(sender, PERMISSION)) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        boolean next = args.length > 0
                ? switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                    case "on", "activar" -> true;
                    case "off", "desactivar" -> false;
                    default -> !isActive(player);
                }
                : !isActive(player);
        if (next) active.add(player.getUniqueId());
        else active.remove(player.getUniqueId());
        player.sendMessage(Lang.get(player, next
                ? "staff-moderation.chestview-on" : "staff-moderation.chestview-off"));
        module.audit(player, next ? "chestview on" : "chestview off", player.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? StaffCommandSupport.prefix(args[0], List.of("on", "off"))
                : List.of();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !inspecting(player)) return;
        // Una herramienta del kit de staff en la mano manda: la sirve StaffModeListener.
        if (module.staffMode().action(event.getItem()) != null) return;
        Block block = event.getClickedBlock();
        Inventory container = VanishObserverListener.containerOf(block, player);
        if (container == null) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
        SilentContainerView.open(player, container);
        module.audit(player, "chestview", block.getType().name().toLowerCase(java.util.Locale.ROOT)
                + " " + block.getWorld().getName() + " " + block.getX() + "," + block.getY()
                + "," + block.getZ());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || !inspecting(player)) return;
        if (module.staffMode().action(player.getInventory().getItemInMainHand()) != null) return;
        Inventory container = VanishObserverListener.containerOf(event.getRightClicked());
        if (container == null) return;
        event.setCancelled(true);
        SilentContainerView.open(player, container);
        var at = event.getRightClicked().getLocation();
        module.audit(player, "chestview", event.getRightClicked().getType().name()
                .toLowerCase(java.util.Locale.ROOT) + " " + at.getWorld().getName() + " "
                + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
    }

    /** Activo Y con el permiso todavia: si se lo quitan, el modo se apaga solo. */
    private boolean inspecting(Player player) {
        if (!isActive(player)) return false;
        if (StaffAccess.has(player, PERMISSION) && module.enabled()) return true;
        active.remove(player.getUniqueId());
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }
}
