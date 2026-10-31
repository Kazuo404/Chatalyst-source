package dev.chatalyst.staff;

import dev.chatalyst.command.PlayerManageMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
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

import java.util.List;
import java.util.UUID;

/** Read-only, deliberately spaced inventory review for one online player. */
public final class TargetInventoryMenu implements Menu {

    private static final int BACK = 49;
    private static final int[] ARMOR = {10, 12, 14, 16};
    private final StaffModerationModule module;
    private final UUID targetId;
    private final String targetName;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final int listPage;

    private TargetInventoryMenu(StaffModerationModule module, UUID targetId, String targetName,
                                ItemStack[] contents, ItemStack[] armor, ItemStack offhand,
                                int listPage) {
        this.module = module;
        this.targetId = targetId;
        this.targetName = targetName;
        this.contents = contents;
        this.armor = armor;
        this.offhand = offhand;
        this.listPage = listPage;
    }

    public static void open(StaffModerationModule module, Player viewer, UUID targetId,
                            int listPage) {
        if (!StaffAccess.has(viewer, "chatalyst.command.invsee")) {
            viewer.sendMessage(Lang.get(viewer, "common.no-permission"));
            return;
        }
        Player online = Bukkit.getPlayer(targetId);
        Runnable missing = () -> FoliaTasks.entity(module.plugin(), viewer,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        if (online == null) {
            missing.run();
            return;
        }
        FoliaTasks.entity(module.plugin(), online, target -> {
            String name = target.getName();
            boolean vanished = dev.chatalyst.util.Vanish.isVanished(target);
            ItemStack[] storage = clone(target.getInventory().getStorageContents());
            ItemStack[] worn = clone(target.getInventory().getArmorContents());
            ItemStack hand = clone(target.getInventory().getItemInOffHand());
            FoliaTasks.entity(module.plugin(), viewer, current -> {
                if (vanished && !StaffAccess.canSeeVanished(current)) {
                    current.sendMessage(Lang.get(current, "common.player-offline"));
                    return;
                }
                Menu.open(current, new TargetInventoryMenu(module, targetId, name,
                        storage, worn, hand, listPage));
            });
        }, missing);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.inventory-title", "player",
                MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.inventory-title", "player",
                MM.escape(targetName));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        inventory.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        for (int i = 0; i < Math.min(9, contents.length); i++) {
            inventory.setItem(i, clone(contents[i]));
        }
        for (int i = 9; i < Math.min(36, contents.length); i++) {
            inventory.setItem(18 + (i - 9), clone(contents[i]));
        }
        for (int i = 0; i < Math.min(ARMOR.length, armor.length); i++) {
            inventory.setItem(ARMOR[i], clone(armor[i]));
        }
        inventory.setItem(13, clone(offhand));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.command.invsee")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            PlayerManageMenu.open(module.plugin(), player, targetId, listPage);
            return;
        }
        // Shulkers dentro del inventario: un click abre su contenido en solo lectura.
        ItemStack clicked = itemAt(slot);
        if (clicked != null) {
            dev.chatalyst.gui.ShulkerViewMenu.tryOpen(player, targetName, clicked,
                    () -> Menu.open(player, this));
        }
    }

    // Slot de la GUI -> item del snapshot (mismo mapeo que build()).
    private ItemStack itemAt(int slot) {
        if (slot >= 0 && slot < 9 && slot < contents.length) return contents[slot];
        if (slot >= 18 && slot < 45 && slot - 9 < contents.length) return contents[slot - 9];
        for (int i = 0; i < ARMOR.length; i++) {
            if (slot == ARMOR[i] && i < armor.length) return armor[i];
        }
        if (slot == 13) return offhand;
        return null;
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
