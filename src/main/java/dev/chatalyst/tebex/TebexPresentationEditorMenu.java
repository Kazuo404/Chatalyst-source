package dev.chatalyst.tebex;

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
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/** Complete visual editor for a global or rank-specific purchase presentation. */
public final class TebexPresentationEditorMenu implements Menu {

    // Simetrico por temas (KZ): fila 0 heredar/entrega | resumen | estilo; fila 1 general;
    // fila 2 chat | barra de accion; fila 3 titulo; fila 4 notificacion + sonido (completa);
    // abajo volver | vista previa | restablecer.
    private static final int SUMMARY = 4;
    // "Heredar" (rango) y "Entrega sin conexion" (global) nunca salen a la vez: comparten el
    // hueco de arriba a la izquierda. OFFLINE_DELIVERY es un id interno; el clic se traduce.
    private static final int TOP_LEFT = 2;
    private static final int INHERIT = TOP_LEFT;
    private static final int OFFLINE_DELIVERY = 1002;
    private static final int PRESET = 6;
    private static final int ENABLED = 11;
    private static final int AUDIENCE = 13;
    private static final int FIREWORK = 15;
    private static final int CHAT_ENABLED = 19;
    private static final int CHAT_MESSAGE = 20;
    private static final int ACTION_ENABLED = 24;
    private static final int ACTION_MESSAGE = 25;
    private static final int TITLE_ENABLED = 28;
    private static final int TITLE_TEXT = 29;
    private static final int TITLE_SUBTITLE = 30;
    private static final int FADE_IN = 32;
    private static final int STAY = 33;
    private static final int FADE_OUT = 34;
    private static final int TOAST_ENABLED = 36;
    private static final int TOAST_TITLE = 37;
    private static final int TOAST_DESCRIPTION = 38;
    private static final int TOAST_ICON = 39;
    private static final int TOAST_FRAME = 40;
    private static final int SOUND_ENABLED = 41;
    private static final int SOUND_KEY = 42;
    private static final int VOLUME = 43;
    private static final int PITCH = 44;
    private static final int BACK = 45;
    private static final int PREVIEW = 49;
    private static final int RESET = 53;

    private final ChatalystPlugin plugin;
    private final TebexPresentationService presentations;
    private final String rankId;
    private final int returnPage;
    private boolean resetArmed;

    public TebexPresentationEditorMenu(ChatalystPlugin plugin,
                                       TebexPresentationService presentations,
                                       String rankId, int returnPage) {
        this.plugin = plugin;
        this.presentations = presentations;
        this.rankId = rankId;
        this.returnPage = Math.max(0, returnPage);
    }

    @Override
    public Component title() {
        return Lang.get("tebex-presentation.editor-title",
                "rank", rankId == null ? "GLOBAL" : rankId);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "tebex-presentation.editor-title",
                "rank", rankId == null
                        ? Lang.raw(viewer, "tebex-presentation.global-short") : rankId);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        TebexPresentation value = presentations.presentation(rankId);
        inventory.setItem(SUMMARY, MenuItems.item(value.toastIconMaterial(),
                rankId == null
                        ? Lang.raw(viewer, "tebex-presentation.global-name")
                        : "<#E4A8FF>" + rankId,
                List.of(
                        value.silent()
                                ? "<#7B68A0>" + Lang.raw(viewer,
                                "tebex-presentation.state-silent")
                                : "<#3DFFA8>" + Lang.raw(viewer,
                                "tebex-presentation.state-active"),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.summary-audience", "audience",
                                TebexPresentationMenu.audience(viewer, value.audience())),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.summary-preset", "preset",
                                TebexPresentationMenu.preset(viewer, value.preset())),
                        "<#7B68A0>" + Lang.raw(viewer,
                                "tebex-presentation.placeholders"))));
        if (rankId != null) {
            inventory.setItem(INHERIT, toggle(viewer,
                    value.inherited() ? Material.ENDER_CHEST : Material.CRAFTING_TABLE,
                    "tebex-presentation.inherit", value.inherited(),
                    value.inherited()
                            ? "tebex-presentation.inherit-on"
                            : "tebex-presentation.inherit-off"));
        }
        inventory.setItem(ENABLED, toggle(viewer, Material.LEVER,
                "tebex-presentation.enabled", value.enabled(), null));
        inventory.setItem(AUDIENCE, MenuItems.item(Material.SPYGLASS,
                Lang.raw(viewer, "tebex-presentation.audience"),
                List.of("<#FBF2FF>" + TebexPresentationMenu.audience(
                                viewer, value.audience()),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.click-cycle"))));
        inventory.setItem(PRESET, MenuItems.item(Material.NETHER_STAR,
                Lang.raw(viewer, "tebex-presentation.preset"),
                List.of("<#FBF2FF>" + TebexPresentationMenu.preset(viewer, value.preset()),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.preset-lore"))));
        inventory.setItem(FIREWORK, toggle(viewer, Material.FIREWORK_ROCKET,
                "tebex-presentation.firework", value.fireworkEnabled(), null));
        if (rankId == null) {
            inventory.setItem(TOP_LEFT, toggle(viewer, Material.CHEST,
                    "tebex-presentation.offline-delivery",
                    plugin.getConfig().getBoolean(
                            TebexPresentation.GLOBAL_PATH + ".Offline_Delivery", true),
                    "tebex-presentation.offline-delivery-lore"));
        }
        inventory.setItem(CHAT_ENABLED, toggle(viewer, Material.PAPER,
                "tebex-presentation.chat", value.chatEnabled(), null));
        inventory.setItem(CHAT_MESSAGE, edit(viewer, Material.FEATHER,
                "tebex-presentation.chat-message", value.chatMessage().forViewer(viewer)));
        inventory.setItem(TOAST_ENABLED, toggle(viewer, Material.KNOWLEDGE_BOOK,
                "tebex-presentation.toast", value.toastEnabled(), null));
        inventory.setItem(TOAST_TITLE, edit(viewer, Material.OAK_SIGN,
                "tebex-presentation.toast-title", value.toastTitle().forViewer(viewer)));
        inventory.setItem(TOAST_DESCRIPTION, edit(viewer, Material.WRITABLE_BOOK,
                "tebex-presentation.toast-description",
                value.toastDescription().forViewer(viewer)));
        inventory.setItem(TITLE_ENABLED, toggle(viewer, Material.BEACON,
                "tebex-presentation.title", value.titleEnabled(), null));
        inventory.setItem(TITLE_TEXT, edit(viewer, Material.GLOW_INK_SAC,
                "tebex-presentation.title-text", value.title().forViewer(viewer)));
        inventory.setItem(TITLE_SUBTITLE, edit(viewer, Material.INK_SAC,
                "tebex-presentation.title-subtitle", value.subtitle().forViewer(viewer)));
        inventory.setItem(FADE_IN, ticks(viewer, Material.LIME_DYE,
                "tebex-presentation.fade-in", value.fadeInTicks()));
        inventory.setItem(STAY, ticks(viewer, Material.CLOCK,
                "tebex-presentation.stay", value.stayTicks()));
        inventory.setItem(FADE_OUT, ticks(viewer, Material.PURPLE_DYE,
                "tebex-presentation.fade-out", value.fadeOutTicks()));
        inventory.setItem(ACTION_ENABLED, toggle(viewer, Material.EXPERIENCE_BOTTLE,
                "tebex-presentation.actionbar", value.actionBarEnabled(), null));
        inventory.setItem(ACTION_MESSAGE, edit(viewer, Material.NAME_TAG,
                "tebex-presentation.actionbar-message",
                value.actionBarMessage().forViewer(viewer)));
        inventory.setItem(SOUND_ENABLED, toggle(viewer, Material.NOTE_BLOCK,
                "tebex-presentation.sound", value.soundEnabled(), null));
        inventory.setItem(SOUND_KEY, edit(viewer, Material.JUKEBOX,
                "tebex-presentation.sound-key", value.soundKey()));
        inventory.setItem(TOAST_ICON, edit(viewer, value.toastIconMaterial(),
                "tebex-presentation.toast-icon", value.toastIcon()));
        inventory.setItem(TOAST_FRAME, MenuItems.item(Material.ITEM_FRAME,
                Lang.raw(viewer, "tebex-presentation.toast-frame"),
                List.of("<#FBF2FF>" + value.toastFrame(),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.click-cycle"))));
        inventory.setItem(VOLUME, number(viewer, Material.GOAT_HORN,
                "tebex-presentation.volume", value.soundVolume()));
        inventory.setItem(PITCH, number(viewer, Material.REPEATER,
                "tebex-presentation.pitch", value.soundPitch()));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(PREVIEW, MenuNavigation.preview(viewer));
        inventory.setItem(RESET, MenuItems.item(resetArmed
                        ? Material.LAVA_BUCKET : Material.REDSTONE,
                Lang.raw(viewer, resetArmed
                        ? "tebex-presentation.reset-confirm"
                        : "tebex-presentation.reset"),
                List.of(Lang.raw(viewer, resetArmed
                        ? "tebex-presentation.reset-confirm-lore"
                        : "tebex-presentation.reset-lore"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == TOP_LEFT && rankId == null) slot = OFFLINE_DELIVERY;
        if (!allowed(player)) return;
        TebexPresentation value = presentations.presentation(rankId);
        switch (slot) {
            case INHERIT -> {
                if (rankId != null) {
                    presentations.setInherited(player, rankId, !value.inherited());
                    reopen(player);
                }
            }
            case ENABLED -> update(player, "Enabled", !value.enabled(), false);
            case AUDIENCE -> update(player, "Audience",
                    value.audience().next().name(), false);
            case PRESET -> {
                presentations.applyPreset(player, rankId, value.preset().next());
                reopen(player);
            }
            case FIREWORK -> update(player, "Firework.Enabled",
                    !value.fireworkEnabled(), true);
            case OFFLINE_DELIVERY -> {
                if (rankId == null) {
                    boolean enabled = plugin.getConfig().getBoolean(
                            TebexPresentation.GLOBAL_PATH + ".Offline_Delivery", true);
                    update(player, "Offline_Delivery", !enabled, false);
                }
            }
            case CHAT_ENABLED -> update(player, "Chat.Enabled",
                    !value.chatEnabled(), true);
            case CHAT_MESSAGE -> prompt(player,
                    TebexPresentationPrompt.Field.CHAT_MESSAGE);
            case TOAST_ENABLED -> update(player, "Toast.Enabled",
                    !value.toastEnabled(), true);
            case TOAST_TITLE -> prompt(player,
                    TebexPresentationPrompt.Field.TOAST_TITLE);
            case TOAST_DESCRIPTION -> prompt(player,
                    TebexPresentationPrompt.Field.TOAST_DESCRIPTION);
            case TITLE_ENABLED -> update(player, "Title.Enabled",
                    !value.titleEnabled(), true);
            case TITLE_TEXT -> prompt(player,
                    TebexPresentationPrompt.Field.TITLE);
            case TITLE_SUBTITLE -> prompt(player,
                    TebexPresentationPrompt.Field.SUBTITLE);
            case FADE_IN -> update(player, "Title.Fade_In_Ticks",
                    bounded(value.fadeInTicks() + tickDelta(click), 0, 200), true);
            case STAY -> update(player, "Title.Stay_Ticks",
                    bounded(value.stayTicks() + tickDelta(click), 1, 1_200), true);
            case FADE_OUT -> update(player, "Title.Fade_Out_Ticks",
                    bounded(value.fadeOutTicks() + tickDelta(click), 0, 200), true);
            case ACTION_ENABLED -> update(player, "ActionBar.Enabled",
                    !value.actionBarEnabled(), true);
            case ACTION_MESSAGE -> prompt(player,
                    TebexPresentationPrompt.Field.ACTIONBAR);
            case SOUND_ENABLED -> update(player, "Sound.Enabled",
                    !value.soundEnabled(), true);
            case SOUND_KEY -> prompt(player,
                    TebexPresentationPrompt.Field.SOUND_KEY);
            case TOAST_ICON -> prompt(player,
                    TebexPresentationPrompt.Field.TOAST_ICON);
            case TOAST_FRAME -> update(player, "Toast.Frame",
                    nextFrame(value.toastFrame(), click.isRightClick()), true);
            case VOLUME -> update(player, "Sound.Volume",
                    bounded(value.soundVolume() + delta(click), 0.0f, 10.0f), true);
            case PITCH -> update(player, "Sound.Pitch",
                    bounded(value.soundPitch() + delta(click), 0.5f, 2.0f), true);
            case PREVIEW -> presentations.preview(player, rankId);
            case BACK -> Menu.open(player,
                    new TebexPresentationMenu(plugin, presentations, returnPage));
            case RESET -> reset(player);
            default -> {
            }
        }
    }

    private void reset(Player player) {
        if (!resetArmed) {
            resetArmed = true;
            reopen(player);
            return;
        }
        presentations.reset(player, rankId);
        resetArmed = false;
        reopen(player);
    }

    private void prompt(Player player, TebexPresentationPrompt.Field field) {
        TebexPresentationPrompt prompt = plugin.getTebexPresentationPrompt();
        if (prompt == null) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        prompt.begin(player, rankId, returnPage, field);
    }

    private void update(Player player, String path, Object value, boolean markCustom) {
        presentations.set(player, rankId, path, value, markCustom);
        reopen(player);
    }

    private void reopen(Player player) {
        Menu.open(player, this);
    }

    private ItemStack toggle(Player viewer, Material material, String key,
                             boolean enabled, String detailKey) {
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add(enabled
                ? "<#3DFFA8>" + Lang.raw(viewer, "tebex-presentation.state-on")
                : "<#FF4D6D>" + Lang.raw(viewer, "tebex-presentation.state-off"));
        if (detailKey != null) lore.add("<#C0B3D9>" + Lang.raw(viewer, detailKey));
        lore.add("<#7B68A0>" + Lang.raw(viewer, "tebex-presentation.click-toggle"));
        return MenuItems.item(enabled ? material : Material.GRAY_DYE,
                Lang.raw(viewer, key), lore);
    }

    private ItemStack edit(Player viewer, Material material, String key, String value) {
        String shown = value == null || value.isBlank() ? "-"
                : value.length() > 80 ? value.substring(0, 77) + "..." : value;
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + shown,
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.click-edit"),
                        "<#7B68A0>" + Lang.raw(viewer,
                                "tebex-presentation.placeholders")));
    }

    private ItemStack number(Player viewer, Material material, String key, float value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + String.format(Locale.ROOT, "%.1f", value),
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.number-lore")));
    }

    private ItemStack ticks(Player viewer, Material material, String key, int value) {
        return MenuItems.item(material, Lang.raw(viewer, key),
                List.of("<#FBF2FF>" + value + " ticks",
                        "<#C0B3D9>" + Lang.raw(viewer,
                                "tebex-presentation.ticks-lore")));
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.admin")
                || player.hasPermission("chatalyst.admin.tebex.presentation")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }

    private static float delta(ClickType click) {
        float amount = click.isShiftClick() ? 0.5f : 0.1f;
        return click.isRightClick() ? -amount : amount;
    }

    private static float bounded(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int tickDelta(ClickType click) {
        int amount = click.isShiftClick() ? 20 : 5;
        return click.isRightClick() ? -amount : amount;
    }

    private static String nextFrame(String current, boolean backwards) {
        List<String> frames = List.of("task", "goal", "challenge");
        int index = Math.max(0, frames.indexOf(current.toLowerCase(Locale.ROOT)));
        int next = Math.floorMod(index + (backwards ? -1 : 1), frames.size());
        return frames.get(next);
    }
}
