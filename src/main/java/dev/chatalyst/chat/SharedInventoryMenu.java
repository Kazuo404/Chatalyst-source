package dev.chatalyst.chat;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.ShulkerViewMenu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Vista publica de solo lectura del inventario o ender chest que un jugador compartio en el
 * chat con [inv] / [ec]. Mismo layout que la revision de staff, pero sin dependencia del
 * modulo de moderacion: cualquier jugador puede abrirla mientras el permiso temporal del
 * dueno siga vigente. Las shulkers dentro se abren con un click.
 */
public final class SharedInventoryMenu implements Menu {

    private static final int CLOSE_INV = 49;
    private static final int CLOSE_EC = 31;
    private static final int[] ARMOR = {10, 12, 14, 16};
    private static final int OFFHAND = 13;

    private final InventoryShareManager.Kind kind;
    private final String ownerName;
    private final ItemStack[] contents;   // storage (36) o ender chest (27)
    private final ItemStack[] armor;      // solo para INVENTORY
    private final ItemStack offhand;      // solo para INVENTORY

    private SharedInventoryMenu(InventoryShareManager.Kind kind, String ownerName,
                                ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
        this.kind = kind;
        this.ownerName = ownerName;
        this.contents = contents;
        this.armor = armor;
        this.offhand = offhand;
    }

    /**
     * Snapshot en la region del dueno (Folia-safe) y apertura en la del viewer. El permiso
     * temporal se valida ANTES de llegar aqui (ChatalystCommand).
     */
    public static void open(JavaPlugin plugin, Player viewer, UUID ownerId,
                            InventoryShareManager.Kind kind) {
        Player owner = Bukkit.getPlayer(ownerId);
        Runnable missing = () -> FoliaTasks.entity(plugin, viewer,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        if (owner == null) {
            missing.run();
            return;
        }
        FoliaTasks.entity(plugin, owner, target -> {
            // Nombre MOSTRADO (apodo si tiene): con /anonymous o /nick el titulo de la
            // vista no debe revelar el nombre real del dueno.
            String name = MM.strip(dev.chatalyst.nick.NickManager.shownName(
                    target.getUniqueId().toString(), target.getName()));
            ItemStack[] storage;
            ItemStack[] worn = null;
            ItemStack hand = null;
            if (kind == InventoryShareManager.Kind.ENDER_CHEST) {
                storage = clone(target.getEnderChest().getContents());
            } else {
                storage = clone(target.getInventory().getStorageContents());
                worn = clone(target.getInventory().getArmorContents());
                hand = clone(target.getInventory().getItemInOffHand());
            }
            ItemStack[] finalWorn = worn;
            ItemStack finalHand = hand;
            FoliaTasks.entity(plugin, viewer, current -> Menu.open(current,
                    new SharedInventoryMenu(kind, name, storage, finalWorn, finalHand)));
        }, missing);
    }

    @Override
    public Component title() {
        return Lang.get(titleKey(), "player", MM.escape(ownerName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey(), "player", MM.escape(ownerName));
    }

    private String titleKey() {
        return kind == InventoryShareManager.Kind.ENDER_CHEST
                ? "chat.share-ec-title" : "chat.share-inv-title";
    }

    @Override
    public int size() {
        return kind == InventoryShareManager.Kind.ENDER_CHEST ? 36 : 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        if (kind == InventoryShareManager.Kind.ENDER_CHEST) {
            for (int i = 0; i < Math.min(27, contents.length); i++) {
                inventory.setItem(i, contents[i]);
            }
            inventory.setItem(CLOSE_EC, closeButton(viewer));
            return;
        }
        // Mismo layout que la revision de staff: hotbar arriba, almacen abajo, armadura
        // y segunda mano espaciadas en la fila intermedia.
        for (int i = 0; i < Math.min(9, contents.length); i++) {
            inventory.setItem(i, contents[i]);
        }
        for (int i = 9; i < Math.min(36, contents.length); i++) {
            inventory.setItem(18 + (i - 9), contents[i]);
        }
        if (armor != null) {
            for (int i = 0; i < Math.min(ARMOR.length, armor.length); i++) {
                inventory.setItem(ARMOR[i], armor[i]);
            }
        }
        inventory.setItem(OFFHAND, offhand);
        inventory.setItem(CLOSE_INV, closeButton(viewer));
    }

    private ItemStack closeButton(Player viewer) {
        return dev.chatalyst.gui.MenuNavigation.close(viewer);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        int close = kind == InventoryShareManager.Kind.ENDER_CHEST ? CLOSE_EC : CLOSE_INV;
        if (slot == close) {
            player.closeInventory();
            return;
        }
        ItemStack clicked = itemAt(slot);
        if (clicked != null) {
            ShulkerViewMenu.tryOpen(player, ownerName, clicked,
                    () -> Menu.open(player, this));
        }
    }

    private ItemStack itemAt(int slot) {
        if (kind == InventoryShareManager.Kind.ENDER_CHEST) {
            return slot >= 0 && slot < Math.min(27, contents.length) ? contents[slot] : null;
        }
        if (slot >= 0 && slot < 9) return contents[slot];
        if (slot >= 18 && slot < 45 && slot - 9 < contents.length) return contents[slot - 9];
        for (int i = 0; i < ARMOR.length; i++) {
            if (slot == ARMOR[i] && armor != null && i < armor.length) return armor[i];
        }
        if (slot == OFFHAND) return offhand;
        return null;
    }

    private static ItemStack[] clone(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null || items[i].getType().isAir() ? null : items[i].clone();
        }
        return copy;
    }

    private static ItemStack clone(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
