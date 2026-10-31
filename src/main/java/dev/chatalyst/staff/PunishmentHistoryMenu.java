package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Historial de castigos de un jugador como menu: cada entrada de {@link HistoryLog} con su
 * tipo, quien la aplico, hace cuanto y el motivo. La version visual de la cola de /check,
 * pero completa (el chat solo muestra las ultimas 3). Solo lectura; mas reciente primero.
 */
public final class PunishmentHistoryMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final StaffModerationModule module;
    private final UUID targetId;
    private final String targetName;
    private final Consumer<Player> back;
    private int page;

    public PunishmentHistoryMenu(StaffModerationModule module, UUID targetId, String targetName,
                                 Consumer<Player> back) {
        this(module, targetId, targetName, back, 0);
    }

    private PunishmentHistoryMenu(StaffModerationModule module, UUID targetId, String targetName,
                                  Consumer<Player> back, int page) {
        this.module = module;
        this.targetId = targetId;
        this.targetName = targetName;
        this.back = back;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.history-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.history-title",
                "player", MM.escape(targetName));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<HistoryLog.Entry> history = entries();
        if (history.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.BOOK,
                    Lang.raw(viewer, "staff-moderation.history-empty"), List.of()));
        }
        int pages = Math.max(1, (history.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        long now = System.currentTimeMillis();
        int shown = Math.max(0, Math.min(history.size() - start, PAGE_SIZE));
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(shown, 0);
        for (int i = 0; i < shown; i++) {
            inventory.setItem(layout[i], entryItem(viewer, history.get(start + i), now));
        }
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.command.check")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            if (back != null) back.accept(player);
            else player.closeInventory();
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new PunishmentHistoryMenu(module, targetId, targetName, back, page - 1));
            return;
        }
        int pages = Math.max(1, (entries().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT && page + 1 < pages) {
            Menu.open(player, new PunishmentHistoryMenu(module, targetId, targetName, back, page + 1));
        }
    }

    // Mas reciente primero: lo ultimo que hizo es lo primero que el staff quiere ver.
    private List<HistoryLog.Entry> entries() {
        List<HistoryLog.Entry> out = new ArrayList<>(module.history().list(targetId));
        java.util.Collections.reverse(out);
        return out;
    }

    private ItemStack entryItem(Player viewer, HistoryLog.Entry entry, long now) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "staff-moderation.history-entry-when",
                "duration", TimeUtil.formatElapsed(now - entry.time())));
        lore.add(Lang.raw(viewer, "staff-moderation.history-entry-staff",
                "staff", MM.escape(entry.staff())));
        if (!entry.detail().isBlank()) {
            lore.add(Lang.raw(viewer, "staff-moderation.history-entry-reason", "reason",
                    MM.escape(module.formatter().plainReason(viewer, entry.detail()))));
        }
        return MenuItems.item(material(entry.type()),
                StaffToolsCommand.historyType(viewer, entry.type()), lore);
    }

    // Mismo lenguaje visual que los botones de los menus de moderacion.
    private static Material material(String type) {
        return switch (type) {
            case "ban", "tempban", "escalation-ban" -> Material.RED_CONCRETE;
            case "banip", "ipban-alt" -> Material.CHAIN;
            case "kick" -> Material.YELLOW_CONCRETE;
            case "freeze", "freeze-ban" -> Material.PACKED_ICE;
            case "mute", "softmute" -> Material.ORANGE_DYE;
            case "warn" -> Material.YELLOW_DYE;
            default -> Material.PAPER;
        };
    }
}
