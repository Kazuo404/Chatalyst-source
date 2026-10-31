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

import java.util.List;

/** Personal mail settings. */
public final class MailSettingsMenu implements Menu {

    private static final int PRIVACY_SLOT = 11;
    private static final int BLOCKED_SLOT = 15;
    private static final int BACK_SLOT = 22;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;

    public MailSettingsMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("mail.gui-settings-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-settings-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        boolean disabled = mail.isReceivingDisabled(viewer.getUniqueId());
        inv.setItem(PRIVACY_SLOT, MailMenu.basic(
                disabled ? Material.RED_DYE : Material.LIME_DYE,
                Lang.raw(viewer, disabled ? "mail.gui-receive-off" : "mail.gui-receive-on"),
                List.of(Lang.raw(viewer, disabled ? "mail.gui-cannot-receive" : "mail.gui-can-receive"),
                        Lang.raw(viewer, "mail.gui-click-toggle"))));
        inv.setItem(BLOCKED_SLOT, MailMenu.basic(Material.IRON_BARS, Lang.raw(viewer, "mail.gui-blocked-senders"),
                List.of(Lang.raw(viewer, "mail.gui-blocked-count", "count", mail.blocked(viewer.getUniqueId()).size()),
                        Lang.raw(viewer, "mail.gui-manage"))));
        inv.setItem(BACK_SLOT, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, 0));
            return;
        }
        if (slot == BLOCKED_SLOT) {
            Menu.open(player, new MailBlockedMenu(plugin, mail, compose));
            return;
        }
        if (slot == PRIVACY_SLOT) {
            boolean disabled = !mail.isReceivingDisabled(player.getUniqueId());
            if (!mail.setReceivingDisabled(player.getUniqueId(), disabled)) {
                player.sendMessage(Lang.get(player, "mail.storage-error"));
                return;
            }
            player.sendMessage(Lang.get(player, disabled ? "mail.privacy-disabled" : "mail.privacy-enabled"));
            Menu.open(player, new MailSettingsMenu(plugin, mail, compose));
        }
    }
}
