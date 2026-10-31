package dev.chatalyst.toggle;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.translate.TranslationService;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/** Visual controls for personal feature toggles. */
public final class ToggleMenu implements Menu {

    private static final Map<Integer, ToggleType> SLOTS = Map.of(
            10, ToggleType.PM,
            12, ToggleType.ANNOUNCEMENTS,
            14, ToggleType.MENTIONS,
            16, ToggleType.JOINQUIT,
            22, ToggleType.TRANSLATE
    );

    private final ToggleManager toggles;
    private final TranslationService translation;

    public ToggleMenu(ToggleManager toggles, TranslationService translation) {
        this.toggles = toggles;
        this.translation = translation;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("toggle.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "toggle.gui-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        for (Map.Entry<Integer, ToggleType> entry : SLOTS.entrySet()) {
            ToggleType type = entry.getValue();
            if (type == ToggleType.TRANSLATE && !translationAvailable()) continue;
            boolean enabled = toggles.isEnabled(viewer, type);
            inv.setItem(entry.getKey(), item(viewer, type, enabled));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        ToggleType type = SLOTS.get(slot);
        if (type == null) {
            return;
        }
        if (type == ToggleType.TRANSLATE && !translationAvailable()) return;
        ToggleManager.ToggleResult result = toggles.toggleResult(player, type);
        if (!result.persisted()) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        Menu.open(player, new ToggleMenu(toggles, translation));
    }

    private ItemStack item(Player viewer, ToggleType type, boolean enabled) {
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        String status = Lang.raw(viewer, enabled ? "toggle.gui-enabled" : "toggle.gui-disabled");
        List<String> lore = new java.util.ArrayList<>();
        lore.add(status);
        if (type == ToggleType.JOINQUIT) {
            lore.add(Lang.raw(viewer, "toggle.gui-joinquit-lore"));
        } else if (type == ToggleType.TRANSLATE) {
            lore.add(Lang.raw(viewer, "toggle.gui-translate-lore"));
        }
        lore.add(Lang.raw(viewer, "toggle.gui-click-toggle"));
        return dev.chatalyst.gui.MenuItems.item(material, name(viewer, type), lore);
    }

    private String name(Player viewer, ToggleType type) {
        return switch (type) {
            case PM -> Lang.raw(viewer, "toggle.gui-pm");
            case ANNOUNCEMENTS -> Lang.raw(viewer, "toggle.gui-announcements");
            case MENTIONS -> Lang.raw(viewer, "toggle.gui-mentions");
            case JOINQUIT -> Lang.raw(viewer, "toggle.gui-joinquit");
            case TRANSLATE -> Lang.raw(viewer, "toggle.gui-translate");
        };
    }

    private boolean translationAvailable() {
        return translation != null && translation.enabled();
    }
}
