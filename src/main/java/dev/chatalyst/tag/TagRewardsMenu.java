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

/** Editor visual de comandos ejecutados cuando un jugador obtiene el titulo. */
public final class TagRewardsMenu implements Menu {

    private static final int[] COMMAND_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17};
    private static final int BACK = 45;
    private static final int ADD = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;

    public TagRewardsMenu(ChatalystPlugin plugin, TagManager tags,
                          String titleId, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-rewards-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-rewards-title");
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
        List<String> commands = definition.rewards().commands();
        for (int index = 0; index < commands.size(); index++) {
            inventory.setItem(COMMAND_SLOTS[index], MenuItems.item(Material.COMMAND_BLOCK,
                    Lang.raw(viewer, "tag.admin-reward-number", "number", index + 1),
                    List.of("<#FBF2FF>/" + MM.escape(commands.get(index)),
                            "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-reward-edit-lore"),
                            "<#FF4D6D>" + Lang.raw(viewer, "tag.admin-reward-delete-lore"))));
        }
        if (commands.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.CHEST,
                    Lang.raw(viewer, "tag.admin-rewards-empty"),
                    List.of(Lang.raw(viewer, "tag.admin-rewards-empty-lore"))));
        }
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(ADD, MenuItems.item(commands.size() >= TagRewards.MAX_COMMANDS
                        ? Material.GRAY_DYE : Material.LIME_CONCRETE,
                Lang.raw(viewer, "tag.admin-rewards-add"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "tag.admin-rewards-count",
                                "count", commands.size(), "max", TagRewards.MAX_COMMANDS),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-rewards-add-lore"))));
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
        if (slot == ADD) {
            if (definition.rewards().commands().size() >= TagRewards.MAX_COMMANDS) {
                player.sendMessage(Lang.get(player, "tag.admin-reward-limit",
                        "max", TagRewards.MAX_COMMANDS));
                return;
            }
            prompt(player, -1);
            return;
        }
        for (int index = 0; index < COMMAND_SLOTS.length; index++) {
            if (slot != COMMAND_SLOTS[index]
                    || index >= definition.rewards().commands().size()) continue;
            if (click.isShiftClick() && click.isRightClick()) {
                TagDefinition updated = definition.withRewards(
                        definition.rewards().remove(index));
                if (persist(player, updated)) Menu.open(player, this);
            } else {
                prompt(player, index);
            }
            return;
        }
    }

    private void prompt(Player player, int index) {
        TagAdminPrompt prompt = plugin.getTagAdminPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.beginReward(player, titleId, returnPage, index);
    }

    private boolean persist(Player player, TagDefinition updated) {
        if (!tags.catalog().update(updated)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, "tag reward edit", titleId);
        }
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
}
