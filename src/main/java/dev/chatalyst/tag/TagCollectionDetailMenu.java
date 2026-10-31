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

/** Detalle de una colección, sus títulos y su presentación administrativa. */
public final class TagCollectionDetailMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    // Barra simetrica: <- nombre descripcion | volver | icono bonus ->
    private static final int PREVIOUS = 45;
    private static final int NAME = 46;
    private static final int DESCRIPTION = 47;
    private static final int BACK = 49;
    private static final int ICON = 51;
    private static final int BONUS = 52;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String collectionId;
    private final int collectionsPage;
    private final boolean returnAdmin;
    private final int adminReturnPage;
    private int page;
    private final List<String> shown = new ArrayList<>();

    public TagCollectionDetailMenu(ChatalystPlugin plugin, TagManager tags,
                                   String collectionId, int page,
                                   int collectionsPage, boolean returnAdmin,
                                   int adminReturnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.collectionId = collectionId;
        this.page = Math.max(0, page);
        this.collectionsPage = Math.max(0, collectionsPage);
        this.returnAdmin = returnAdmin;
        this.adminReturnPage = Math.max(0, adminReturnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.collection-detail-title");
    }

    @Override
    public Component title(Player viewer) {
        TagCollection collection = tags.catalog().collectionById(collectionId);
        return collection == null
                ? Lang.get(viewer, "tag.collection-detail-title")
                : Lang.get(viewer, "tag.collection-detail-title-named",
                "collection", collection.displayName());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        TagCollection collection = tags.catalog().collectionById(collectionId);
        if (collection == null) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.collection-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            return;
        }
        List<TagDefinition> titles = visibleTitles(viewer, collection);
        int pages = Math.max(1, (titles.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(titles.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            TagDefinition definition = titles.get(index);
            shown.add(definition.id());
            inventory.setItem(layout[index - start], titleItem(viewer, definition));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NAME, field(viewer, Material.OAK_SIGN,
                "tag.collection-field-name", MM.escape(collection.displayName())));
        inventory.setItem(DESCRIPTION, field(viewer, Material.WRITABLE_BOOK,
                "tag.collection-field-description",
                collection.description().isBlank() ? "-" : MM.escape(collection.description())));
        inventory.setItem(ICON, field(viewer, collection.icon(),
                "tag.collection-field-icon", collection.icon().name()));
        inventory.setItem(BONUS, field(viewer, Material.EXPERIENCE_BOTTLE,
                "tag.collection-field-bonus", String.valueOf(collection.bonusPoints())));
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.tag")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        TagCollection collection = tags.catalog().collectionById(collectionId);
        if (collection == null) {
            if (slot == BACK) back(player);
            return;
        }
        if (slot == BACK) {
            back(player);
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            open(player, page - 1);
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1, (visibleTitles(player, collection).size()
                    + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1);
            return;
        }
        if (slot == NAME || slot == DESCRIPTION || slot == ICON || slot == BONUS) {
            if (!player.hasPermission("chatalyst.tag.admin")) {
                player.sendMessage(Lang.get(player, "tag.collection-admin-only"));
                return;
            }
            TagAdminPrompt.Field field = switch (slot) {
                case NAME -> TagAdminPrompt.Field.COLLECTION_NAME;
                case DESCRIPTION -> TagAdminPrompt.Field.COLLECTION_DESCRIPTION;
                case ICON -> TagAdminPrompt.Field.COLLECTION_ICON;
                default -> TagAdminPrompt.Field.COLLECTION_BONUS;
            };
            TagAdminPrompt prompt = plugin.getTagAdminPrompt();
            if (prompt == null) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            } else {
                prompt.beginCollection(player, collection.id(), collectionsPage,
                        returnAdmin, adminReturnPage, field);
            }
            return;
        }
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0);
        if (local < 0) return;
        TagDefinition definition = tags.catalog().byId(shown.get(local));
        if (definition == null || !definition.enabled()
                || !tags.isUnlocked(player.getUniqueId(), definition)) {
            player.sendMessage(Lang.get(player, "tag.locked"));
            return;
        }
        if (!tags.equip(player.getUniqueId(), definition.id(),
                dev.chatalyst.api.title.TitleMutationContext.player(
                        player.getUniqueId(), player.getName()))) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        player.sendMessage(Lang.get(player, "tag.equipped",
                "title", definition.displayName()));
        TagDisplay.refresh(plugin, player);
        Menu.open(player, this);
    }

    private org.bukkit.inventory.ItemStack titleItem(Player viewer,
                                                      TagDefinition definition) {
        boolean unlocked = tags.isUnlocked(viewer.getUniqueId(), definition);
        boolean equipped = definition.id().equals(tags.activeId(viewer.getUniqueId()));
        if (definition.secret() && !unlocked) {
            return MenuItems.item(Material.ENDER_EYE,
                    "<#7B68A0>" + Lang.raw(viewer, "tag.secret-name"),
                    List.of("<#B56CFF>" + Lang.raw(viewer, "tag.secret-lore"),
                            "<#FF4D6D>" + Lang.raw(viewer, "tag.status-locked")));
        }
        List<String> lore = new ArrayList<>();
        lore.add(definition.rarity().color() + Lang.raw(viewer,
                "tag.rarity-" + definition.rarity().name().toLowerCase(Locale.ROOT)));
        lore.add("<#FFD166>" + Lang.raw(viewer, "tag.points",
                "points", definition.points()));
        lore.add(equipped
                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.status-equipped")
                : unlocked
                ? "<#E0AAFF>" + Lang.raw(viewer, "tag.status-unlocked")
                : "<#FF4D6D>" + Lang.raw(viewer, "tag.status-locked"));
        if (unlocked && !equipped) {
            lore.add("<#B56CFF>" + Lang.raw(viewer, "tag.click-equip"));
        }
        return TagTooltips.styled(MenuItems.item(unlocked ? definition.icon() : Material.GRAY_DYE,
                definition.menuName(equipped), lore), tags.catalog().tooltipStyle(definition.rarity()));
    }

    private org.bukkit.inventory.ItemStack field(Player viewer, Material material,
                                                 String key, String value) {
        List<String> lore = new ArrayList<>();
        lore.add("<#FBF2FF>" + value);
        lore.add("<#C0B3D9>" + Lang.raw(viewer,
                viewer.hasPermission("chatalyst.tag.admin")
                        ? "tag.admin-click-change" : "tag.collection-admin-only"));
        return MenuItems.item(material, Lang.raw(viewer, key), lore);
    }

    private List<TagDefinition> visibleTitles(Player viewer, TagCollection collection) {
        if (viewer.hasPermission("chatalyst.tag.admin")) return collection.titles();
        return collection.titles().stream()
                .filter(TagDefinition::enabled)
                .filter(definition -> !definition.hiddenUntilUnlocked()
                        || tags.isUnlocked(viewer.getUniqueId(), definition))
                .toList();
    }

    private void open(Player player, int nextPage) {
        Menu.open(player, new TagCollectionDetailMenu(plugin, tags, collectionId,
                nextPage, collectionsPage, returnAdmin, adminReturnPage));
    }

    private void back(Player player) {
        Menu.open(player, new TagCollectionsMenu(plugin, tags, collectionsPage,
                returnAdmin, adminReturnPage));
    }
}
