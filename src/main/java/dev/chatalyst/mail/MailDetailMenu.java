package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Single mail view with reply/delete actions. */
public final class MailDetailMenu implements Menu {

    // Simetria: correo arriba al centro (4), acciones espejadas 11/13/15, volver abajo (22).
    private static final int MESSAGE_SLOT = 4;
    private static final int REPLY_SLOT = 11;
    private static final int BLOCK_SLOT = 13;
    private static final int DELETE_SLOT = 15;
    private static final int BACK_SLOT = 22;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private final String entryId;
    private final int index;
    private final int page;
    private final MailFolder folder;

    public MailDetailMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose,
                          MailManager.MailEntry entry, int index, int page, MailFolder folder) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.entryId = entry.id();
        this.index = index;
        this.page = page;
        this.folder = folder != null ? folder : MailFolder.INBOX;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("mail.gui-message-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-message-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        MailManager.MailEntry entry = entry(viewer);
        if (entry == null) {
            // Estado vacio: aviso en el centro exacto (13) y volver abajo al centro.
            inv.setItem(13, MailMenu.basic(Material.BARRIER, Lang.raw(viewer, "mail.gui-not-found"),
                    List.of(Lang.raw(viewer, "mail.gui-not-found-lore"))));
            inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
            return;
        }
        String shown = MailManager.shownFrom(entry);
        inv.setItem(REPLY_SLOT, MailMenu.basic(Material.WRITABLE_BOOK, Lang.raw(viewer, "mail.gui-reply"),
                List.of(Lang.raw(viewer, "mail.gui-reply-lore", "from", shown),
                        Lang.raw(viewer, "mail.gui-click-write"))));
        inv.setItem(MESSAGE_SLOT, messageItem(viewer, entry));
        inv.setItem(DELETE_SLOT, MailMenu.basic(Material.BARRIER, Lang.raw(viewer, "mail.gui-delete"),
                List.of(Lang.raw(viewer, "mail.gui-delete-lore"), Lang.raw(viewer, "mail.gui-click-delete"))));
        inv.setItem(BLOCK_SLOT, MailMenu.basic(Material.REDSTONE_BLOCK, Lang.raw(viewer, "mail.gui-block"),
                List.of(Lang.raw(viewer, "mail.gui-block-lore", "from", shown),
                        Lang.raw(viewer, "mail.gui-click-block"))));
        inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        MailManager.MailEntry entry = entry(player);
        if (entry == null) {
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        if (slot == REPLY_SLOT) {
            MailMenu.startReply(player, compose, entry);
            return;
        }
        if (slot == DELETE_SLOT) {
            boolean deleted = folder == MailFolder.SPAM
                    ? mail.deleteSpam(player.getUniqueId(), entry)
                    : mail.delete(player.getUniqueId(), entry);
            player.sendMessage(Lang.get(player,
                    deleted ? "mail.deleted" : "mail.storage-error"));
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
            return;
        }
        if (slot == BLOCK_SLOT) {
            MailManager.BlockResult result = mail.blockResult(
                    player.getUniqueId(), MailMenu.senderId(entry), entry.from());
            String key = switch (result) {
                case ADDED -> "mail.blocked-sender";
                case ALREADY_BLOCKED -> "mail.blocked-already";
                case INVALID, STORAGE_FAILED -> "mail.storage-error";
            };
            player.sendMessage(Lang.get(player, key, "player", MailManager.shownFrom(entry)));
            Menu.open(player, new MailMenu(plugin, mail, compose, page, folder));
        }
    }

    private MailManager.MailEntry entry(Player player) {
        return mail.find(player.getUniqueId(), entryId, folder == MailFolder.SPAM);
    }

    private org.bukkit.inventory.ItemStack messageItem(Player viewer, MailManager.MailEntry entry) {
        String shown = MailManager.shownFrom(entry);
        ArrayList<String> lore = new ArrayList<>();
        lore.add("<#7B68A0>" + MailMenu.DATE.format(Instant.ofEpochMilli(entry.time())));
        lore.add(Lang.raw(viewer, "mail.gui-from", "from", shown));
        lore.add("");
        lore.addAll(MailMenu.messageLore(viewer, entry.message(), "<#FBF2FF>"));
        return MailMenu.basic(Material.WRITTEN_BOOK,
                "<gradient:#240046:#9D4EDD:#E0AAFF>" + Lang.raw(viewer, "mail.gui-mail-from", "from", shown),
                lore);
    }
}
