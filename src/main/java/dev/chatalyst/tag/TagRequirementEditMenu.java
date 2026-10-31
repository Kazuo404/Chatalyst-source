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

/** Editor de una regla concreta dentro del grupo de requisitos. */
public final class TagRequirementEditMenu implements Menu {

    private static final int TYPE = 11;
    private static final int TARGET = 13;
    private static final int AMOUNT = 15;
    private static final int BACK = 45;
    private static final int CLOSE = 53;   // espejo de BACK: barra simetrica
    private static final int DELETE = 49;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;
    private final int ruleIndex;
    private boolean deleteArmed;

    public TagRequirementEditMenu(ChatalystPlugin plugin, TagManager tags,
                                  String titleId, int returnPage, int ruleIndex) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
        this.ruleIndex = ruleIndex;
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-requirement-edit-title", "number", ruleIndex + 1);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-requirement-edit-title",
                "number", ruleIndex + 1);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        TagRequirement rule = rule();
        if (rule == null) {
            inventory.setItem(22, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.admin-requirement-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            inventory.setItem(CLOSE, MenuNavigation.close(viewer));
            return;
        }
        inventory.setItem(TYPE, MenuItems.item(Material.TARGET,
                Lang.raw(viewer, "tag.admin-field-rule-type"),
                List.of("<#FBF2FF>" + type(viewer, rule.type()),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-cycle-lore"))));
        inventory.setItem(TARGET, MenuItems.item(Material.SPYGLASS,
                Lang.raw(viewer, "tag.admin-field-rule-target"),
                List.of("<#FBF2FF>" + (rule.target().isBlank()
                                ? "-" : MM.escape(rule.target())),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                targetUsed(rule.type())
                                        ? "tag.admin-click-change"
                                        : "tag.admin-field-unused"))));
        inventory.setItem(AMOUNT, MenuItems.item(Material.EXPERIENCE_BOTTLE,
                Lang.raw(viewer, "tag.admin-field-rule-amount"),
                List.of("<#FBF2FF>" + rule.amount(),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                rule.hasNumericProgress()
                                        ? "tag.admin-click-change"
                                        : "tag.admin-field-unused"))));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(DELETE, MenuItems.item(deleteArmed
                        ? Material.LAVA_BUCKET : Material.RED_CONCRETE,
                Lang.raw(viewer, deleteArmed
                        ? "tag.admin-requirement-delete-confirm"
                        : "tag.admin-requirement-delete"),
                List.of(Lang.raw(viewer, deleteArmed
                        ? "tag.admin-delete-confirm-lore"
                        : "tag.admin-requirement-delete-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (!allowed(player)) return;
        TagDefinition definition = tags.catalog().byId(titleId);
        TagRequirement current = rule(definition);
        if (current == null) {
            if (slot == BACK) back(player);
            return;
        }
        if (slot == BACK) {
            back(player);
            return;
        }
        if (slot == TYPE) {
            TagRequirementType nextType = current.type()
                    .step(click.isRightClick() ? -1 : 1);
            TagRequirement next = current.withType(nextType);
            if (nextType == TagRequirementType.TITLE_UNLOCKED) {
                next = validDependencyDefault(definition, next);
                if (next == null) {
                    player.sendMessage(Lang.get(player,
                            "tag.admin-dependency-no-candidate"));
                    return;
                }
            }
            update(player, definition.withRequirements(
                    definition.requirements().update(ruleIndex, next)));
            return;
        }
        if (slot == TARGET) {
            if (!targetUsed(current.type())) {
                player.sendMessage(Lang.get(player, "tag.admin-field-unused"));
                return;
            }
            prompt(player, TagAdminPrompt.Field.RULE_TARGET);
            return;
        }
        if (slot == AMOUNT) {
            if (!current.hasNumericProgress()) {
                player.sendMessage(Lang.get(player, "tag.admin-amount-not-used"));
                return;
            }
            prompt(player, TagAdminPrompt.Field.RULE_AMOUNT);
            return;
        }
        if (slot == DELETE) {
            if (!deleteArmed) {
                deleteArmed = true;
                Menu.open(player, this);
                return;
            }
            TagRequirementGroup next = definition.requirements().remove(ruleIndex);
            if (persist(player, definition.withRequirements(next))) back(player);
        }
    }

    private void prompt(Player player, TagAdminPrompt.Field field) {
        TagAdminPrompt prompt = plugin.getTagAdminPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.beginRequirement(player, titleId, returnPage, ruleIndex, field);
    }

    private void update(Player player, TagDefinition updated) {
        if (persist(player, updated)) Menu.open(player, this);
    }

    private boolean persist(Player player, TagDefinition updated) {
        if (tags.catalog().wouldCreateDependencyCycle(updated)) {
            player.sendMessage(Lang.get(player, "tag.admin-dependency-cycle"));
            return false;
        }
        if (!tags.catalog().update(updated)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(
                    player, "tag requirement edit", titleId + " #" + ruleIndex);
        }
        return true;
    }

    private TagRequirement rule() {
        return rule(tags.catalog().byId(titleId));
    }

    private TagRequirement rule(TagDefinition definition) {
        if (definition == null || ruleIndex < 0
                || ruleIndex >= definition.requirements().rules().size()) return null;
        return definition.requirements().rules().get(ruleIndex);
    }

    private TagRequirement validDependencyDefault(TagDefinition definition,
                                                   TagRequirement template) {
        for (TagDefinition candidate : tags.catalog().all()) {
            if (candidate.id().equals(definition.id())) continue;
            TagRequirement next = template.withTarget(candidate.id());
            TagDefinition updated = definition.withRequirements(
                    definition.requirements().update(ruleIndex, next));
            if (!tags.catalog().wouldCreateDependencyCycle(updated)) return next;
        }
        return null;
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.tag.admin")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }

    private void back(Player player) {
        Menu.open(player, new TagRequirementsMenu(plugin, tags, titleId, returnPage));
    }

    private String type(Player viewer, TagRequirementType type) {
        return Lang.raw(viewer, "tag.admin-requirement-type-"
                + type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }

    private static boolean targetUsed(TagRequirementType type) {
        return type == TagRequirementType.MOB_KILLS
                || type == TagRequirementType.BLOCKS_MINED
                || type == TagRequirementType.ITEMS_CRAFTED
                || type == TagRequirementType.ITEMS_PICKED_UP
                || type == TagRequirementType.ITEMS_OWNED
                || type == TagRequirementType.ADVANCEMENT
                || type == TagRequirementType.PERMISSION
                || type == TagRequirementType.TITLE_UNLOCKED
                || type == TagRequirementType.PLACEHOLDER
                || type == TagRequirementType.COUNTER;
    }
}
