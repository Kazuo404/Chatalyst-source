package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/** Elige el sonido de entrada de un tier, con preview al clicar. Sub-GUI del tier editor. */
public final class FancyJoinSoundMenu implements Menu {

    /** Catalogo curado de sonidos "de entrada". El primero ("") es sin sonido. */
    static final List<String> SOUNDS = List.of(
            "",
            "minecraft:entity.lightning_bolt.thunder",
            "minecraft:entity.wither.spawn",
            "minecraft:entity.wither.death",
            "minecraft:entity.wither.shoot",
            "minecraft:entity.ender_dragon.growl",
            "minecraft:entity.ender_dragon.death",
            "minecraft:entity.ender_dragon.flap",
            "minecraft:entity.warden.sonic_boom",
            "minecraft:entity.warden.roar",
            "minecraft:entity.elder_guardian.curse",
            "minecraft:entity.evoker.cast_spell",
            "minecraft:entity.evoker.prepare_summon",
            "minecraft:entity.illusioner.cast_spell",
            "minecraft:entity.blaze.shoot",
            "minecraft:entity.zombie_villager.converted",
            "minecraft:entity.player.levelup",
            "minecraft:entity.villager.celebrate",
            "minecraft:entity.experience_orb.pickup",
            "minecraft:entity.firework_rocket.blast",
            "minecraft:entity.firework_rocket.launch",
            "minecraft:entity.firework_rocket.twinkle",
            "minecraft:entity.allay.item_thrown",
            "minecraft:item.totem.use",
            "minecraft:ui.toast.challenge_complete",
            "minecraft:block.bell.use",
            "minecraft:block.amethyst_block.resonate",
            "minecraft:block.amethyst_block.chime",
            "minecraft:block.beacon.activate",
            "minecraft:block.beacon.power_select",
            "minecraft:block.conduit.activate",
            "minecraft:block.enchantment_table.use",
            "minecraft:block.end_portal.spawn",
            "minecraft:block.respawn_anchor.charge",
            "minecraft:block.note_block.pling",
            "minecraft:block.note_block.bell",
            "minecraft:block.note_block.chime");

    private static final int BACK = 49;

    private final ChatalystPlugin plugin;
    private final FancyJoinMenuStore store;
    private final String tierKey;

    public FancyJoinSoundMenu(ChatalystPlugin plugin, String tierKey) {
        this.plugin = plugin;
        this.store = new FancyJoinMenuStore(plugin);
        this.tierKey = tierKey;
    }

    private String path() {
        return "Join.Fancy.Tiers." + tierKey + ".Sound";
    }

    @Override
    public Component title() {
        return Lang.get("fancy-join-sound.title", "tier", tierKey);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "fancy-join-sound.title", "tier", tierKey);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        String current = normalize(store.load().getString(path(), ""));
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.min(SOUNDS.size(), 45), 0);
        for (int i = 0; i < layout.length; i++) {
            String sound = SOUNDS.get(i);
            inv.setItem(layout[i], soundItem(viewer, sound, normalize(sound).equals(current)));
        }
        inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    private ItemStack soundItem(Player viewer, String sound, boolean selected) {
        boolean none = sound == null || sound.isEmpty();
        Material icon = none ? Material.BARRIER : Material.NOTE_BLOCK;
        String name = (selected ? "<#3DFFA8>" : "<#FBF2FF>") + store.prettySound(sound, viewer);
        List<String> lore = List.of(selected
                ? "<#3DFFA8>" + Lang.raw(viewer, "fancy-join-sound.selected")
                : "<#E0AAFF>" + Lang.raw(viewer, "fancy-join-sound.click-select"));
        ItemStack item = MenuItems.item(icon, name, lore);
        if (selected) {
            item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return item;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.admin")) return;
        if (slot == BACK) {
            Menu.open(player, new FancyJoinTierMenu(plugin, tierKey));
            return;
        }
        int index = dev.chatalyst.gui.MenuLayout.indexAt(slot, Math.min(SOUNDS.size(), 45), 0);
        if (index < 0) return;
        String sound = SOUNDS.get(index);
        double pitch = store.load().getDouble("Join.Fancy.Tiers." + tierKey + ".Pitch", 1.0);
        if (store.set(player, path(), sound)) {
            player.sendMessage(Lang.get(player, "fancy-join-gui.sound-changed",
                    "tier", tierKey, "sound", store.prettySound(sound, player)));
            if (plugin.getFancyJoin() != null) plugin.getFancyJoin().previewSound(player, sound, pitch);
        }
        Menu.open(player, this);
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) return "";
        String clean = key.trim().toLowerCase(Locale.ROOT);
        return clean.indexOf(':') >= 0 ? clean : "minecraft:" + clean;
    }
}
