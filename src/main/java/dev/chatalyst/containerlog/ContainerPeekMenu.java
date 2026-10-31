package dev.chatalyst.containerlog;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.ShulkerViewMenu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Vista de solo lectura del contenido de un contenedor del mundo, abierta desde el boton
 * <b>[Ver]</b> del spy y de {@code /containerlog}. El contenido se lee EN VIVO en la
 * region propietaria del bloque (Folia), asi que muestra lo que hay AHORA — no una foto
 * del momento en que se abrio. Las shulkers de dentro se abren con un click.
 */
public final class ContainerPeekMenu implements Menu {

    private final String label;
    private final ItemStack[] contents;
    private final int size;
    private final int closeSlot;

    private ContainerPeekMenu(String label, ItemStack[] contents) {
        this.label = label;
        this.contents = contents;
        int rows = Math.max(1, (contents.length + 8) / 9);
        // Una fila extra para el boton de cerrar, si cabe (limite del cliente: 6 filas).
        this.size = Math.min(6, rows + 1) * 9;
        this.closeSlot = size - 5;
    }

    /**
     * Lee el contenedor y abre la vista. Un ender chest no guarda nada en el bloque: su
     * contenido es el del JUGADOR, asi que en ese caso se muestra el suyo (si sigue
     * conectado). {@code targetName} es solo la etiqueta del titulo.
     */
    public static void open(JavaPlugin plugin, Player viewer, String playerName,
                            World world, int x, int y, int z) {
        if (!viewer.isOp() && !viewer.hasPermission(ContainerLogManager.PEEK_PERMISSION)) {
            viewer.sendMessage(Lang.get(viewer, "common.no-permission"));
            return;
        }
        Location location = new Location(world, x, y, z);
        FoliaTasks.region(plugin, location, () -> {
            Block block = world.getBlockAt(x, y, z);
            String label = ContainerLogManager.targetLabel(viewer, block.getType().name());
            if (block.getType() == Material.ENDER_CHEST) {
                openEnderChest(plugin, viewer, playerName, label);
                return;
            }
            if (!(block.getState() instanceof Container container)) {
                // Lo rompieron, o nunca fue un contenedor (mecanismo, crafteo...).
                FoliaTasks.entity(plugin, viewer,
                        v -> v.sendMessage(Lang.get(v, "containerlog.peek-gone")));
                return;
            }
            ItemStack[] snapshot = clone(container.getInventory().getContents());
            FoliaTasks.entity(plugin, viewer, v -> {
                if (!v.isOp() && !v.hasPermission(ContainerLogManager.PEEK_PERMISSION)) return;
                if (isEmpty(snapshot)) {
                    v.sendMessage(Lang.get(v, "containerlog.peek-empty", "target", label));
                    return;
                }
                Menu.open(v, new ContainerPeekMenu(label, snapshot));
            });
        });
    }

    // El ender chest vive en el jugador: se lee en SU region, no en la del bloque.
    private static void openEnderChest(JavaPlugin plugin, Player viewer, String playerName,
                                       String label) {
        Player owner = Bukkit.getPlayerExact(playerName);
        if (owner == null) {
            FoliaTasks.entity(plugin, viewer,
                    v -> v.sendMessage(Lang.get(v, "common.player-offline")));
            return;
        }
        FoliaTasks.entity(plugin, owner, target -> {
            ItemStack[] snapshot = clone(target.getEnderChest().getContents());
            FoliaTasks.entity(plugin, viewer, v -> {
                if (!v.isOp() && !v.hasPermission(ContainerLogManager.PEEK_PERMISSION)) return;
                if (isEmpty(snapshot)) {
                    v.sendMessage(Lang.get(v, "containerlog.peek-empty", "target", label));
                    return;
                }
                Menu.open(v, new ContainerPeekMenu(label, snapshot));
            });
        });
    }

    @Override
    public Component title() {
        return Lang.get("containerlog.peek-title", "target", MM.escape(label));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "containerlog.peek-title", "target", MM.escape(label));
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        for (int i = 0; i < Math.min(contents.length, size); i++) {
            inventory.setItem(i, contents[i]);
        }
        if (closeSlot >= contents.length && closeSlot < size) {
            inventory.setItem(closeSlot, dev.chatalyst.gui.MenuNavigation.close(viewer));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.isOp() && !player.hasPermission(ContainerLogManager.PEEK_PERMISSION)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == closeSlot && closeSlot >= contents.length) {
            player.closeInventory();
            return;
        }
        if (slot >= 0 && slot < contents.length && contents[slot] != null) {
            ShulkerViewMenu.tryOpen(player, label, contents[slot],
                    () -> Menu.open(player, this));
        }
    }

    private static boolean isEmpty(ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null) return false;
        }
        return true;
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null || items[i].getType().isAir() ? null : items[i].clone();
        }
        return copy;
    }
}
