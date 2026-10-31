package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

/** Permission checks and click actions for {@link FancyJoinMenu}. */
final class FancyJoinMenuController {

    private final ChatalystPlugin plugin;
    private final FancyJoinMenuStore store;
    private final FancyJoinMenu menu;

    FancyJoinMenuController(ChatalystPlugin plugin, FancyJoinMenuStore store,
            FancyJoinMenu menu) {
        this.plugin = plugin;
        this.store = store;
        this.menu = menu;
    }

    void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.admin")) return;
        switch (slot) {
            case FancyJoinMenu.SLOT_BACK ->
                Menu.open(player, new dev.chatalyst.command.AdminMenu(plugin));
            case FancyJoinMenu.SLOT_ENABLED -> handleMasterClick(player, click);
            case FancyJoinMenu.SLOT_AUTO_DETECT -> handleAutoDetect(player, click);
            case FancyJoinMenu.SLOT_REPLACE_NORMAL -> handleReplaceClick(player, click);
            default -> handleTier(player, slot, click);
        }
    }

    private void handleReplaceClick(Player player, ClickType click) {
        switch (click) {
            case LEFT -> toggleReplaceNormal(player);
            default -> {
            }
        }
    }

    private void toggleReplaceNormal(Player player) {
        String path = "Join.Fancy.Replace_Normal_Message";
        Boolean enabled = store.toggle(player, path, true);
        if (enabled == null) return;
        String label = Lang.raw(player, "fancy-join-gui.replace-normal");
        sendToggle(player, label, enabled);
        reopen(player);
    }

    private void handleMasterClick(Player player, ClickType click) {
        switch (click) {
            case LEFT -> toggleMaster(player);
            default -> {
            }
        }
    }

    private void toggleMaster(Player player) {
        String path = "Join.Fancy.Enabled";
        Boolean enabled = store.toggle(player, path, false);
        if (enabled == null) return;
        sendToggle(player, path, enabled);
        reopen(player);
    }

    private void handleAutoDetect(Player player, ClickType click) {
        if (click == ClickType.SHIFT_RIGHT) {
            player.sendMessage(Lang.get(player, "fancy-join-gui.preview-auto"));
            if (plugin.getFancyJoin() != null) plugin.getFancyJoin().previewAuto(player);
            return;
        }
        if (click != ClickType.LEFT) return;
        String path = "Join.Fancy.Auto_Detect_LuckPerms.Enabled";
        Boolean enabled = store.toggle(player, path, true);
        if (enabled == null) return;
        String label = Lang.raw(player, "fancy-join-gui.auto-detect");
        sendToggle(player, label, enabled);
        reopen(player);
    }

    private void handleTier(Player player, int slot, ClickType click) {
        String key = tierAt(store.load(), slot);
        if (key == null) return;
        // Shift-derecho = preview rapido; cualquier otro click abre el editor del tier (sonidos,
        // estilos, tono, encender/apagar) en su propia GUI.
        if (click == ClickType.SHIFT_RIGHT) {
            preview(player, key);
            return;
        }
        Menu.open(player, new FancyJoinTierMenu(plugin, key));
    }

    private String tierAt(YamlConfiguration yaml, int slot) {
        List<String> tiers = store.tierKeys(yaml);
        if (tiers.isEmpty()) return null;
        int start = FancyJoinMenu.TIER_ROW_CENTER - (tiers.size() - 1) / 2;
        int index = slot - start;
        return index >= 0 && index < tiers.size() ? tiers.get(index) : null;
    }

    private void preview(Player player, String key) {
        player.sendMessage(Lang.get(player, "fancy-join-gui.preview", "tier", key));
        if (plugin.getFancyJoin() != null) plugin.getFancyJoin().preview(player, key);
    }

    private void sendToggle(Player player, String label, boolean enabled) {
        String message = enabled ? "admin-gui.turned-on" : "admin-gui.turned-off";
        player.sendMessage(Lang.get(player, message, "option", label));
    }

    private void reopen(Player player) {
        Menu.open(player, menu);
    }
}
