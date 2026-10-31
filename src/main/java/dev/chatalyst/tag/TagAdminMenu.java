package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lista administrativa del catalogo: crear y abrir cualquier titulo, activo o no. */
public final class TagAdminMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int NOTIFY = 46;
    private static final int HOME = 47;
    private static final int CATEGORY = 48;
    private static final int PAGE = 49;
    private static final int AUTO_EQUIP = 50;
    private static final int CREATE = 51;
    private static final int STATUS = 52;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String category;
    private final AdminStatus status;
    private int page;
    private final List<String> shown = new ArrayList<>();

    public TagAdminMenu(ChatalystPlugin plugin, TagManager tags, int page) {
        this(plugin, tags, page, "", AdminStatus.ALL);
    }

    private TagAdminMenu(ChatalystPlugin plugin, TagManager tags, int page,
                         String category, AdminStatus status) {
        this.plugin = plugin;
        this.tags = tags;
        this.page = Math.max(0, page);
        this.category = category == null ? "" : category;
        this.status = status == null ? AdminStatus.ALL : status;
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        List<TagDefinition> definitions = definitions();
        int pages = Math.max(1, (definitions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(definitions.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            TagDefinition definition = definitions.get(index);
            shown.add(definition.id());
            inventory.setItem(layout[index - start], item(viewer, definition));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, MenuNavigation.previous(viewer, page + 1, pages));
        }
        inventory.setItem(NOTIFY, setting(viewer, Material.BELL,
                "tag.admin-notify", tags.catalog().notifyUnlock()));
        inventory.setItem(HOME, MenuNavigation.home(viewer));
        inventory.setItem(CATEGORY, MenuItems.item(Material.BOOKSHELF,
                Lang.raw(viewer, "tag.filter-category"),
                List.of("<#FBF2FF>" + (category.isBlank()
                                ? Lang.raw(viewer, "tag.filter-all") : MM.escape(category)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.filter-cycle-lore"),
                        "<#E0AAFF>" + Lang.raw(viewer,
                                "tag.admin-collections-shift-open"))));
        inventory.setItem(PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inventory.setItem(AUTO_EQUIP, setting(viewer, Material.ARMOR_STAND,
                "tag.admin-auto-equip", tags.catalog().autoEquipFirst()));
        inventory.setItem(CREATE, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "tag.admin-create"),
                List.of(Lang.raw(viewer, "tag.admin-create-lore"))));
        inventory.setItem(STATUS, MenuItems.item(Material.SPYGLASS,
                Lang.raw(viewer, "tag.filter-status"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.admin-filter-status-"
                                + status.name().toLowerCase(Locale.ROOT)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.filter-cycle-lore"))));
        if (page + 1 < pages) {
            inventory.setItem(NEXT, MenuNavigation.next(viewer, page + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.tag.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == HOME) {
            Menu.open(player, new dev.chatalyst.command.AdminMenu(plugin));
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            open(player, page - 1, category, status);
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1,
                    (definitions().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1, category, status);
            return;
        }
        if (slot == CATEGORY) {
            if (click.isShiftClick()) {
                Menu.open(player, new TagCollectionsMenu(
                        plugin, tags, 0, true, page));
                return;
            }
            open(player, 0, stepCategory(click.isRightClick() ? -1 : 1), status);
            return;
        }
        if (slot == STATUS) {
            open(player, 0, category,
                    status.step(click.isRightClick() ? -1 : 1));
            return;
        }
        if (slot == CREATE) {
            if (tags.catalog().isFull()) {
                player.sendMessage(Lang.get(player, "tag.admin-limit",
                        "max", TagCatalog.MAX_TITLES));
                return;
            }
            TagDefinition created = tags.catalog().create();
            if (created == null) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return;
            }
            audit(player, "tag title create", created.id());
            Menu.open(player, new TagAdminEditMenu(plugin, tags, created.id(), page));
            return;
        }
        if (slot == NOTIFY) {
            setting(player, tags.catalog().toggleNotifyUnlock(),
                    "tag setting notify", String.valueOf(tags.catalog().notifyUnlock()));
            return;
        }
        if (slot == AUTO_EQUIP) {
            setting(player,
                    tags.catalog().toggleAutoEquipFirst(),
                    "tag setting auto-equip",
                    String.valueOf(tags.catalog().autoEquipFirst()));
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) >= 0) {
            Menu.open(player, new TagAdminEditMenu(plugin, tags, shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)), page));
        }
    }

    private org.bukkit.inventory.ItemStack item(Player viewer, TagDefinition definition) {
        List<String> lore = new ArrayList<>();
        lore.add("<#7B68A0>ID <#C0B3D9>" + MM.escape(definition.id()));
        lore.add("<#7B68A0>" + Lang.raw(viewer, "tag.preview") + " <white>"
                + definition.prefix() + MM.escape(viewer.getName()) + definition.suffix());
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "tag.position",
                "position", definition.position().name(), "spacing", definition.spacing()));
        lore.add(definition.rarity().color() + Lang.raw(viewer,
                "tag.rarity-" + definition.rarity().name().toLowerCase(Locale.ROOT))
                + " <#7B68A0>• <#C0B3D9>" + MM.escape(definition.category())
                + " <#7B68A0>• #" + definition.sortOrder());
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "tag.admin-card-counts",
                "requirements", definition.requirements().rules().size(),
                "rewards", definition.rewards().commands().size(),
                "unlocked", tags.unlockedCount(definition.id())));
        lore.add("<#FFD166>" + Lang.raw(viewer, "tag.admin-card-points",
                "points", definition.points()));
        if (definition.secret()) {
            lore.add("<#B56CFF>" + Lang.raw(viewer, "tag.admin-card-secret"));
        }
        if (definition.season().enabled()) {
            lore.add("<#E0AAFF>" + Lang.raw(viewer, "tag.admin-card-season",
                    "season", MM.escape(definition.season().id())));
        }
        lore.add(definition.enabled()
                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.admin-enabled")
                : "<#FF4D6D>" + Lang.raw(viewer, "tag.admin-disabled"));
        lore.add("<#E0AAFF>" + Lang.raw(viewer, "tag.admin-click-edit"));
        return MenuItems.item(definition.enabled() ? definition.icon() : Material.GRAY_DYE,
                (definition.enabled() ? "<#FBF2FF>" : "<#7B68A0>")
                        + MM.escape(definition.displayName()), lore);
    }

    private org.bukkit.inventory.ItemStack setting(Player viewer, Material material,
                                                   String key, boolean enabled) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of(enabled
                        ? "<#3DFFA8>" + Lang.raw(viewer, "tag.admin-enabled")
                        : "<#FF4D6D>" + Lang.raw(viewer, "tag.admin-disabled"),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-toggle")));
    }

    private void setting(Player player, boolean persisted, String action, String detail) {
        if (!persisted) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
        } else {
            audit(player, action, detail);
        }
        Menu.open(player, this);
    }

    private List<TagDefinition> definitions() {
        return tags.catalog().all().stream()
                .filter(definition -> category.isBlank()
                        || definition.category().equalsIgnoreCase(category))
                .filter(definition -> switch (status) {
                    case ALL -> true;
                    case ENABLED -> definition.enabled();
                    case DISABLED -> !definition.enabled();
                })
                .toList();
    }

    private String stepCategory(int delta) {
        List<String> categories = new ArrayList<>();
        categories.add("");
        categories.addAll(tags.catalog().categories());
        int current = 0;
        for (int index = 1; index < categories.size(); index++) {
            if (categories.get(index).equalsIgnoreCase(category)) {
                current = index;
                break;
            }
        }
        return categories.get(Math.floorMod(current + delta, categories.size()));
    }

    private void open(Player player, int nextPage, String nextCategory,
                      AdminStatus nextStatus) {
        Menu.open(player, new TagAdminMenu(
                plugin, tags, nextPage, nextCategory, nextStatus));
    }

    private enum AdminStatus {
        ALL,
        ENABLED,
        DISABLED;

        private AdminStatus step(int delta) {
            AdminStatus[] values = values();
            return values[Math.floorMod(ordinal() + delta, values.length)];
        }
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }
}
