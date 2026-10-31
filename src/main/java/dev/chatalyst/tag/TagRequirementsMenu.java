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

/** Lista visual de reglas que componen la obtencion de un titulo. */
public final class TagRequirementsMenu implements Menu {

    private static final int[] RULE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17};
    private static final int BACK = 45;
    private static final int MODE = 49;
    private static final int ADD = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;

    public TagRequirementsMenu(ChatalystPlugin plugin, TagManager tags,
                               String titleId, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-requirements-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-requirements-title");
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
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.admin-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            return;
        }
        List<TagRequirement> rules = definition.requirements().rules();
        for (int index = 0; index < rules.size(); index++) {
            inventory.setItem(RULE_SLOTS[index], ruleItem(viewer, rules.get(index), index));
        }
        if (rules.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.LIME_DYE,
                    Lang.raw(viewer, "tag.admin-requirements-free"),
                    List.of(Lang.raw(viewer, "tag.admin-requirements-free-lore"))));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(MODE, MenuItems.item(Material.COMPARATOR,
                Lang.raw(viewer, "tag.admin-requirements-mode"),
                List.of("<#FBF2FF>" + mode(viewer, definition.requirements().mode()),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-toggle"))));
        inventory.setItem(ADD, MenuItems.item(rules.size() >= TagRequirementGroup.MAX_RULES
                        ? Material.GRAY_DYE : Material.LIME_CONCRETE,
                Lang.raw(viewer, "tag.admin-requirements-add"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "tag.admin-requirements-count",
                                "count", rules.size(), "max", TagRequirementGroup.MAX_RULES),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-requirements-add-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!allowed(player)) return;
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            if (slot == BACK) back(player);
            return;
        }
        if (slot == BACK) {
            back(player);
            return;
        }
        if (slot == MODE) {
            update(player, definition.withRequirements(definition.requirements()
                    .withMode(definition.requirements().mode().opposite())));
            return;
        }
        if (slot == ADD) {
            if (definition.requirements().rules().size() >= TagRequirementGroup.MAX_RULES) {
                player.sendMessage(Lang.get(player, "tag.admin-requirements-limit",
                        "max", TagRequirementGroup.MAX_RULES));
                return;
            }
            TagRequirementGroup next = definition.requirements()
                    .add(TagRequirement.defaults(TagRequirementType.MOB_KILLS));
            if (persist(player, definition.withRequirements(next))) {
                Menu.open(player, new TagRequirementEditMenu(plugin, tags, titleId,
                        returnPage, next.rules().size() - 1));
            }
            return;
        }
        for (int index = 0; index < RULE_SLOTS.length; index++) {
            if (slot == RULE_SLOTS[index]
                    && index < definition.requirements().rules().size()) {
                Menu.open(player, new TagRequirementEditMenu(
                        plugin, tags, titleId, returnPage, index));
                return;
            }
        }
    }

    private org.bukkit.inventory.ItemStack ruleItem(Player viewer, TagRequirement rule,
                                                     int index) {
        String key = "tag.requirement-" + rule.type().name().toLowerCase(
                java.util.Locale.ROOT).replace('_', '-');
        return MenuItems.item(icon(rule.type()),
                Lang.raw(viewer, "tag.admin-requirement-number", "number", index + 1),
                List.of("<#FBF2FF>" + Lang.raw(viewer, key,
                                "target", MM.escape(rule.target()), "amount", rule.amount()),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-requirement-edit-lore")));
    }

    private void update(Player player, TagDefinition updated) {
        if (persist(player, updated)) Menu.open(player, this);
    }

    private boolean persist(Player player, TagDefinition updated) {
        if (!tags.catalog().update(updated)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }
        audit(player, "tag requirements edit", titleId);
        return true;
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.tag.admin")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }

    private void back(Player player) {
        Menu.open(player, new TagAdminEditMenu(plugin, tags, titleId, returnPage));
    }

    private String mode(Player viewer, TagRequirementMode mode) {
        return Lang.raw(viewer, "tag.admin-requirements-mode-"
                + mode.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Material icon(TagRequirementType type) {
        return switch (type) {
            case NONE -> Material.LIME_DYE;
            case MOB_KILLS -> Material.IRON_SWORD;
            case PLAYER_KILLS -> Material.DIAMOND_SWORD;
            case PLAYTIME_MINUTES -> Material.CLOCK;
            case BLOCKS_MINED -> Material.DIAMOND_PICKAXE;
            case ITEMS_CRAFTED -> Material.CRAFTING_TABLE;
            case ITEMS_PICKED_UP -> Material.HOPPER;
            case ITEMS_OWNED -> Material.CHEST;
            case FISH_CAUGHT -> Material.FISHING_ROD;
            case ANIMALS_BRED -> Material.WHEAT;
            case ADVANCEMENT -> Material.KNOWLEDGE_BOOK;
            case PERMISSION -> Material.TRIPWIRE_HOOK;
            case TITLE_UNLOCKED -> Material.CHAIN;
            case PLACEHOLDER -> Material.PAPER;
            case COUNTER -> Material.REPEATER;
            case MANUAL -> Material.WRITABLE_BOOK;
        };
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }
}
