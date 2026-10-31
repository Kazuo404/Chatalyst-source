package dev.chatalyst.profile;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Jugadores que el espectador ignora (lo que guarda /ignore), con su cabeza. Clic en una
 * cabeza = confirmar y dejar de ignorarlo.
 */
public final class BlockedMenu implements Menu {

    private final ChatalystPlugin plugin;
    private final int requestedPage;
    /** Lista congelada al construir: los clics se resuelven contra lo que el jugador VE. */
    private List<UUID> entries = List.of();
    private int page;

    public BlockedMenu(ChatalystPlugin plugin, int page) {
        this.plugin = plugin;
        this.requestedPage = page;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("profile.blocked.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "profile.blocked.title");
    }

    @Override
    public int size() {
        return ProfileLayout.BLOCKED_SIZE;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        entries = plugin.getPrivateMessages() == null ? List.of()
                : plugin.getPrivateMessages().ignoredBy(viewer.getUniqueId()).stream()
                        .sorted(Comparator.comparing((UUID id) -> name(id).toLowerCase(java.util.Locale.ROOT)))
                        .toList();
        page = ProfileLayout.clampPage(requestedPage, entries.size());

        if (entries.isEmpty()) {
            inv.setItem(ProfileLayout.BLOCKED_EMPTY, MenuItems.item(Material.LIGHT_GRAY_DYE,
                    Lang.raw(viewer, "profile.blocked.empty-name"),
                    Lang.rawList(viewer, "profile.blocked.empty-lore")));
        } else {
            int[] slots = ProfileLayout.blockedSlots(page, entries.size());
            int start = page * ProfileLayout.BLOCKED_PER_PAGE;
            for (int i = 0; i < slots.length; i++) {
                UUID id = entries.get(start + i);
                inv.setItem(slots[i], head(viewer, id));
                PlayerHeads.completeLater(viewer, inv, slots[i], id);
            }
        }

        int pages = ProfileLayout.pages(entries.size());
        inv.setItem(ProfileLayout.BLOCKED_BACK, MenuNavigation.back(viewer));
        inv.setItem(ProfileLayout.BLOCKED_PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(ProfileLayout.BLOCKED_PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inv.setItem(ProfileLayout.BLOCKED_NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
        inv.setItem(ProfileLayout.BLOCKED_CLOSE, MenuNavigation.close(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case ProfileLayout.BLOCKED_BACK -> Menu.open(player, new ProfileMenu(plugin));
            case ProfileLayout.BLOCKED_CLOSE -> player.closeInventory();
            case ProfileLayout.BLOCKED_PREVIOUS -> {
                if (page > 0) Menu.open(player, new BlockedMenu(plugin, page - 1));
            }
            case ProfileLayout.BLOCKED_NEXT -> {
                if (page + 1 < ProfileLayout.pages(entries.size())) {
                    Menu.open(player, new BlockedMenu(plugin, page + 1));
                }
            }
            default -> {
                int index = ProfileLayout.blockedIndexAt(slot, page, entries.size());
                if (index >= 0 && index < entries.size()) {
                    Menu.open(player, new UnignoreConfirmMenu(plugin, entries.get(index), page));
                }
            }
        }
    }

    private ItemStack head(Player viewer, UUID id) {
        ItemStack head = MenuItems.item(Material.PLAYER_HEAD,
                Lang.raw(viewer, "profile.blocked.entry-name", "player", MM.escape(name(id))),
                Lang.rawList(viewer, "profile.blocked.entry-lore"));
        head.editMeta(SkullMeta.class, meta -> PlayerHeads.apply(meta, id));
        return head;
    }

    /** Nombre real conocido por el servidor (sin red), o el UUID corto si nunca entro. */
    static String name(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
    }
}
