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

import java.util.List;
import java.util.function.UnaryOperator;

/** Editor integral de una definicion del catalogo. Cada cambio se persiste al instante. */
public final class TagAdminEditMenu implements Menu {

    private static final int PREVIEW = 4;
    private static final int NAME = 10;
    private static final int TEXT = 12;
    private static final int DESCRIPTION = 14;
    private static final int MATERIAL = 16;
    // Simetrico: filas 1, 2 y 4 en columnas 1-3-5-7; fila 3 seis centradas (sin la columna 4);
    // abajo volver | duplicar | borrar | cerrar.
    private static final int POSITION = 19;
    private static final int SPACING = 21;
    private static final int ENABLED = 23;
    private static final int HIDDEN = 25;
    private static final int SECRET = 28;
    private static final int CATEGORY = 29;
    private static final int POINTS = 30;
    private static final int RARITY = 32;
    private static final int SEASON = 33;
    private static final int SORT_ORDER = 34;
    private static final int REQUIREMENTS = 37;
    private static final int ACCESS = 39;
    private static final int REWARDS = 41;
    private static final int ACHIEVEMENT = 43;
    private static final int BACK = 45;
    private static final int DUPLICATE = 47;
    private static final int DELETE = 51;
    private static final int CLOSE = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;
    private boolean deleteArmed;

    public TagAdminEditMenu(ChatalystPlugin plugin, TagManager tags,
                            String titleId, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-edit-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-edit-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            inventory.setItem(PREVIEW, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.admin-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            inventory.setItem(CLOSE, MenuNavigation.close(viewer));
            return;
        }
        inventory.setItem(PREVIEW, MenuItems.item(definition.icon(),
                definition.text().isBlank() ? MM.escape(definition.displayName())
                        : definition.text(),
                List.of("<#C0B3D9>" + definition.prefix() + MM.escape(viewer.getName())
                                + definition.suffix(),
                        "<#7B68A0>ID <#C0B3D9>" + MM.escape(definition.id()))));
        inventory.setItem(NAME, editItem(viewer, Material.OAK_SIGN, "tag.admin-field-name",
                MM.escape(definition.displayName())));
        inventory.setItem(TEXT, editItem(viewer, Material.NAME_TAG, "tag.admin-field-text",
                definition.text()));
        inventory.setItem(DESCRIPTION, editItem(viewer, Material.WRITABLE_BOOK,
                "tag.admin-field-description", MM.escape(definition.description())));
        inventory.setItem(MATERIAL, editItem(viewer, definition.icon(),
                "tag.admin-field-material", definition.icon().name()));
        inventory.setItem(POSITION, MenuItems.item(Material.COMPASS,
                Lang.raw(viewer, "tag.admin-field-position"),
                List.of("<#FBF2FF>" + definition.position().name(),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-position-lore"))));
        inventory.setItem(SPACING, MenuItems.item(Material.STRING,
                Lang.raw(viewer, "tag.admin-field-spacing"),
                List.of("<#FBF2FF>" + definition.spacing(),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-spacing-lore"))));
        inventory.setItem(ENABLED, toggle(viewer, Material.LEVER, "tag.admin-field-enabled",
                definition.enabled()));
        inventory.setItem(HIDDEN, toggle(viewer, Material.ENDER_EYE, "tag.admin-field-hidden",
                definition.hiddenUntilUnlocked()));
        inventory.setItem(SECRET, toggle(viewer, Material.ENDER_CHEST,
                "tag.admin-field-secret", definition.secret()));
        inventory.setItem(CATEGORY, editItem(viewer, Material.BOOKSHELF,
                "tag.admin-field-category", MM.escape(definition.category())));
        inventory.setItem(RARITY, MenuItems.item(Material.AMETHYST_SHARD,
                Lang.raw(viewer, "tag.admin-field-rarity"),
                List.of(definition.rarity().color() + Lang.raw(viewer,
                                "tag.rarity-" + definition.rarity().name().toLowerCase(
                                        java.util.Locale.ROOT)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-cycle-lore"))));
        inventory.setItem(POINTS, editItem(viewer, Material.EXPERIENCE_BOTTLE,
                "tag.admin-field-points", String.valueOf(definition.points())));
        inventory.setItem(SEASON, MenuItems.item(Material.CLOCK,
                Lang.raw(viewer, "tag.admin-field-season"),
                List.of("<#FBF2FF>" + (definition.season().enabled()
                                ? MM.escape(definition.season().id())
                                : Lang.raw(viewer, "tag.admin-season-none")),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-open"))));
        inventory.setItem(SORT_ORDER, editItem(viewer, Material.HOPPER,
                "tag.admin-field-order", String.valueOf(definition.sortOrder())));
        inventory.setItem(REQUIREMENTS, MenuItems.item(Material.TARGET,
                Lang.raw(viewer, "tag.admin-field-requirements"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.admin-requirements-summary",
                                "count", definition.requirements().rules().size(),
                                "mode", Lang.raw(viewer, "tag.admin-requirements-mode-"
                                        + definition.requirements().mode().name()
                                        .toLowerCase(java.util.Locale.ROOT))),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-open"))));
        inventory.setItem(ACCESS, MenuItems.item(Material.PLAYER_HEAD,
                Lang.raw(viewer, "tag.admin-field-access"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.admin-access-summary",
                                "unlocked", tags.unlockedCount(definition.id()),
                                "equipped", tags.equippedCount(definition.id())),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-open"))));
        inventory.setItem(REWARDS, MenuItems.item(Material.CHEST,
                Lang.raw(viewer, "tag.admin-field-rewards"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.admin-rewards-summary",
                                "count", definition.rewards().commands().size()),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-open"))));
        inventory.setItem(ACHIEVEMENT, MenuItems.item(
                definition.achievement().toastIcon(),
                Lang.raw(viewer, "tag.admin-field-achievement"),
                List.of("<#FBF2FF>" + Lang.raw(viewer,
                                "tag.admin-achievement-summary",
                                "frame", Lang.raw(viewer,
                                        "tag.admin-achievement-frame-"
                                                + definition.achievement().frame().name()
                                                .toLowerCase(java.util.Locale.ROOT))),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-open"))));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(DUPLICATE, MenuItems.item(Material.MAP,
                Lang.raw(viewer, "tag.admin-duplicate"),
                List.of(Lang.raw(viewer, "tag.admin-duplicate-lore"))));
        inventory.setItem(DELETE, MenuItems.item(deleteArmed
                        ? Material.LAVA_BUCKET : Material.RED_CONCRETE,
                Lang.raw(viewer, deleteArmed ? "tag.admin-delete-confirm"
                        : "tag.admin-delete"),
                List.of(Lang.raw(viewer, deleteArmed ? "tag.admin-delete-confirm-lore"
                        : "tag.admin-delete-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("chatalyst.tag.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            if (slot == BACK) back(player);
            return;
        }
        switch (slot) {
            case NAME -> prompt(player, TagAdminPrompt.Field.DISPLAY_NAME);
            case TEXT -> prompt(player, TagAdminPrompt.Field.TEXT);
            case DESCRIPTION -> prompt(player, TagAdminPrompt.Field.DESCRIPTION);
            case MATERIAL -> prompt(player, TagAdminPrompt.Field.MATERIAL);
            case POSITION -> update(player,
                    current -> current.withPosition(current.position().opposite()));
            case SPACING -> update(player, current -> current.withSpacing(
                    click.isRightClick() ? current.spacing() - 1 : current.spacing() + 1));
            case ENABLED -> update(player,
                    current -> current.withEnabled(!current.enabled()));
            case HIDDEN -> update(player,
                    current -> current.withHiddenUntilUnlocked(
                            !current.hiddenUntilUnlocked()));
            case SECRET -> update(player,
                    current -> current.withSecret(!current.secret()));
            case CATEGORY -> prompt(player, TagAdminPrompt.Field.CATEGORY);
            case RARITY -> update(player, current -> current.withRarity(
                    current.rarity().step(click.isRightClick() ? -1 : 1)));
            case SORT_ORDER -> prompt(player, TagAdminPrompt.Field.SORT_ORDER);
            case POINTS -> prompt(player, TagAdminPrompt.Field.POINTS);
            case SEASON -> Menu.open(player, new TagSeasonMenu(
                    plugin, tags, titleId, returnPage));
            case REQUIREMENTS -> Menu.open(player, new TagRequirementsMenu(
                    plugin, tags, titleId, returnPage));
            case ACCESS -> Menu.open(player, new TagAccessMenu(
                    plugin, tags, titleId, returnPage));
            case REWARDS -> Menu.open(player, new TagRewardsMenu(
                    plugin, tags, titleId, returnPage));
            case ACHIEVEMENT -> Menu.open(player, new TagAchievementMenu(
                    plugin, tags, titleId, returnPage));
            case BACK -> back(player);
            case DUPLICATE -> duplicate(player);
            case DELETE -> delete(player, definition);
            default -> {
            }
        }
    }

    private void update(Player player, UnaryOperator<TagDefinition> change) {
        TagDefinition current = tags.catalog().byId(titleId);
        if (current == null || !tags.catalog().update(change.apply(current))) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
        } else {
            audit(player, "tag title edit", titleId);
        }
        Menu.open(player, this);
    }

    private void prompt(Player player, TagAdminPrompt.Field field) {
        TagAdminPrompt prompt = plugin.getTagAdminPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.begin(player, titleId, returnPage, field);
    }

    private void delete(Player player, TagDefinition definition) {
        int dependents = tags.catalog().dependentCount(titleId);
        if (dependents > 0) {
            player.sendMessage(Lang.get(player, "tag.admin-delete-dependents",
                    "count", dependents));
            return;
        }
        if (!deleteArmed) {
            deleteArmed = true;
            Menu.open(player, this);
            return;
        }
        if (!tags.catalog().remove(titleId)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        audit(player, "tag title delete", definition.id());
        player.sendMessage(Lang.get(player, "tag.admin-deleted",
                "title", definition.displayName()));
        back(player);
    }

    private void duplicate(Player player) {
        if (tags.catalog().isFull()) {
            player.sendMessage(Lang.get(player, "tag.admin-limit",
                    "max", TagCatalog.MAX_TITLES));
            return;
        }
        TagDefinition copy = tags.catalog().duplicate(titleId);
        if (copy == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        audit(player, "tag title duplicate", titleId + " -> " + copy.id());
        player.sendMessage(Lang.get(player, "tag.admin-duplicated",
                "title", copy.displayName()));
        Menu.open(player, new TagAdminEditMenu(
                plugin, tags, copy.id(), returnPage));
    }

    private void back(Player player) {
        Menu.open(player, new TagAdminMenu(plugin, tags, returnPage));
    }

    private org.bukkit.inventory.ItemStack editItem(Player viewer, Material material,
                                                    String key, String value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + (value == null || value.isBlank() ? "-" : value),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-change")));
    }

    private org.bukkit.inventory.ItemStack toggle(Player viewer, Material material,
                                                  String key, boolean enabled) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of(enabled
                        ? "<#3DFFA8>" + Lang.raw(viewer, "tag.admin-enabled")
                        : "<#FF4D6D>" + Lang.raw(viewer, "tag.admin-disabled"),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-toggle")));
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }
}
