package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** Thin menu shell; rendering, persistence and click actions live in focused collaborators. */
public final class FancyJoinMenu implements Menu {

    static final int SLOT_ENABLED = 4;
    static final int SLOT_AUTO_DETECT = 20;
    static final int SLOT_BACK = 22;
    static final int SLOT_REPLACE_NORMAL = 24;
    static final int TIER_ROW_CENTER = 13;
    static final int MAX_TIERS = 9;

    /** Presets cycled by right-click; messages.yml still accepts any custom sound key. */
    static final List<String> SOUND_PRESETS = List.of(
            "minecraft:entity.lightning_bolt.thunder",
            "minecraft:entity.wither.spawn",
            "minecraft:entity.ender_dragon.growl",
            "minecraft:entity.elder_guardian.curse",
            "minecraft:item.totem.use",
            "minecraft:ui.toast.challenge_complete",
            "minecraft:entity.player.levelup",
            "minecraft:block.bell.use",
            "minecraft:block.amethyst_block.resonate",
            "");

    private final FancyJoinMenuView view;
    private final FancyJoinMenuController controller;

    public FancyJoinMenu(ChatalystPlugin plugin) {
        FancyJoinMenuStore store = new FancyJoinMenuStore(plugin);
        this.view = new FancyJoinMenuView(store);
        this.controller = new FancyJoinMenuController(plugin, store, this);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("fancy-join-gui.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "fancy-join-gui.title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        view.build(inv, viewer);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        controller.onClick(player, slot, click);
    }

    static String nextSound(String current) {
        return FancyJoinMenuStore.nextSound(current);
    }
}
