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

/** Read-only ender chest review for one online player, mirrored from its region. */
public final class TargetEnderChestMenu implements Menu {

    private static final int EXIT = 31;
    /** Abierto por comando: el boton de salida solo cierra, no hay menu padre. */
    private static final int NO_PARENT = -1;

    private final StaffModerationModule module;
    private final UUID targetId;
    private final String targetName;
    private final ItemStack[] contents;
    private final int listPage;

    private TargetEnderChestMenu(StaffModerationModule module, UUID targetId,
                                 String targetName, ItemStack[] contents, int listPage) {
        this.module = module;
        this.targetId = targetId;
        this.targetName = targetName;
        this.contents = contents;
        this.listPage = listPage;
    }

    /** Flujo del comando /echest: sin menu padre al que volver. */
    public static void open(StaffModerationModule module, Player viewer, UUID targetId) {
        open(module, viewer, targetId, NO_PARENT);
    }

    /** Flujo del menu de gestion: la flecha vuelve a PlayerManageMenu en {@code listPage}. */
    public static void open(StaffModerationModule module, Player viewer, UUID targetId,
                            int listPage) {
        if (!StaffAccess.has(viewer, "chatalyst.command.echest")) {
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
            ItemStack[] chest = clone(target.getEnderChest().getContents());
            FoliaTasks.entity(module.plugin(), viewer, current -> {
                if (vanished && !StaffAccess.canSeeVanished(current)) {
                    current.sendMessage(Lang.get(current, "common.player-offline"));
                    return;
                }
                Menu.open(current, new TargetEnderChestMenu(module, targetId, name,
                        chest, listPage));
            });
        }, missing);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.echest-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.echest-title", "player",
                MM.escape(targetName));
    }

    @Override
    public int size() {
        return 36;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        for (int i = 0; i < Math.min(27, contents.length); i++) {
            inventory.setItem(i, clone(contents[i]));
        }
        inventory.setItem(EXIT, listPage == NO_PARENT
                ? dev.chatalyst.gui.MenuNavigation.close(viewer)
                : dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.command.echest")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == EXIT) {
            if (listPage == NO_PARENT) {
                player.closeInventory();
            } else {
                PlayerManageMenu.open(module.plugin(), player, targetId, listPage);
            }
            return;
        }
        // Shulkers dentro del ender chest: un click abre su contenido en solo lectura.
        if (slot >= 0 && slot < Math.min(27, contents.length) && contents[slot] != null) {
            dev.chatalyst.gui.ShulkerViewMenu.tryOpen(player, targetName, contents[slot],
                    () -> Menu.open(player, this));
        }
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
