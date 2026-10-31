package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paginated live staff directory reachable from the administrative menu. */
public final class StaffDirectoryMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private final StaffDirectoryManager directory;
    private final int page;
    private final Map<Integer, StaffDirectoryManager.Entry> slots = new HashMap<>();

    public StaffDirectoryMenu(StaffDirectoryManager directory, int page) {
        this.directory = directory;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("staff-directory.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-directory.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        slots.clear();
        List<StaffDirectoryManager.Entry> staff = directory.snapshot();
        int pages = Math.max(1, (staff.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.min(page, pages - 1);
        int start = safePage * PAGE_SIZE;
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                Math.max(0, Math.min(staff.size() - start, PAGE_SIZE)), 0);
        for (int index = start; index < Math.min(staff.size(), start + PAGE_SIZE); index++) {
            int slot = layout[index - start];
            StaffDirectoryManager.Entry entry = staff.get(index);
            slots.put(slot, entry);
            inventory.setItem(slot, MenuItems.item(Material.PLAYER_HEAD,
                "<#FBF2FF>" + MM.escape(entry.name()), List.of(
                Lang.raw(viewer, "staff-directory.tier", "tier", MM.escape(entry.tier())),
                Lang.raw(viewer, "staff-directory.server", "server", MM.escape(entry.server())),
                Lang.raw(viewer, "staff-directory.world", "world", MM.escape(entry.world())),
                Lang.raw(viewer, "staff-directory.click"))));
        }
        // Barra simetrica: flecha - actualizar - flecha (el n. de pagina va en las flechas).
        inventory.setItem(45, MenuNavigation.previousOrFirst(viewer, safePage + 1, pages));
        inventory.setItem(49, MenuNavigation.refresh(viewer));
        inventory.setItem(53, MenuNavigation.nextOrLast(viewer, safePage + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.admin.staff.directory")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        StaffDirectoryManager.Entry target = slots.get(slot);
        if (target != null) {
            player.closeInventory();
            directory.teleport(player, target);
            return;
        }
        if (slot == 45 && page > 0) Menu.open(player, new StaffDirectoryMenu(directory, page - 1));
        if (slot == 49) Menu.open(player, new StaffDirectoryMenu(directory, page));
        if (slot == 53 && directory.snapshot().size() > (page + 1) * PAGE_SIZE) {
            Menu.open(player, new StaffDirectoryMenu(directory, page + 1));
        }
    }
}
