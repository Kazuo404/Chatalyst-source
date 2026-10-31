package dev.chatalyst.nick;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.tablist.FancyNametagManager;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Elige una animacion de nametag por jugador (/nickcolor -> Animados), paginada. Cada preset
 * viene de {@code Nametag.Fancy.Animation.Presets} en la config: el motor
 * ({@link FancyNametagManager}) dice cuales son validos y guarda la eleccion; aqui solo se leen
 * su Icon/Display/Description para pintarlos. La animacion sustituye el color del apodo pero
 * conserva sus estilos, y solo existe con nametag fancy + {@code Per_Player: true}.
 */
public final class NickAnimationMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_SLOT = 47;
    private static final int PREVIEW_SLOT = 49;
    private static final int NONE_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final NickManager nicks;
    private final boolean forTitle;   // true = anima el TITULO; false = el APODO (independientes)
    private final Map<Integer, String> slots = new HashMap<>();
    private int page;

    public NickAnimationMenu(JavaPlugin plugin, NickManager nicks) {
        this(plugin, nicks, false, 0);
    }

    public NickAnimationMenu(JavaPlugin plugin, NickManager nicks, boolean forTitle) {
        this(plugin, nicks, forTitle, 0);
    }

    private NickAnimationMenu(JavaPlugin plugin, NickManager nicks, boolean forTitle, int page) {
        this.plugin = plugin;
        this.nicks = nicks;
        this.forTitle = forTitle;
        this.page = Math.max(0, page);
    }

    private String titleKey() {
        return forTitle ? "nickcolor.anim-title-title" : "nickcolor.anim-title";
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(titleKey()));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        FancyNametagManager fancy = fancy();
        List<String> ids = fancy != null ? fancy.animationPresetIds() : List.of();
        String current = fancy == null ? null : forTitle
                ? fancy.titleAnimation(viewer.getUniqueId())
                : fancy.playerAnimation(viewer.getUniqueId());

        int pages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int visible = Math.min(PAGE_SIZE, ids.size() - start);
        for (int index = start; index < start + visible; index++) {
            String id = ids.get(index);
            int slot = optionSlot(index - start, visible);
            inv.setItem(slot, presetItem(viewer, id, id.equals(current)));
            slots.put(slot, id);
        }

        inv.setItem(PREVIOUS_SLOT, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(PREVIEW_SLOT, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "nickcolor.anim-preview"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.gui-now") + ": "
                        + (current == null ? "<#7B68A0>" + Lang.raw(viewer, "nickcolor.anim-none")
                                : displayOf(current)))));
        inv.setItem(NONE_SLOT, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "nickcolor.anim-off-item"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, "nickcolor.anim-off-lore"))));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            Menu.open(player, forTitle ? new NickTitleMenu(plugin, nicks)
                    : new NickColorHub(plugin, nicks));
            return;
        }
        if (!player.hasPermission("chatalyst.nick.color")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        int pages = pageCount();
        if (slot == PREVIOUS_SLOT && page > 0) {
            Menu.open(player, new NickAnimationMenu(plugin, nicks, forTitle, page - 1));
            return;
        }
        if (slot == NEXT_SLOT && page + 1 < pages) {
            Menu.open(player, new NickAnimationMenu(plugin, nicks, forTitle, page + 1));
            return;
        }
        FancyNametagManager fancy = fancy();
        if (fancy == null || !fancy.animationChoiceEnabled()) {
            player.sendMessage(Lang.get(player, "nickcolor.anim-disabled"));
            return;
        }
        if (slot == NONE_SLOT) {
            apply(fancy, player, null);
            player.sendMessage(Lang.get(player, "nickcolor.anim-cleared"));
            Menu.open(player, new NickAnimationMenu(plugin, nicks, forTitle, page));
            return;
        }
        String id = slots.get(slot);
        if (id == null) return;
        if (!apply(fancy, player, id)) {
            player.sendMessage(Lang.get(player, "nickcolor.anim-disabled"));
            return;
        }
        player.sendMessage(Lang.get(player, "nickcolor.anim-applied", "name", MM.strip(displayOf(id))));
        Menu.open(player, new NickAnimationMenu(plugin, nicks, forTitle, page));
    }

    /** Aplica al TITULO o al APODO segun el objetivo del menu. */
    private boolean apply(FancyNametagManager fancy, Player player, String presetId) {
        return forTitle ? fancy.setTitleAnimation(player, presetId)
                : fancy.setPlayerAnimation(player, presetId);
    }

    private int pageCount() {
        FancyNametagManager fancy = fancy();
        int n = fancy != null ? fancy.animationPresetIds().size() : 0;
        return Math.max(1, (n + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private ItemStack presetItem(Player viewer, String id, boolean active) {
        String base = "Nametag.Fancy.Animation.Presets." + id;
        Material material = material(plugin.getConfig().getString(base + ".Icon"),
                Material.NETHER_STAR);
        String description = plugin.getConfig().getString(base + ".Description", "");
        List<String> lore = new ArrayList<>();
        if (!description.isBlank()) lore.add("<#C0B3D9>" + description);
        lore.add("");
        lore.add(active ? "<#3DFFA8>" + Lang.raw(viewer, "nickcolor.anim-active")
                : "<#E0AAFF>" + Lang.raw(viewer, "nickcolor.anim-apply"));
        // El Display ya trae su propio color/gradiente: el activo se marca con un guillemet verde
        // (mismo simbolo que el resto de mensajes; sin glifos que algunos clientes pinten emoji).
        String name = (active ? "<#3DFFA8>» " : "") + displayOf(id);
        return MenuItems.item(material, name, lore);
    }

    /** El nombre visible del preset (config Display), o su id capitalizado como respaldo. */
    private String displayOf(String id) {
        String display = plugin.getConfig().getString(
                "Nametag.Fancy.Animation.Presets." + id + ".Display", "");
        if (!display.isBlank()) return display;
        return "<#FBF2FF>" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }

    /** Centrado por fila, igual que el resto de menus de paleta. */
    private int optionSlot(int index, int total) {
        // Filas llenas de lado a lado y la ultima centrada de verdad (con un numero par se deja
        // libre la columna del medio): la paleta queda simetrica con cualquier cantidad.
        return dev.chatalyst.gui.MenuLayout.slots(total, 0)[index];
    }

    private FancyNametagManager fancy() {
        ChatalystPlugin chatalyst = ChatalystPlugin.get();
        return chatalyst != null ? chatalyst.getFancyNametags() : null;
    }
}
