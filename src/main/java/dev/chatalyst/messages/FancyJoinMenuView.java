package dev.chatalyst.messages;

import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Inventory presentation for Fancy Join; contains no file writes or click side effects. */
final class FancyJoinMenuView {

    private static final Material[] TIER_ICONS = {
            Material.NETHER_STAR, Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
            Material.IRON_INGOT, Material.COPPER_INGOT, Material.AMETHYST_SHARD,
            Material.QUARTZ, Material.LAPIS_LAZULI};

    private final FancyJoinMenuStore store;

    FancyJoinMenuView(FancyJoinMenuStore store) {
        this.store = store;
    }

    void build(Inventory inventory, Player viewer) {
        inventory.clear();
        YamlConfiguration yaml = store.load();
        inventory.setItem(FancyJoinMenu.SLOT_ENABLED, enabledItem(viewer, yaml));
        placeTiers(inventory, viewer, yaml);
        inventory.setItem(FancyJoinMenu.SLOT_AUTO_DETECT, autoItem(viewer, yaml));
        inventory.setItem(FancyJoinMenu.SLOT_REPLACE_NORMAL, replaceNormalItem(viewer, yaml));
        inventory.setItem(FancyJoinMenu.SLOT_BACK,
                dev.chatalyst.gui.MenuNavigation.home(viewer));
    }

    private ItemStack replaceNormalItem(Player viewer, YamlConfiguration yaml) {
        boolean enabled = yaml.getBoolean("Join.Fancy.Replace_Normal_Message", true);
        Material icon = enabled ? Material.NAME_TAG : Material.PAPER;
        String color = enabled ? "<#3DFFA8>" : "<#7B68A0>";
        return MenuItems.item(icon, color + Lang.raw(viewer, "fancy-join-gui.replace-normal"),
                List.of(state(viewer, enabled),
                "<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.replace-normal-lore"),
                "<#C0B3D9>" + Lang.raw(viewer, "admin-gui.click-toggle")));
    }

    private ItemStack enabledItem(Player viewer, YamlConfiguration yaml) {
        boolean enabled = yaml.getBoolean("Join.Fancy.Enabled", false);
        Material icon = enabled ? Material.LIGHTNING_ROD : Material.GRAY_DYE;
        String color = enabled ? "<#FBF2FF>" : "<#7B68A0>";
        return MenuItems.item(icon, color + Lang.raw(viewer, "fancy-join-gui.enabled"),
                List.of(state(viewer, enabled),
                "<#C0B3D9>" + Lang.raw(viewer, "admin-gui.click-toggle")));
    }

    private void placeTiers(Inventory inventory, Player viewer, YamlConfiguration yaml) {
        List<String> tiers = store.tierKeys(yaml);
        if (tiers.isEmpty()) {
            inventory.setItem(FancyJoinMenu.TIER_ROW_CENTER, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "fancy-join-gui.no-tiers"), List.of()));
            return;
        }
        int start = FancyJoinMenu.TIER_ROW_CENTER - (tiers.size() - 1) / 2;
        for (int index = 0; index < tiers.size(); index++) {
            inventory.setItem(start + index, tierItem(viewer, yaml, tiers.get(index), index));
        }
    }

    private ItemStack autoItem(Player viewer, YamlConfiguration yaml) {
        String base = "Join.Fancy.Auto_Detect_LuckPerms";
        boolean enabled = yaml.getBoolean(base + ".Enabled", true);
        List<String> excluded = yaml.getStringList(base + ".Excluded_Groups");
        List<String> detected = detectedGroups(excluded);
        Material icon = enabled ? Material.ENCHANTED_BOOK : Material.BOOK;
        String color = enabled ? "<#3DFFA8>" : "<#7B68A0>";
        return MenuItems.item(icon, color + Lang.raw(viewer, "fancy-join-gui.auto-detect"),
                List.of(state(viewer, enabled),
                groupLore(viewer, "detected-groups", detected),
                groupLore(viewer, "excluded-groups", excluded),
                "<#C0B3D9>" + Lang.raw(viewer, "admin-gui.click-toggle"),
                "<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.click-preview")));
    }

    private static List<String> detectedGroups(List<String> excluded) {
        List<String> groups = new ArrayList<>(dev.chatalyst.hook.LuckPermsHook.loadedGroups());
        groups.removeIf(group -> excluded.stream().anyMatch(group::equalsIgnoreCase));
        return groups;
    }

    private static String groupLore(Player viewer, String key, List<String> groups) {
        String names = groups.isEmpty() ? "-" : String.join(", ", groups);
        return "<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui." + key, "groups", names);
    }

    private ItemStack tierItem(Player viewer, YamlConfiguration yaml, String key, int index) {
        ConfigurationSection tier = yaml.getConfigurationSection("Join.Fancy.Tiers." + key);
        boolean enabled = tier != null && tier.getBoolean("Enabled", true);
        String sound = tier == null ? "" : tier.getString("Sound", "");
        FancyJoinStyle style = FancyJoinStyle.byId(tier == null ? "none" : tier.getString("Style", "none"));
        String permission = tier == null ? "" : tier.getString("Permission", "");
        List<String> groups = tier == null ? List.of() : tier.getStringList("Groups");
        List<String> lore = tierLore(viewer, key, enabled, sound, style, permission, groups);
        Material icon = enabled ? TIER_ICONS[index % TIER_ICONS.length] : Material.GRAY_DYE;
        String color = enabled ? "<#FBF2FF>" : "<#7B68A0>";
        return MenuItems.item(icon, color + Lang.raw(viewer, "fancy-join-gui.tier", "tier", key), lore);
    }

    private List<String> tierLore(Player viewer, String key, boolean enabled, String sound,
            FancyJoinStyle style, String permission, List<String> groups) {
        List<String> lore = new ArrayList<>();
        lore.add(state(viewer, enabled));
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.permission", "perm",
                permission == null || permission.isEmpty() ? "-" : permission));
        lore.add(groupLore(viewer, "groups", groups.isEmpty() ? List.of(key) : groups));
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.sound", "sound",
                store.prettySound(sound, viewer)));
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.style", "style",
                Lang.raw(viewer, "fancy-join-style.name." + style.id())));
        lore.add("<#E0AAFF>" + Lang.raw(viewer, "fancy-join-gui.click-edit"));
        lore.add("<#C0B3D9>" + Lang.raw(viewer, "fancy-join-gui.click-preview"));
        return lore;
    }

    private static String state(Player viewer, boolean enabled) {
        return "<#C0B3D9>" + Lang.raw(viewer,
                enabled ? "admin-gui.state-on" : "admin-gui.state-off");
    }
}
