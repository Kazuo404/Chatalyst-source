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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Catalogo que muestra progreso, desbloquea y equipa titulos sin escribir comandos. */
public final class TagPlayerMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int CATEGORY = 46;
    private static final int ADMIN = 47;
    private static final int PAGE = 48;
    private static final int UNEQUIP = 49;
    private static final int CLOSE = 50;
    private static final int STATUS = 51;
    private static final int SORT = 52;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String category;
    private final StatusFilter status;
    private final SortMode sort;
    private int page;
    private final List<String> shown = new ArrayList<>();

    public TagPlayerMenu(ChatalystPlugin plugin, TagManager tags) {
        this(plugin, tags, 0, "", StatusFilter.ALL, SortMode.ORDER);
    }

    private TagPlayerMenu(ChatalystPlugin plugin, TagManager tags, int page,
                          String category, StatusFilter status, SortMode sort) {
        this.plugin = plugin;
        this.tags = tags;
        this.page = Math.max(0, page);
        this.category = category == null ? "" : category;
        this.status = status == null ? StatusFilter.ALL : status;
        this.sort = sort == null ? SortMode.ORDER : sort;
    }

    @Override
    public Component title() {
        return Lang.get("tag.menu-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.menu-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        shown.clear();
        TagUnlockProcessor.refresh(plugin, tags, viewer);
        List<TagDefinition> visible = visible(viewer);
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(visible.size(), start + PAGE_SIZE);
        String active = tags.activeId(viewer.getUniqueId());
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            TagDefinition definition = visible.get(index);
            shown.add(definition.id());
            boolean unlocked = tags.isUnlocked(viewer.getUniqueId(), definition);
            boolean equipped = definition.id().equals(active);
            inventory.setItem(layout[index - start], titleItem(viewer, definition, unlocked, equipped));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, MenuNavigation.previous(viewer, page + 1, pages));
        }
        inventory.setItem(CATEGORY, MenuItems.item(Material.BOOKSHELF,
                Lang.raw(viewer, "tag.filter-category"),
                List.of("<#FBF2FF>" + (category.isBlank()
                                ? Lang.raw(viewer, "tag.filter-all") : MM.escape(category)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.filter-cycle-lore"))));
        if (viewer.hasPermission("chatalyst.tag.admin")) {
            inventory.setItem(ADMIN, MenuItems.item(Material.COMMAND_BLOCK,
                    Lang.raw(viewer, "tag.admin-open"),
                    List.of(Lang.raw(viewer, "tag.admin-open-lore"))));
        }
        int earned = tags.earnedCount(viewer.getUniqueId());
        int total = tags.availableCount();
        int percent = total == 0 ? 100 : Math.min(100, earned * 100 / total);
        inventory.setItem(PAGE, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "tag.profile-title"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "tag.profile-page",
                                "page", page + 1, "pages", pages),
                        "<#3DFFA8>" + Lang.raw(viewer, "tag.profile-earned",
                                "earned", earned, "total", total, "percent", percent),
                        "<#FFD166>" + Lang.raw(viewer, "tag.profile-points",
                                "points", tags.achievementPoints(viewer.getUniqueId())),
                        "<#E0AAFF>" + Lang.raw(viewer, "tag.profile-collections-open"))));
        inventory.setItem(UNEQUIP, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "tag.unequip"),
                List.of(Lang.raw(viewer, "tag.unequip-lore"))));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(STATUS, MenuItems.item(Material.SPYGLASS,
                Lang.raw(viewer, "tag.filter-status"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.filter-status-"
                                + status.name().toLowerCase(Locale.ROOT)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.filter-cycle-lore"))));
        inventory.setItem(SORT, MenuItems.item(Material.HOPPER,
                Lang.raw(viewer, "tag.filter-sort"),
                List.of("<#FBF2FF>" + Lang.raw(viewer, "tag.filter-sort-"
                                + sort.name().toLowerCase(Locale.ROOT)),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.filter-cycle-lore"))));
        if (page + 1 < pages) {
            inventory.setItem(NEXT, MenuNavigation.next(viewer, page + 1, pages));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.tag")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            open(player, page - 1, category, status, sort);
            return;
        }
        if (slot == NEXT) {
            int pages = Math.max(1, (visible(player).size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) open(player, page + 1, category, status, sort);
            return;
        }
        if (slot == CATEGORY) {
            open(player, 0, stepCategory(click.isRightClick() ? -1 : 1), status, sort);
            return;
        }
        if (slot == STATUS) {
            open(player, 0, category,
                    status.step(click.isRightClick() ? -1 : 1), sort);
            return;
        }
        if (slot == SORT) {
            open(player, 0, category, status,
                    sort.step(click.isRightClick() ? -1 : 1));
            return;
        }
        if (slot == PAGE) {
            Menu.open(player, new TagCollectionsMenu(plugin, tags));
            return;
        }
        if (slot == ADMIN && player.hasPermission("chatalyst.tag.admin")) {
            Menu.open(player, new TagAdminMenu(plugin, tags, 0));
            return;
        }
        if (slot == UNEQUIP) {
            if (tags.equip(player.getUniqueId(), "",
                    dev.chatalyst.api.title.TitleMutationContext.player(
                            player.getUniqueId(), player.getName()))) {
                player.sendMessage(Lang.get(player, "tag.unequipped"));
                TagDisplay.refresh(plugin, player);
            } else {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            }
            Menu.open(player, this);
            return;
        }
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) < 0) return;
        TagDefinition definition = tags.catalog().byId(shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)));
        if (definition == null || !definition.enabled()) {
            Menu.open(player, this);
            return;
        }
        TagUnlockProcessor.refresh(plugin, tags, player);
        if (!tags.isUnlocked(player.getUniqueId(), definition)) {
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

    private List<TagDefinition> visible(Player viewer) {
        if (tags.catalog() == null) return List.of();
        Comparator<TagDefinition> comparator = switch (sort) {
            case ORDER -> Comparator.comparingInt(TagDefinition::sortOrder)
                    .thenComparing(TagDefinition::category,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TagDefinition::displayName,
                            String.CASE_INSENSITIVE_ORDER);
            case RARITY -> Comparator.comparingInt(
                            (TagDefinition value) -> value.rarity().ordinal()).reversed()
                    .thenComparingInt(TagDefinition::sortOrder)
                    .thenComparing(TagDefinition::displayName,
                            String.CASE_INSENSITIVE_ORDER);
            case POINTS -> Comparator.comparingInt(TagDefinition::points).reversed()
                    .thenComparingInt(TagDefinition::sortOrder)
                    .thenComparing(TagDefinition::displayName,
                            String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(TagDefinition::displayName,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TagDefinition::id);
        };
        return tags.catalog().all().stream()
                .filter(TagDefinition::enabled)
                .filter(definition -> !definition.hiddenUntilUnlocked()
                        || tags.isUnlocked(viewer.getUniqueId(), definition))
                .filter(definition -> category.isBlank()
                        || definition.category().equalsIgnoreCase(category))
                .filter(definition -> switch (status) {
                    case ALL -> true;
                    case UNLOCKED -> tags.isUnlocked(viewer.getUniqueId(), definition);
                    case LOCKED -> !tags.isUnlocked(viewer.getUniqueId(), definition);
                })
                .sorted(comparator)
                .toList();
    }

    private org.bukkit.inventory.ItemStack titleItem(Player viewer, TagDefinition definition,
                                                      boolean unlocked, boolean equipped) {
        List<String> lore = new ArrayList<>();
        if (definition.secret() && !unlocked) {
            lore.add("<#B56CFF>" + Lang.raw(viewer, "tag.secret-lore"));
            lore.add("<#FF4D6D>" + Lang.raw(viewer, "tag.status-locked"));
            return MenuItems.item(Material.ENDER_EYE,
                    "<#7B68A0>" + Lang.raw(viewer, "tag.secret-name"), lore);
        }
        if (!definition.description().isBlank()) {
            lore.add("<#C0B3D9>" + MM.escape(definition.description()));
        }
        lore.add(definition.rarity().color() + Lang.raw(viewer,
                "tag.rarity-" + definition.rarity().name().toLowerCase(Locale.ROOT))
                + " <#7B68A0>• <#C0B3D9>" + MM.escape(definition.category()));
        lore.add("<#FFD166>" + Lang.raw(viewer, "tag.points",
                "points", definition.points()));
        if (definition.season().enabled()) {
            lore.add("<#7B68A0>" + seasonStatus(viewer, definition.season()));
        }
        lore.add("<#7B68A0>" + Lang.raw(viewer, "tag.preview") + " <white>"
                + definition.prefix() + MM.escape(viewer.getName()) + definition.suffix());
        lore.add("<#7B68A0>" + Lang.raw(viewer, "tag.position", "position",
                definition.position().name(), "spacing", definition.spacing()));
        addRequirementLore(lore, viewer, definition, unlocked);
        lore.add(equipped
                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.status-equipped")
                : unlocked
                ? "<#E0AAFF>" + Lang.raw(viewer, "tag.status-unlocked")
                : "<#FF4D6D>" + Lang.raw(viewer, "tag.status-locked"));
        if (unlocked && !equipped) {
            lore.add("<#B56CFF>" + Lang.raw(viewer, "tag.click-equip"));
        }
        Material material = unlocked ? definition.icon() : Material.GRAY_DYE;
        return TagTooltips.styled(MenuItems.item(material,
                definition.menuName(equipped), lore), tags.catalog().tooltipStyle(definition.rarity()));
    }

    private void addRequirementLore(List<String> lore, Player viewer,
                                    TagDefinition definition, boolean unlocked) {
        TagRequirementGroup group = definition.requirements();
        if (group.isFree()) {
            lore.add("<#3DFFA8>" + Lang.raw(viewer, "tag.requirement-none",
                    "target", "", "amount", 1));
            return;
        }
        if (group.rules().size() > 1) {
            lore.add("<#E0AAFF>" + Lang.raw(viewer, "tag.requirements-mode",
                    "mode", Lang.raw(viewer, "tag.admin-requirements-mode-"
                            + group.mode().name().toLowerCase(Locale.ROOT))));
        }
        for (TagRequirement requirement : group.rules()) {
            String key = "tag.requirement-" + requirement.type().name().toLowerCase(
                    Locale.ROOT).replace('_', '-');
            lore.add("<#C0B3D9>• " + Lang.raw(viewer, key,
                    "target", MM.escape(requirement.target()),
                    "amount", requirement.amount()));
            if (!unlocked && requirement.hasNumericProgress()) {
                long progress = Math.min(requirement.amount(),
                        requirement.progress(viewer, target -> {
                            TagDefinition dependency = tags.catalog().byId(target);
                            return dependency != null && tags.isUnlocked(
                                    viewer.getUniqueId(), dependency);
                        }));
                lore.add("<#7B68A0>" + progressBar(progress, requirement.amount())
                        + " <#C0B3D9>" + progress + "<#7B68A0>/<#C0B3D9>"
                        + requirement.amount());
            }
        }
    }

    private String seasonStatus(Player viewer, TagSeason season) {
        long now = System.currentTimeMillis();
        if (!season.validWindow()) {
            return Lang.raw(viewer, "tag.season-invalid");
        }
        if (season.upcoming(now)) {
            return Lang.raw(viewer, "tag.season-upcoming",
                    "season", season.id(), "date",
                    TagSeasonMenu.format(season.startsAtMillis()));
        }
        if (season.ended(now)) {
            return Lang.raw(viewer, "tag.season-ended",
                    "season", season.id(), "date",
                    TagSeasonMenu.format(season.endsAtMillis()));
        }
        return Lang.raw(viewer, "tag.season-active", "season", season.id());
    }

    private void open(Player player, int nextPage, String nextCategory,
                      StatusFilter nextStatus, SortMode nextSort) {
        Menu.open(player, new TagPlayerMenu(plugin, tags, nextPage,
                nextCategory, nextStatus, nextSort));
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

    enum StatusFilter {
        ALL,
        UNLOCKED,
        LOCKED;

        StatusFilter step(int delta) {
            StatusFilter[] values = values();
            return values[Math.floorMod(ordinal() + delta, values.length)];
        }
    }

    enum SortMode {
        ORDER,
        RARITY,
        POINTS,
        NAME;

        SortMode step(int delta) {
            SortMode[] values = values();
            return values[Math.floorMod(ordinal() + delta, values.length)];
        }
    }

    static String progressBar(long progress, long total) {
        int width = 16;
        int filled = total <= 0 ? width
                : (int) Math.min(width, Math.max(0L, progress) * width / total);
        return "<#B56CFF>" + "■".repeat(filled)
                + "<#5A4775>" + "■".repeat(width - filled);
    }
}
