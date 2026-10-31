package dev.chatalyst.hologram;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Todos los carteles del servidor: clic para ir, clic derecho para editarlo. */
final class HologramListMenu implements Menu {

    private static final int PER_PAGE = 45;
    private static final int PREVIOUS = 45;
    // Barra simetrica: flecha | crear | cerrar (centro) | cerca de ti | flecha.
    private static final int CREATE = 47;
    private static final int CLOSE = 49;
    private static final int NEARBY = 51;
    private static final int NEXT = 53;

    private final HologramModule module;
    private final int page;
    private List<Hologram> shown = List.of();

    HologramListMenu(HologramModule module, int page) {
        this.module = module;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("holograms.menu-list-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "holograms.menu-list-title");
    }

    @Override
    public int size() {
        return 54;
    }

    private int pages() {
        HologramService service = module.service();
        int total = service == null ? 0 : service.all().size();
        return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        HologramService service = module.service();
        if (service == null) return;
        List<Hologram> all = List.copyOf(service.all());
        int current = Math.min(page, pages() - 1);
        int from = current * PER_PAGE;
        shown = all.subList(Math.min(from, all.size()), Math.min(all.size(), from + PER_PAGE));
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(shown.size(), 0);
        for (int i = 0; i < shown.size(); i++) {
            inventory.setItem(layout[i], icon(viewer, service, shown.get(i)));
        }
        if (all.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.PAPER,
                    Lang.raw(viewer, "holograms.menu-list-empty"),
                    Lang.rawList(viewer, "holograms.menu-list-empty-lore", "label", module.label())));
        }
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, current + 1, pages()));
        inventory.setItem(CREATE, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "holograms.menu-create"),
                Lang.rawList(viewer, "holograms.menu-create-lore")));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(NEARBY, MenuItems.item(Material.COMPASS,
                Lang.raw(viewer, "holograms.menu-nearby"),
                Lang.rawList(viewer, "holograms.menu-nearby-lore")));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, current + 1, pages()));
    }

    private ItemStack icon(Player viewer, HologramService service, Hologram hologram) {
        Hologram.Position p = hologram.position();
        Material material = Material.OAK_HANGING_SIGN;
        for (HoloLine line : hologram.lines()) {
            if (line.isImage()) {
                material = Material.PAINTING;
                break;
            }
            if (!line.isText()) {
                Material item = HologramInstance.item(line.content()).getType();
                if (item != Material.BARRIER) material = item;
                break;
            }
        }
        List<String> lore = new ArrayList<>(Lang.rawList(viewer, "holograms.menu-list-entry-lore",
                "world", MM.escape(p.world()), "x", (int) Math.floor(p.x()),
                "y", (int) Math.floor(p.y()), "z", (int) Math.floor(p.z()),
                "lines", hologram.lines().size(),
                "preview", MM.escape(HologramCommand.preview(hologram)),
                "state", Lang.raw(viewer, service.spawned(hologram.id())
                        ? "holograms.info-spawned" : "holograms.info-not-spawned")));
        return MenuItems.item(material, "<#B56CFF>" + hologram.id(), lore);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        HologramService service = module.service();
        if (service == null || !StaffAccess.has(player, HologramCommand.PERMISSION)) {
            player.closeInventory();
            return;
        }
        int current = Math.min(page, pages() - 1);
        if (slot == PREVIOUS && current > 0) {
            Menu.open(player, new HologramListMenu(module, current - 1));
        } else if (slot == NEXT && current + 1 < pages()) {
            Menu.open(player, new HologramListMenu(module, current + 1));
        } else if (slot == CLOSE) {
            player.closeInventory();
        } else if (slot == CREATE) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "holograms.menu-create-hint", "label", module.label())
                    .clickEvent(ClickEvent.suggestCommand("/" + module.label() + " crear ")));
        } else if (slot == NEARBY) {
            player.closeInventory();
            player.performCommand("chatalyst:" + module.label() + " cerca");
        } else if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) >= 0) {
            Hologram hologram = service.get(shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)).id());
            if (hologram == null) return;
            if (click.isRightClick()) {
                Menu.open(player, new HologramEditorMenu(module, hologram.id()));
            } else {
                player.closeInventory();
                HologramCommand.teleport(player, hologram, service);
            }
        }
    }
}
