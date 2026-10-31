package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sanciones activas en dos pestañas: congelados y muteados, una cabeza por jugador con su
 * motivo y quien la aplico. En congelados, clic va hacia el jugador y shift+clic lo libera;
 * en muteados, clic abre su menu de moderacion y shift+clic lo desmutea.
 */
public final class FrozenListMenu implements Menu {

    public enum Tab { FROZEN, MUTED }

    private static final int PREVIOUS = 36;
    private static final int TAB_FROZEN = 38;
    private static final int BACK = 40;
    private static final int TAB_MUTED = 42;
    private static final int NEXT = 44;
    private static final int MAX_HEADS = 27;

    private final StaffModerationModule module;
    private final Tab tab;
    private int page;

    public FrozenListMenu(StaffModerationModule module) {
        this(module, Tab.FROZEN);
    }

    public FrozenListMenu(StaffModerationModule module, Tab tab) {
        this(module, tab, 0);
    }

    private FrozenListMenu(StaffModerationModule module, Tab tab, int page) {
        this.module = module;
        this.tab = tab;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get(titleKey());
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey());
    }

    private String titleKey() {
        return tab == Tab.FROZEN ? "staff-moderation.frozen-list-title"
                : "staff-moderation.muted-list-title";
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        int total;
        if (tab == Tab.FROZEN) {
            List<Map.Entry<UUID, FreezeRecord>> frozen = frozenEntries();
            total = frozen.size();
            page = Math.min(page, pages(total) - 1);
            if (frozen.isEmpty()) {
                inventory.setItem(13, MenuItems.item(Material.PACKED_ICE,
                        Lang.raw(viewer, "staff-moderation.frozen-list-empty"), List.of()));
            }
            int start = page * MAX_HEADS;
            int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                    Math.max(0, Math.min(frozen.size() - start, MAX_HEADS)), 0);
            for (int i = start; i < Math.min(frozen.size(), start + MAX_HEADS); i++) {
                inventory.setItem(layout[i - start], frozenHead(viewer, frozen.get(i)));
            }
        } else {
            List<Map.Entry<UUID, Mute>> muted = mutedEntries();
            total = muted.size();
            page = Math.min(page, pages(total) - 1);
            if (muted.isEmpty()) {
                inventory.setItem(13, MenuItems.item(Material.ORANGE_DYE,
                        Lang.raw(viewer, "staff-moderation.muted-list-empty"), List.of()));
            }
            int start = page * MAX_HEADS;
            int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                    Math.max(0, Math.min(muted.size() - start, MAX_HEADS)), 0);
            for (int i = start; i < Math.min(muted.size(), start + MAX_HEADS); i++) {
                inventory.setItem(layout[i - start], mutedHead(viewer, muted.get(i)));
            }
        }

        inventory.setItem(TAB_FROZEN, MenuItems.item(
                tab == Tab.FROZEN ? Material.BLUE_ICE : Material.PACKED_ICE,
                Lang.raw(viewer, "staff-moderation.list-tab-frozen",
                        "count", module.freezes().snapshot().size()), List.of()));
        inventory.setItem(TAB_MUTED, MenuItems.item(
                tab == Tab.MUTED ? Material.ORANGE_CONCRETE : Material.ORANGE_DYE,
                Lang.raw(viewer, "staff-moderation.list-tab-muted",
                        "count", mutedEntries().size()), List.of()));
        int pages = pages(total);
        inventory.setItem(PREVIOUS,
                MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NEXT,
                MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.command.freeze")
                && !StaffAccess.has(player, "chatalyst.command.mute")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        switch (slot) {
            case BACK -> {
                StaffDashboardMenu.open(module, player);
                return;
            }
            case TAB_FROZEN -> {
                if (tab != Tab.FROZEN) Menu.open(player, new FrozenListMenu(module, Tab.FROZEN, 0));
                return;
            }
            case TAB_MUTED -> {
                if (tab != Tab.MUTED) Menu.open(player, new FrozenListMenu(module, Tab.MUTED, 0));
                return;
            }
            case PREVIOUS -> {
                if (page > 0) Menu.open(player, new FrozenListMenu(module, tab, page - 1));
                return;
            }
            case NEXT -> {
                int total = tab == Tab.FROZEN ? frozenEntries().size() : mutedEntries().size();
                if (page + 1 < pages(total)) {
                    Menu.open(player, new FrozenListMenu(module, tab, page + 1));
                }
                return;
            }
            default -> { }
        }
        if (tab == Tab.FROZEN) {
            clickFrozen(player, slot, click);
        } else {
            clickMuted(player, slot, click);
        }
    }

    private void clickFrozen(Player player, int slot, ClickType click) {
        List<Map.Entry<UUID, FreezeRecord>> frozen = frozenEntries();
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                Math.max(0, Math.min(frozen.size() - page * MAX_HEADS, MAX_HEADS)), 0);
        if (local < 0) return;
        int index = page * MAX_HEADS + local;
        String name = nameOf(frozen.get(index).getKey());
        if (name == null) return;
        player.closeInventory();
        if (click.isShiftClick()) {
            if (!StaffAccess.has(player, "chatalyst.command.freeze")) return;
            player.performCommand("chatalyst:unfreeze " + name);
        } else {
            player.performCommand("chatalyst:tpto " + name);
        }
    }

    private void clickMuted(Player player, int slot, ClickType click) {
        List<Map.Entry<UUID, Mute>> muted = mutedEntries();
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                Math.max(0, Math.min(muted.size() - page * MAX_HEADS, MAX_HEADS)), 0);
        if (local < 0) return;
        int index = page * MAX_HEADS + local;
        UUID id = muted.get(index).getKey();
        String name = nameOf(id);
        if (name == null) return;
        if (click.isShiftClick()) {
            if (!StaffAccess.has(player, "chatalyst.command.mute")) return;
            player.closeInventory();
            player.performCommand("chatalyst:unmute " + name);
        } else {
            FrozenListMenu.Tab current = tab;
            int currentPage = page;
            OfflineManageMenu.open(module, player, Bukkit.getOfflinePlayer(id), name,
                    viewer -> Menu.open(viewer, new FrozenListMenu(module, current, currentPage)));
        }
    }

    private List<Map.Entry<UUID, FreezeRecord>> frozenEntries() {
        return new ArrayList<>(module.freezes().snapshot().entrySet());
    }

    private List<Map.Entry<UUID, Mute>> mutedEntries() {
        var mutes = module.plugin().getMuteManager();
        if (mutes == null) return List.of();
        return new ArrayList<>(mutes.snapshot().entrySet());
    }

    private static int pages(int entries) {
        return Math.max(1, (entries + MAX_HEADS - 1) / MAX_HEADS);
    }

    private ItemStack frozenHead(Player viewer, Map.Entry<UUID, FreezeRecord> entry) {
        UUID id = entry.getKey();
        FreezeRecord record = entry.getValue();
        String name = nameOf(id);
        String shown = name == null ? id.toString().substring(0, 8) : name;
        boolean online = Bukkit.getPlayer(id) != null;

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, id);
            meta.displayName(MenuItems.noItalic(MM.full("<#B56CFF>" + MM.escape(shown))));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.get(viewer, online ? "staff-moderation.frozen-list-online"
                    : "staff-moderation.frozen-list-offline"));
            lore.add(module.formatter().feedback(viewer,
                    "staff-moderation.frozen-list-reason", record.reason()));
            lore.add(Lang.get(viewer, "staff-moderation.frozen-list-by",
                    "staff", MM.escape(record.staff())));
            lore.add(Component.empty());
            lore.add(Lang.get(viewer, "staff-moderation.frozen-list-go"));
            lore.add(Lang.get(viewer, "staff-moderation.frozen-list-release"));
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }

    private ItemStack mutedHead(Player viewer, Map.Entry<UUID, Mute> entry) {
        UUID id = entry.getKey();
        Mute mute = entry.getValue();
        String name = nameOf(id);
        String shown = name == null ? id.toString().substring(0, 8) : name;
        boolean online = Bukkit.getPlayer(id) != null;

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, id);
            meta.displayName(MenuItems.noItalic(MM.full("<#E0AAFF>" + MM.escape(shown))));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.get(viewer, online ? "staff-moderation.frozen-list-online"
                    : "staff-moderation.frozen-list-offline"));
            lore.add(Lang.get(viewer, "staff-moderation.muted-list-remaining",
                    "duration", mute.isPermanent() ? "∞" : mute.remaining()));
            if (!mute.reason.isEmpty()) {
                lore.add(module.formatter().feedback(viewer,
                        "staff-moderation.frozen-list-reason", mute.reason));
            }
            lore.add(Lang.get(viewer, "staff-moderation.muted-list-by",
                    "staff", MM.escape(mute.by)));
            if (mute.soft) {
                lore.add(Lang.get(viewer, "staff-moderation.muted-list-soft"));
            }
            lore.add(Component.empty());
            lore.add(Lang.get(viewer, "staff-moderation.muted-list-manage"));
            lore.add(Lang.get(viewer, "staff-moderation.muted-list-release"));
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }

    private static String nameOf(UUID id) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(id);
        return target.getName();
    }
}
