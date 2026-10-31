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

/** Personaliza el toast nativo, mensaje y sonido de obtencion de un titulo. */
public final class TagAchievementMenu implements Menu {

    private static final int PREVIEW = 4;
    private static final int TOAST_ENABLED = 10;
    private static final int TOAST_TITLE = 12;
    private static final int TOAST_DESCRIPTION = 14;
    private static final int TOAST_ICON = 16;
    private static final int FRAME = 20;
    private static final int CHAT_ENABLED = 22;
    private static final int CHAT_MESSAGE = 24;
    private static final int SOUND_ENABLED = 28;
    private static final int SOUND_KEY = 30;
    private static final int VOLUME = 32;
    private static final int PITCH = 34;
    private static final int BACK = 45;
    private static final int TEST = 49;
    private static final int RESET = 53;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final String titleId;
    private final int returnPage;
    private boolean resetArmed;

    public TagAchievementMenu(ChatalystPlugin plugin, TagManager tags,
                              String titleId, int returnPage) {
        this.plugin = plugin;
        this.tags = tags;
        this.titleId = titleId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tag.admin-achievement-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tag.admin-achievement-title");
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
            return;
        }
        TagAchievement achievement = definition.achievement();
        String titleValue = achievement.toastTitle().isBlank()
                ? Lang.raw(viewer, "tag.admin-achievement-default",
                        "value", definition.displayName())
                : achievement.toastTitle();
        String descriptionValue = achievement.toastDescription().isBlank()
                ? Lang.raw(viewer, "tag.admin-achievement-default",
                        "value", Lang.raw(viewer, "tag.achievement-toast-description",
                                "rarity", Lang.raw(viewer, "tag.rarity-"
                                        + definition.rarity().name().toLowerCase(
                                        java.util.Locale.ROOT)),
                                "category", MM.escape(definition.category())))
                : achievement.toastDescription();
        String previewTitle = achievement.toastTitle().isBlank()
                ? Lang.raw(viewer, "tag.achievement-toast-title",
                        "title", MM.escape(definition.displayName()))
                : achievement.render(achievement.toastTitle(), viewer, definition);
        String previewDescription = achievement.toastDescription().isBlank()
                ? Lang.raw(viewer, "tag.achievement-toast-description",
                        "rarity", Lang.raw(viewer, "tag.rarity-"
                                + definition.rarity().name().toLowerCase(
                                java.util.Locale.ROOT)),
                        "category", MM.escape(definition.category()))
                : achievement.render(
                        achievement.toastDescription(), viewer, definition);
        inventory.setItem(PREVIEW, MenuItems.item(achievement.toastIcon(), previewTitle,
                List.of(previewDescription,
                        "<#7B68A0>" + Lang.raw(viewer, "tag.admin-achievement-frame-value",
                                "frame", frame(viewer, achievement.frame())))));
        inventory.setItem(TOAST_ENABLED, toggle(viewer, Material.KNOWLEDGE_BOOK,
                "tag.admin-achievement-toast", achievement.toastEnabled()));
        inventory.setItem(TOAST_TITLE, editItem(viewer, Material.OAK_SIGN,
                "tag.admin-achievement-toast-title", titleValue));
        inventory.setItem(TOAST_DESCRIPTION, editItem(viewer, Material.WRITABLE_BOOK,
                "tag.admin-achievement-toast-description", descriptionValue));
        inventory.setItem(TOAST_ICON, editItem(viewer, achievement.toastIcon(),
                "tag.admin-achievement-toast-icon", achievement.toastIcon().name()));
        inventory.setItem(FRAME, MenuItems.item(Material.ITEM_FRAME,
                Lang.raw(viewer, "tag.admin-achievement-frame"),
                List.of("<#FBF2FF>" + frame(viewer, achievement.frame()),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-cycle-lore"))));
        inventory.setItem(CHAT_ENABLED, toggle(viewer, Material.PAPER,
                "tag.admin-achievement-chat", achievement.chatEnabled()));
        inventory.setItem(CHAT_MESSAGE, editItem(viewer, Material.FEATHER,
                "tag.admin-achievement-chat-message",
                achievement.chatMessage().isBlank()
                        ? Lang.raw(viewer, "tag.admin-achievement-localized-default")
                        : achievement.chatMessage()));
        inventory.setItem(SOUND_ENABLED, toggle(viewer, Material.NOTE_BLOCK,
                "tag.admin-achievement-sound", achievement.soundEnabled()));
        inventory.setItem(SOUND_KEY, editItem(viewer, Material.JUKEBOX,
                "tag.admin-achievement-sound-key", achievement.soundKey()));
        inventory.setItem(VOLUME, numberItem(viewer, Material.GOAT_HORN,
                "tag.admin-achievement-volume", achievement.soundVolume()));
        inventory.setItem(PITCH, numberItem(viewer, Material.REPEATER,
                "tag.admin-achievement-pitch", achievement.soundPitch()));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(TEST, MenuItems.item(Material.FIREWORK_ROCKET,
                Lang.raw(viewer, "tag.admin-achievement-test"),
                List.of(Lang.raw(viewer, "tag.admin-achievement-test-lore"))));
        inventory.setItem(RESET, MenuItems.item(resetArmed
                        ? Material.LAVA_BUCKET : Material.REDSTONE,
                Lang.raw(viewer, resetArmed
                        ? "tag.admin-achievement-reset-confirm"
                        : "tag.admin-achievement-reset"),
                List.of(Lang.raw(viewer, resetArmed
                        ? "tag.admin-achievement-reset-confirm-lore"
                        : "tag.admin-achievement-reset-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!allowed(player)) return;
        TagDefinition definition = tags.catalog().byId(titleId);
        if (definition == null) {
            if (slot == BACK) back(player);
            return;
        }
        TagAchievement achievement = definition.achievement();
        switch (slot) {
            case TOAST_ENABLED -> update(player, definition.withAchievement(
                    achievement.withToastEnabled(!achievement.toastEnabled())));
            case TOAST_TITLE -> prompt(
                    player, TagAdminPrompt.Field.ACHIEVEMENT_TOAST_TITLE);
            case TOAST_DESCRIPTION -> prompt(
                    player, TagAdminPrompt.Field.ACHIEVEMENT_TOAST_DESCRIPTION);
            case TOAST_ICON -> prompt(
                    player, TagAdminPrompt.Field.ACHIEVEMENT_TOAST_ICON);
            case FRAME -> update(player, definition.withAchievement(
                    achievement.withFrame(achievement.frame()
                            .step(click.isRightClick() ? -1 : 1))));
            case CHAT_ENABLED -> update(player, definition.withAchievement(
                    achievement.withChatEnabled(!achievement.chatEnabled())));
            case CHAT_MESSAGE -> prompt(
                    player, TagAdminPrompt.Field.ACHIEVEMENT_CHAT_MESSAGE);
            case SOUND_ENABLED -> update(player, definition.withAchievement(
                    achievement.withSoundEnabled(!achievement.soundEnabled())));
            case SOUND_KEY -> prompt(
                    player, TagAdminPrompt.Field.ACHIEVEMENT_SOUND_KEY);
            case VOLUME -> update(player, definition.withAchievement(
                    achievement.withSoundVolume(achievement.soundVolume()
                            + delta(click))));
            case PITCH -> update(player, definition.withAchievement(
                    achievement.withSoundPitch(achievement.soundPitch()
                            + delta(click))));
            case BACK -> back(player);
            case TEST -> TagUnlockProcessor.preview(plugin, player, definition);
            case RESET -> reset(player, definition);
            default -> {
            }
        }
    }

    private void reset(Player player, TagDefinition definition) {
        if (!resetArmed) {
            resetArmed = true;
            Menu.open(player, this);
            return;
        }
        update(player, definition.withAchievement(
                TagAchievement.defaults(definition.icon())));
    }

    private float delta(ClickType click) {
        float amount = click.isShiftClick() ? 0.5f : 0.1f;
        return click.isRightClick() ? -amount : amount;
    }

    private void prompt(Player player, TagAdminPrompt.Field field) {
        TagAdminPrompt prompt = plugin.getTagAdminPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.begin(player, titleId, returnPage, field);
    }

    private void update(Player player, TagDefinition updated) {
        if (!tags.catalog().update(updated)) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
        } else if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(
                    player, "tag achievement edit", titleId);
        }
        Menu.open(player, this);
    }

    private org.bukkit.inventory.ItemStack editItem(Player viewer, Material material,
                                                    String key, String value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + (value == null || value.isBlank() ? "-" : value),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-change"),
                        "<#7B68A0>" + Lang.raw(viewer,
                                "tag.admin-achievement-placeholders")));
    }

    private org.bukkit.inventory.ItemStack toggle(Player viewer, Material material,
                                                  String key, boolean enabled) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of(enabled
                                ? "<#3DFFA8>" + Lang.raw(viewer, "tag.admin-enabled")
                                : "<#FF4D6D>" + Lang.raw(viewer, "tag.admin-disabled"),
                        "<#C0B3D9>" + Lang.raw(viewer, "tag.admin-click-toggle")));
    }

    private org.bukkit.inventory.ItemStack numberItem(Player viewer, Material material,
                                                      String key, float value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + String.format(java.util.Locale.ROOT, "%.1f", value),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tag.admin-achievement-number-lore")));
    }

    private String frame(Player viewer, TagAchievementFrame frame) {
        return Lang.raw(viewer, "tag.admin-achievement-frame-"
                + frame.name().toLowerCase(java.util.Locale.ROOT));
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
