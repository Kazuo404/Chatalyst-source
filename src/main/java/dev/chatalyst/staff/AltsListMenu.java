package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cuentas que comparten conexion con el objetivo ({@link AltTracker}), una cabeza por
 * nombre; clic abre el menu de moderacion offline de ese alt (funciona este conectado o
 * no). El mismo aviso que en /check aplica aqui: conexion compartida es PISTA, no prueba
 * (hermanos, cibercafes...), por eso el menu solo consulta y nunca castiga en bloque.
 */
public final class AltsListMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final StaffModerationModule module;
    private final String ownerName;
    private final List<String> altNames;
    private final Consumer<Player> back;
    private int page;

    public AltsListMenu(StaffModerationModule module, String ownerName, List<String> altNames,
                        Consumer<Player> back) {
        this(module, ownerName, altNames, back, 0);
    }

    private AltsListMenu(StaffModerationModule module, String ownerName, List<String> altNames,
                         Consumer<Player> back, int page) {
        this.module = module;
        this.ownerName = ownerName;
        this.altNames = List.copyOf(altNames);
        this.back = back;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.alts-title", "player", MM.escape(ownerName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.alts-title", "player", MM.escape(ownerName));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        if (altNames.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "staff-moderation.alts-empty"), List.of()));
        }
        int pages = Math.max(1, (altNames.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int shown = Math.max(0, Math.min(altNames.size() - start, PAGE_SIZE));
        int[] slots = dev.chatalyst.gui.MenuLayout.slots(shown, 0);
        for (int i = 0; i < shown; i++) inventory.setItem(slots[i], head(viewer, altNames.get(start + i)));
        // Barra simetrica: flecha - volver - flecha, siempre las tres (el n. de pagina va en las flechas).
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.staff.alts")) {
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
            Menu.open(player, new AltsListMenu(module, ownerName, altNames, back, page - 1));
            return;
        }
        int pages = Math.max(1, (altNames.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT && page + 1 < pages) {
            Menu.open(player, new AltsListMenu(module, ownerName, altNames, back, page + 1));
            return;
        }
        int start = page * PAGE_SIZE;
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                Math.max(0, Math.min(altNames.size() - start, PAGE_SIZE)), 0);
        if (local < 0) return;
        int index = start + local;
        String name = altNames.get(index);
        OfflinePlayer alt = resolveKnown(name);
        if (alt == null) return;
        // El menu offline resuelve online/offline por si mismo en cada accion.
        OfflineManageMenu.open(module, player, alt, name, this::reopen);
    }

    private void reopen(Player player) {
        Menu.open(player, new AltsListMenu(module, ownerName, altNames, back, page));
    }

    private ItemStack head(Player viewer, String name) {
        OfflinePlayer alt = resolveKnown(name);
        boolean online = alt != null && alt.getPlayer() != null
                && (!dev.chatalyst.util.Vanish.isVanished(alt.getPlayer())
                        || StaffAccess.canSeeVanished(viewer));
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            if (alt != null) {
                dev.chatalyst.gui.PlayerHeads.apply(meta, alt.getUniqueId());
            }
            meta.displayName(MenuItems.noItalic(MM.full("<#B56CFF>" + MM.escape(name))));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.get(viewer, online ? "staff-moderation.frozen-list-online"
                    : "staff-moderation.frozen-list-offline"));
            lore.add(Lang.get(viewer, "staff-moderation.alts-manage"));
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }

    /** Solo cuentas que este servidor conoce (mismas garantias que /check). */
    private static OfflinePlayer resolveKnown(String name) {
        OfflinePlayer resolved = dev.chatalyst.nick.NickManager.resolveOffline(name);
        if (resolved == null) return null;
        return resolved.isOnline() || resolved.hasPlayedBefore() ? resolved : null;
    }
}
