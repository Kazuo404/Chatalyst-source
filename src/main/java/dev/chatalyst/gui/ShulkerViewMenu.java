package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.List;

/**
 * Vista de solo lectura del contenido de una shulker box encontrada dentro de otra vista
 * (invsee, ender chest, inventario compartido en el chat). La flecha vuelve a la vista
 * padre; los items no se pueden tocar (MenuListener cancela todo).
 */
public final class ShulkerViewMenu implements Menu {

    private static final int BACK = 31;

    private final String ownerName;
    private final ItemStack[] contents;
    private final Runnable back;

    private ShulkerViewMenu(String ownerName, ItemStack[] contents, Runnable back) {
        this.ownerName = ownerName;
        this.contents = contents;
        this.back = back;
    }

    /**
     * Contenido de {@code item} si es una shulker box (clones), o null si no lo es. Sirve
     * a la vez de deteccion y de snapshot: el resultado esta detachado del item original.
     */
    public static ItemStack[] contentsOf(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("SHULKER_BOX")) return null;
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) return null;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return null;
        ItemStack[] raw = box.getInventory().getContents();
        ItemStack[] copy = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            copy[i] = raw[i] == null || raw[i].getType().isAir() ? null : raw[i].clone();
        }
        return copy;
    }

    /**
     * Si el item clicado es una shulker, abre su vista y devuelve true; si no, false.
     * {@code back} reabre la vista padre al pulsar la flecha.
     */
    public static boolean tryOpen(Player viewer, String ownerName, ItemStack clicked,
                                  Runnable back) {
        ItemStack[] inside = contentsOf(clicked);
        if (inside == null) return false;
        Menu.open(viewer, new ShulkerViewMenu(ownerName, inside, back));
        return true;
    }

    @Override
    public Component title() {
        return Lang.get("gui.shulker-title", "player", dev.chatalyst.util.MM.escape(ownerName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "gui.shulker-title", "player",
                dev.chatalyst.util.MM.escape(ownerName));
    }

    @Override
    public int size() {
        return 36;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        for (int i = 0; i < Math.min(27, contents.length); i++) {
            inventory.setItem(i, contents[i]);
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK && back != null) back.run();
    }
}
