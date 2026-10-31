package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * Editor de un tier de entrada fancy: enciende/apaga, elige sonido (sub-GUI), estilo visual
 * (sub-GUI) y tono, con preview. Se abre al clicar un tier en {@link FancyJoinMenu}.
 */
public final class FancyJoinTierMenu implements Menu {

    static final double[] PITCHES = {0.5, 0.8, 1.0, 1.2, 1.5, 2.0};

    private static final int HEAD = 4;
    private static final int ENABLED = 11;
    private static final int SOUND = 13;
    private static final int STYLE = 15;
    private static final int PITCH = 22;
    private static final int PREVIEW = 31;
    private static final int BACK = 40;

    private final ChatalystPlugin plugin;
    private final FancyJoinMenuStore store;
    private final String tierKey;

    public FancyJoinTierMenu(ChatalystPlugin plugin, String tierKey) {
        this.plugin = plugin;
        this.store = new FancyJoinMenuStore(plugin);
        this.tierKey = tierKey;
    }

    private String base() {
        return "Join.Fancy.Tiers." + tierKey;
    }

    @Override
    public Component title() {
        return Lang.get("fancy-join-tier.title", "tier", tierKey);
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "fancy-join-tier.title", "tier", tierKey);
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        YamlConfiguration yaml = store.load();
        boolean enabled = yaml.getBoolean(base() + ".Enabled", true);
        String soundKey = yaml.getString(base() + ".Sound", "");
        FancyJoinStyle style = FancyJoinStyle.byId(yaml.getString(base() + ".Style", "none"));
        double pitch = yaml.getDouble(base() + ".Pitch", 1.0);

        inv.setItem(HEAD, MenuItems.item(style.icon(),
                Lang.raw(viewer, "fancy-join-tier.head", "tier", tierKey),
                List.of(state(viewer, enabled),
                        line(viewer, "fancy-join-tier.head-sound", store.prettySound(soundKey, viewer)),
                        line(viewer, "fancy-join-tier.head-style", styleName(viewer, style)),
                        line(viewer, "fancy-join-tier.head-pitch", prettyPitch(pitch)))));

        inv.setItem(ENABLED, MenuItems.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                Lang.raw(viewer, "fancy-join-tier.enabled"),
                List.of(state(viewer, enabled), click(viewer, "admin-gui.click-toggle"))));

        inv.setItem(SOUND, MenuItems.item(Material.JUKEBOX,
                Lang.raw(viewer, "fancy-join-tier.sound"),
                List.of(line(viewer, "fancy-join-tier.sound-current", store.prettySound(soundKey, viewer)),
                        click(viewer, "fancy-join-tier.sound-open"))));

        inv.setItem(STYLE, MenuItems.item(style.icon(),
                Lang.raw(viewer, "fancy-join-tier.style"),
                List.of(line(viewer, "fancy-join-tier.style-current", styleName(viewer, style)),
                        click(viewer, "fancy-join-tier.style-open"))));

        inv.setItem(PITCH, MenuItems.item(Material.NOTE_BLOCK,
                Lang.raw(viewer, "fancy-join-tier.pitch"),
                List.of(line(viewer, "fancy-join-tier.pitch-current", prettyPitch(pitch)),
                        click(viewer, "fancy-join-tier.pitch-cycle"))));

        inv.setItem(PREVIEW, dev.chatalyst.gui.MenuNavigation.preview(viewer));

        inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.admin")) return;
        switch (slot) {
            case ENABLED -> toggleEnabled(player);
            case SOUND -> Menu.open(player, new FancyJoinSoundMenu(plugin, tierKey));
            case STYLE -> Menu.open(player, new FancyJoinStyleMenu(plugin, tierKey));
            case PITCH -> cyclePitch(player);
            case PREVIEW -> {
                player.sendMessage(Lang.get(player, "fancy-join-gui.preview", "tier", tierKey));
                if (plugin.getFancyJoin() != null) plugin.getFancyJoin().preview(player, tierKey);
            }
            case BACK -> Menu.open(player, new FancyJoinMenu(plugin));
            default -> {
            }
        }
    }

    private void toggleEnabled(Player player) {
        String path = base() + ".Enabled";
        store.toggle(player, path, true);
        Menu.open(player, this);
    }

    private void cyclePitch(Player player) {
        String path = base() + ".Pitch";
        store.updateDouble(player, path, 1.0, FancyJoinTierMenu::nextPitch);
        Menu.open(player, this);
    }

    static double nextPitch(double current) {
        for (double pitch : PITCHES) {
            if (pitch > current + 0.01) return pitch;
        }
        return PITCHES[0];
    }

    private static String prettyPitch(double pitch) {
        return String.format(Locale.ROOT, "%.1f", pitch);
    }

    private static String styleName(Player viewer, FancyJoinStyle style) {
        return Lang.raw(viewer, "fancy-join-style.name." + style.id());
    }

    private static String state(Player viewer, boolean enabled) {
        return "<#C0B3D9>" + Lang.raw(viewer, enabled ? "admin-gui.state-on" : "admin-gui.state-off");
    }

    private static String line(Player viewer, String key, String value) {
        return "<#C0B3D9>" + Lang.raw(viewer, key, "value", value);
    }

    private static String click(Player viewer, String key) {
        return "<#C0B3D9>" + Lang.raw(viewer, key);
    }
}
