package dev.chatalyst.profile;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/** Confirmacion simple antes de dejar de ignorar a alguien desde /perfil. */
public final class UnignoreConfirmMenu implements Menu {

    private final ChatalystPlugin plugin;
    private final UUID target;
    private final int returnPage;

    public UnignoreConfirmMenu(ChatalystPlugin plugin, UUID target, int returnPage) {
        this.plugin = plugin;
        this.target = target;
        this.returnPage = returnPage;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("profile.blocked.confirm-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "profile.blocked.confirm-title");
    }

    @Override
    public int size() {
        return ProfileLayout.CONFIRM_SIZE;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        String player = MM.escape(BlockedMenu.name(target));
        inv.setItem(ProfileLayout.CONFIRM_YES, MenuItems.item(Material.LIME_DYE,
                Lang.raw(viewer, "profile.blocked.confirm-yes-name"),
                Lang.rawList(viewer, "profile.blocked.confirm-yes-lore", "player", player)));
        ItemStack head = MenuItems.item(Material.PLAYER_HEAD,
                Lang.raw(viewer, "profile.blocked.entry-name", "player", player), List.of());
        head.editMeta(SkullMeta.class, meta -> PlayerHeads.apply(meta, target));
        inv.setItem(ProfileLayout.CONFIRM_HEAD, head);
        PlayerHeads.completeLater(viewer, inv, ProfileLayout.CONFIRM_HEAD, target);
        inv.setItem(ProfileLayout.CONFIRM_NO, MenuItems.item(Material.GRAY_DYE,
                Lang.raw(viewer, "profile.blocked.confirm-no-name"),
                Lang.rawList(viewer, "profile.blocked.confirm-no-lore")));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == ProfileLayout.CONFIRM_NO) {
            Menu.open(player, new BlockedMenu(plugin, returnPage));
            return;
        }
        if (slot != ProfileLayout.CONFIRM_YES) return;
        var pm = plugin.getPrivateMessages();
        // Solo se alterna si de verdad lo sigue ignorando: un doble clic o un /ignore en otra
        // ventana no deben volver a ignorarlo por accidente.
        if (pm != null && pm.isIgnoring(player.getUniqueId(), target)) {
            if (pm.toggleIgnore(player, target)) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            } else {
                player.sendMessage(Lang.get(player, "profile.blocked.unignored",
                        "player", MM.escape(BlockedMenu.name(target))));
            }
        }
        Menu.open(player, new BlockedMenu(plugin, returnPage));
    }
}
