package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Editor visual de la identidad y ventana temporal de una temporada. */
public final class TagSeasonMenu implements Menu {

    private static final int SUMMARY = 4;
    private static final int ID = 20;
    private static final int START = 22;
    private static final int END = 24;
    private static final int BACK = 45;
    private static final int CLOSE = 53;   // espejo de BACK: barra simetrica
    private static final int RESET = 49;
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;
    private boolean resetArmed;

    public TagSeasonMenu(ChatalystPlugin plugin, TagManager tags,
                         String titleId, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-season-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-season-title");
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
            inventory.setItem(SUMMARY, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "tag.admin-gone"), List.of()));
            inventory.setItem(BACK, MenuNavigation.back(viewer));
            inventory.setItem(CLOSE, MenuNavigation.close(viewer));
            return;
        }
        TagSeason season = definition.season();
        String status = seasonStatus(viewer, season, System.currentTimeMillis());
        inventory.setItem(SUMMARY, MenuItems.item(
                season.enabled() ? Material.CLOCK : Material.GRAY_DYE,
                Lang.raw(viewer, "tag.admin-season-summary"),
                List.of(status,
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-season-reset-rule"))));
        inventory.setItem(ID, field(viewer, Material.NAME_TAG,
                "tag.admin-season-id", season.enabled() ? season.id() : "-"));
        inventory.setItem(START, field(viewer, Material.SUNFLOWER,
                "tag.admin-season-start", format(season.startsAtMillis())));
        inventory.setItem(END, field(viewer, Material.RECOVERY_COMPASS,
                "tag.admin-season-end", format(season.endsAtMillis())));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(CLOSE, MenuNavigation.close(viewer));
        inventory.setItem(RESET, MenuItems.item(resetArmed
                        ? Material.LAVA_BUCKET : Material.BARRIER,
                Lang.raw(viewer, resetArmed
                        ? "tag.admin-season-reset-confirm"
                        : "tag.admin-season-reset"),
                List.of(Lang.raw(viewer, resetArmed
                        ? "tag.admin-season-reset-confirm-lore"
                        : "tag.admin-season-reset-lore"))));
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
        if (slot == BACK) {
            back(player);
        } else if (slot == ID) {
            prompt(player, TagAdminPrompt.Field.SEASON_ID);
        } else if (slot == START) {
            prompt(player, TagAdminPrompt.Field.SEASON_START);
        } else if (slot == END) {
            prompt(player, TagAdminPrompt.Field.SEASON_END);
        } else if (slot == RESET) {
            if (!resetArmed) {
                resetArmed = true;
                Menu.open(player, this);
                return;
            }
            if (!tags.catalog().update(definition.withSeason(TagSeason.none()))) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            } else {
                audit(player, "tag season reset", titleId);
            }
            Menu.open(player, this);
        }
    }

    private void prompt(Player player, TagAdminPrompt.Field field) {
        TagAdminPrompt prompt = plugin.getTagAdminPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.begin(player, titleId, returnPage, field);
    }

    private org.bukkit.inventory.ItemStack field(Player viewer, Material material,
                                                 String key, String value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + (value.isBlank() ? "-" : value),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-change")));
    }

    static String format(long millis) {
        if (millis <= 0L) return "-";
        return DISPLAY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static String seasonStatus(Player viewer, TagSeason season, long now) {
        String key;
        if (!season.enabled()) key = "tag.admin-season-status-none";
        else if (!season.validWindow()) key = "tag.admin-season-status-invalid";
        else if (season.upcoming(now)) key = "tag.admin-season-status-upcoming";
        else if (season.ended(now)) key = "tag.admin-season-status-ended";
        else key = "tag.admin-season-status-active";
        return Lang.raw(viewer, key);
    }

    private void back(Player player) {
        Menu.open(player, new TagAdminEditMenu(plugin, tags, titleId, returnPage));
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, action, detail);
        }
    }
}
