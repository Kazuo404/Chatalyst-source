package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shows blocked mail senders and lets the player unblock them. */
public final class MailBlockedMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private int page;
    private final Map<Integer, MailManager.BlockEntry> slots = new HashMap<>();

    public MailBlockedMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose) {
        this(plugin, mail, compose, 0);
    }

    private MailBlockedMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose,
                            int page) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("mail.gui-blocked-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-blocked-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<MailManager.BlockEntry> blocked = mail.blocked(viewer.getUniqueId());
        int pages = Math.max(1, (blocked.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(blocked.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int i = start; i < end; i++) {
            int slot = layout[i - start];
            MailManager.BlockEntry entry = blocked.get(i);
            inv.setItem(slot, MailMenu.basic(Material.REDSTONE_BLOCK,
                    "<#FF4D6D>" + MM.escape(displayName(entry)),
                    List.of(Lang.raw(viewer, "mail.gui-blocked-lore"),
                            Lang.raw(viewer, "mail.gui-click-unblock"))));
            slots.put(slot, entry);
        }
        if (blocked.isEmpty()) {
            inv.setItem(22, MailMenu.basic(Material.IRON_BARS, Lang.raw(viewer, "mail.gui-no-blocked"),
                    List.of(Lang.raw(viewer, "mail.gui-no-blocked-lore"))));
        }
        inv.setItem(PREVIOUS_SLOT, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, 0));
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new MailBlockedMenu(plugin, mail, compose, page - 1));
            return;
        }
        if (slot == NEXT_SLOT) {
            int pages = Math.max(1,
                    (mail.blocked(player.getUniqueId()).size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) {
                Menu.open(player, new MailBlockedMenu(plugin, mail, compose, page + 1));
            }
            return;
        }
        MailManager.BlockEntry entry = slots.get(slot);
        if (entry == null) {
            return;
        }
        if (!mail.unblock(player.getUniqueId(), uuid(entry.playerId()), entry.playerName())) {
            player.sendMessage(Lang.get(player, "mail.storage-error"));
            Menu.open(player, new MailBlockedMenu(plugin, mail, compose, page));
            return;
        }
        player.sendMessage(Lang.get(player, "mail.unblocked-sender", "player", displayName(entry)));
        Menu.open(player, new MailBlockedMenu(plugin, mail, compose, page));
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String displayName(MailManager.BlockEntry entry) {
        if (entry.playerName() != null && !entry.playerName().isBlank()) {
            return entry.playerName();
        }
        return entry.playerId();
    }
}
