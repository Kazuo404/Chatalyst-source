package dev.chatalyst.nick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.tablist.NametagTitleEditor;
import dev.chatalyst.tablist.NametagTitleManager;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Editor por GUI del titulo flotante y los iconos del nametag ({@code /nametagtitle}). Clic
 * IZQUIERDO en un campo: te pide escribir el valor en el chat (captura via
 * {@link NametagTitleEditor}). Clic DERECHO: lo quita. Reusa las mismas defensas del comando.
 * Solo tiene sentido con Nametag.Fancy activo, asi que el boton de entrada se muestra bajo esa
 * condicion.
 */
public final class NickTitleMenu implements Menu {

    private static final int PREVIEW = 4;
    private static final int TITLE = 11;
    private static final int ICON_LEFT = 13;
    private static final int ICON_RIGHT = 15;
    // Fila inferior simetrica y centrada (19/21/23/25): apariencia + volver.
    private static final int STYLES = 19;  // negrita/cursiva/etc del titulo (independiente del apodo)
    private static final int COLOR = 21;   // color/gradiente del titulo (chatalyst.nametagtitle.color)
    private static final int ANIM = 23;    // animacion del titulo (independiente del apodo)
    private static final int BACK = 25;

    private final JavaPlugin plugin;
    private final NickManager nicks;

    public NickTitleMenu(JavaPlugin plugin, NickManager nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("nametag-title.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "nametag-title.gui-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        NametagTitleManager mgr = manager();
        boolean titleLine = plugin.getConfig().getBoolean("Nametag.Fancy.Title.Enabled", false);

        inv.setItem(PREVIEW, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "nametag-title.gui-preview"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nametag-title.gui-your-tag") + ": "
                        + valueOr(mgr, viewer, NametagTitleManager.ICON_LEFT)
                        + nickOf(viewer)
                        + valueOr(mgr, viewer, NametagTitleManager.ICON_RIGHT))));

        List<String> titleLore = new java.util.ArrayList<>();
        titleLore.add("<#C0B3D9>" + Lang.raw(viewer, "nametag-title.gui-current") + ": "
                + valueOr(mgr, viewer, NametagTitleManager.TITLE));
        if (!titleLine) titleLore.add("<#FF9F1C>" + Lang.raw(viewer, "nametag-title.gui-title-off"));
        titleLore.add("");
        titleLore.add(Lang.raw(viewer, "nametag-title.gui-set"));
        titleLore.add(Lang.raw(viewer, "nametag-title.gui-remove"));
        inv.setItem(TITLE, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "nametag-title.gui-title-item"), titleLore));

        inv.setItem(ICON_LEFT, fieldItem(mgr, viewer, NametagTitleManager.ICON_LEFT,
                Material.SHIELD, "nametag-title.gui-iconleft-item"));
        inv.setItem(ICON_RIGHT, fieldItem(mgr, viewer, NametagTitleManager.ICON_RIGHT,
                Material.SHIELD, "nametag-title.gui-iconright-item"));

        inv.setItem(STYLES, MenuItems.item(Material.ENCHANTED_BOOK,
                Lang.raw(viewer, "nametag-title.style-open"),
                List.of(Lang.raw(viewer, "nametag-title.style-open-lore"))));
        if (viewer.hasPermission("chatalyst.nametagtitle.color")) {
            inv.setItem(COLOR, MenuItems.item(Material.GLOW_INK_SAC,
                    Lang.raw(viewer, "nametag-title.color-open"),
                    List.of(Lang.raw(viewer, "nametag-title.color-open-lore"))));
        }
        if (animationAvailable()) {
            inv.setItem(ANIM, MenuItems.item(Material.FIREWORK_STAR,
                    Lang.raw(viewer, "nametag-title.anim-open"),
                    List.of(Lang.raw(viewer, "nametag-title.anim-open-lore"))));
        }
        inv.setItem(BACK, MenuNavigation.back(viewer));
    }

    private ItemStack fieldItem(NametagTitleManager mgr, Player viewer, int field,
                                Material material, String nameKey) {
        return MenuItems.item(material, Lang.raw(viewer, nameKey), List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "nametag-title.gui-current") + ": "
                        + valueOr(mgr, viewer, field),
                "",
                Lang.raw(viewer, "nametag-title.gui-set"),
                Lang.raw(viewer, "nametag-title.gui-remove")));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Menu.open(player, new NickColorHub(plugin, nicks));
            return;
        }
        if (slot == COLOR) {
            if (player.hasPermission("chatalyst.nametagtitle.color")) {
                Menu.open(player, new NickTitleColorMenu(plugin, nicks));
            }
            return;
        }
        if (slot == ANIM) {
            if (animationAvailable()) {
                Menu.open(player, new NickAnimationMenu(plugin, nicks, true)); // true = titulo
            }
            return;
        }
        if (slot == STYLES) {
            Menu.open(player, new NickTitleStyleMenu(plugin, nicks));
            return;
        }
        if (!player.hasPermission("chatalyst.nametagtitle")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        int field = switch (slot) {
            case TITLE -> NametagTitleManager.TITLE;
            case ICON_LEFT -> NametagTitleManager.ICON_LEFT;
            case ICON_RIGHT -> NametagTitleManager.ICON_RIGHT;
            default -> -1;
        };
        if (field < 0) return;
        NametagTitleEditor editor = editor();
        if (editor == null) return;
        if (click.isRightClick()) {
            editor.applyDirect(player, field, "off"); // quitar el campo
            Menu.open(player, new NickTitleMenu(plugin, nicks)); // refresca los valores
            return;
        }
        // Clic izquierdo: cerramos y capturamos el texto por chat.
        player.closeInventory();
        editor.begin(player, field);
    }

    /** El valor actual del campo, ya sanitizado (seguro de renderizar), o "(ninguno)" si vacio. */
    private static String valueOr(NametagTitleManager mgr, Player viewer, int field) {
        String value = mgr != null ? mgr.field(viewer.getUniqueId(), field) : "";
        return value.isEmpty()
                ? "<#7B68A0>" + Lang.raw(viewer, "nametag-title.gui-none") : value;
    }

    private String nickOf(Player viewer) {
        String nick = nicks.getNick(viewer.getUniqueId());
        return nick != null && !nick.isEmpty() ? nick : viewer.getName();
    }

    /** True si los jugadores pueden elegir animacion (Per_Player + presets): habilita el boton. */
    private static boolean animationAvailable() {
        ChatalystPlugin plugin = ChatalystPlugin.get();
        return plugin != null && plugin.getFancyNametags() != null
                && plugin.getFancyNametags().animationChoiceEnabled();
    }

    private static NametagTitleManager manager() {
        ChatalystPlugin plugin = ChatalystPlugin.get();
        return plugin != null ? plugin.getNametagTitleManager() : null;
    }

    private static NametagTitleEditor editor() {
        ChatalystPlugin plugin = ChatalystPlugin.get();
        return plugin != null ? plugin.getNametagTitleEditor() : null;
    }
}
