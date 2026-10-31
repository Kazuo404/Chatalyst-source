package dev.chatalyst.staff.tools;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffModerationModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /banstyle: como se anuncia un ban en la red. Elegir un diseno lo guarda en moderation.yml
 * y te lo ensena en el acto (vista previa solo para ti); sonido, titulo y animacion se
 * recorren con clic. Lo lee {@link PunishmentBroadcast} en vivo: no hace falta recargar.
 *
 * <pre>
 *  fila 0: . . . . [info] . . . .
 *  fila 1: . [linea] . [bloque] . [epico] . [alerta] .
 *  fila 3: . [sonido] . [titulo] . [animacion] . [red] .
 *  fila 4: . . . . [vista previa] . . . .
 * </pre>
 */
public final class BroadcastStyleMenu implements Menu {

    private static final int INFO = 4;
    private static final int[] STYLE_SLOTS = {10, 12, 14, 16};
    private static final int SOUND = 28;
    private static final int TITLE = 30;
    private static final int ANIMATION = 32;
    private static final int NETWORK = 34;
    private static final int PREVIEW = 40;

    private final StaffModerationModule module;

    public BroadcastStyleMenu(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public Component title() {
        return Lang.get("staff-tools.banstyle.title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-tools.banstyle.title");
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        var config = module.plugin().getConfig();
        PunishmentBroadcast.Style current = PunishmentBroadcast.style(module);
        inv.setItem(INFO, item(viewer, Material.BOOK, "staff-tools.banstyle.info.name", Lang.rawList(viewer,
                "staff-tools.banstyle.info.lore", "style", Lang.raw(viewer, "staff-tools.banstyle.style." + current.id() + ".name"))));

        PunishmentBroadcast.Style[] styles = PunishmentBroadcast.Style.values();
        Material[] icons = {Material.PAPER, Material.OAK_SIGN, Material.NETHER_STAR,
                Material.REDSTONE_TORCH};
        for (int i = 0; i < styles.length && i < STYLE_SLOTS.length; i++) {
            PunishmentBroadcast.Style style = styles[i];
            List<String> lore = new ArrayList<>(Lang.rawList(viewer,
                    "staff-tools.banstyle.style." + style.id() + ".lore"));
            lore.add("");
            lore.add(Lang.raw(viewer, style == current ? "staff-tools.banstyle.selected" : "staff-tools.banstyle.click-select"));
            ItemStack stack = item(viewer, icons[i], "staff-tools.banstyle.style." + style.id() + ".name", lore);
            if (style == current) glow(stack);
            inv.setItem(STYLE_SLOTS[i], stack);
        }

        String sound = config.getString(PunishmentBroadcast.ROOT + "Sound", "");
        boolean hasSound = sound != null && !sound.isBlank();
        inv.setItem(SOUND, item(viewer, hasSound ? Material.NOTE_BLOCK : Material.BARRIER,
                "staff-tools.banstyle.sound.name", Lang.rawList(viewer, "staff-tools.banstyle.sound.lore", "value",
                        hasSound ? soundLabel(viewer, sound) : Lang.raw(viewer, "staff-tools.banstyle.sound-none"))));

        boolean title = config.getBoolean(PunishmentBroadcast.ROOT + "Title", false);
        inv.setItem(TITLE, item(viewer, title ? Material.NAME_TAG : Material.GRAY_DYE,
                "staff-tools.banstyle.screen.name", Lang.rawList(viewer, "staff-tools.banstyle.screen.lore", "state", state(viewer, title))));

        String animation = PunishmentBroadcast.animation(module);
        inv.setItem(ANIMATION, item(viewer, Material.FIREWORK_ROCKET, "staff-tools.banstyle.animation.name",
                Lang.rawList(viewer, "staff-tools.banstyle.animation.lore",
                        "value", Lang.raw(viewer, "staff-tools.banstyle.animation-" + animation))));

        boolean network = config.getBoolean(PunishmentBroadcast.ROOT + "Network", true);
        inv.setItem(NETWORK, item(viewer, network ? Material.ENDER_PEARL : Material.GRAY_DYE,
                "staff-tools.banstyle.network.name", Lang.rawList(viewer, "staff-tools.banstyle.network.lore", "state", state(viewer, network))));

        inv.setItem(PREVIEW, item(viewer, Material.SPYGLASS, "staff-tools.banstyle.preview.name",
                Lang.rawList(viewer, "staff-tools.banstyle.preview.lore")));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        var config = module.plugin().getConfig();
        for (int i = 0; i < STYLE_SLOTS.length; i++) {
            if (slot != STYLE_SLOTS[i]) continue;
            PunishmentBroadcast.Style style = PunishmentBroadcast.Style.values()[i];
            if (save(player, "Style", style.id())) PunishmentBroadcast.preview(module, player);
            return;
        }
        switch (slot) {
            case SOUND -> {
                String current = config.getString(PunishmentBroadcast.ROOT + "Sound", "");
                String next = cycle(PunishmentBroadcast.SOUND_CHOICES,
                        current == null ? "" : current.trim(), click.isRightClick());
                if (save(player, "Sound", next)) PunishmentBroadcast.preview(module, player);
            }
            case TITLE -> {
                boolean next = !config.getBoolean(PunishmentBroadcast.ROOT + "Title", false);
                if (save(player, "Title", next) && next) PunishmentBroadcast.preview(module, player);
            }
            case ANIMATION -> {
                String next = cycle(PunishmentBroadcast.ANIMATION_CHOICES,
                        PunishmentBroadcast.animation(module), click.isRightClick());
                if (save(player, "Animation", next)) PunishmentBroadcast.preview(module, player);
            }
            case NETWORK -> save(player, "Network",
                    !config.getBoolean(PunishmentBroadcast.ROOT + "Network", true));
            case PREVIEW -> PunishmentBroadcast.preview(module, player);
            default -> {
                return;
            }
        }
        if (slot != PREVIEW) Menu.open(player, this);
    }

    /** Guarda en moderation.yml; si el disco falla, deshace en memoria y lo dice. */
    private boolean save(Player player, String option, Object value) {
        var plugin = module.plugin();
        String path = PunishmentBroadcast.ROOT + option;
        synchronized (plugin.getConfig()) {
            Object previous = plugin.getConfig().get(path);
            plugin.getConfig().set(path, value);
            if (!plugin.saveConfigChecked()) {
                plugin.getConfig().set(path, previous);
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return false;
            }
        }
        module.audit(player, "banstyle", path + " -> " + value);
        if (option.equals("Style")) Menu.open(player, this);
        return true;
    }

    static String cycle(List<String> choices, String current, boolean backwards) {
        int index = choices.indexOf(current);
        if (index < 0) return choices.get(0);
        int step = backwards ? -1 : 1;
        return choices.get(Math.floorMod(index + step, choices.size()));
    }

    private static String soundLabel(Player viewer, String sound) {
        String key = "staff-tools.banstyle.sound-" + sound.replace('.', '-').replace("minecraft:", "");
        String label = Lang.raw(viewer, key);
        return label.equals(key) ? sound : label;
    }

    private static String state(Player viewer, boolean on) {
        return Lang.raw(viewer, on ? "staff-tools.banstyle.state-on" : "staff-tools.banstyle.state-off");
    }

    private static ItemStack item(Player viewer, Material material, String nameKey,
                                  List<String> lore) {
        return MenuItems.item(material, Lang.raw(viewer, nameKey), lore);
    }

    private static void glow(ItemStack stack) {
        var meta = stack.getItemMeta();
        if (meta == null) return;
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
    }
}
