package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuanto dura lo que concede un premio: para siempre, o hasta cuando.
 *
 * <p>Una pantalla y no un prompt de chat porque la pregunta tiene dos partes — permanente o
 * temporal, y si es temporal cuanto — y encadenarlas por chat obliga a escribir a ciegas sin
 * ver en que estado esta lo que se decide. Aqui se ve, se toca y se corrige.
 *
 * <p>Los atajos cubren lo que se sortea de verdad. Escribir a mano sigue estando, para el
 * "dos semanas" que no cabe en una fila de botones.
 */
public final class GiveawayDurationMenu implements Menu {

    /** Los saltos que de verdad se usan. Mas de una fila y elegir se vuelve leer una lista. */
    private static final String[] PRESETS = {"1d", "3d", "7d", "14d", "30d"};
    private static final int PRESET_START = 11;
    private static final int PERMANENT = 20;
    private static final int DEFAULT = 22;
    private static final int CUSTOM = 24;
    private static final int BACK = 27;
    private static final int CLOSE = 35;

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayPrize prize;
    private final int warmupSeconds;

    GiveawayDurationMenu(ChatalystPlugin plugin, GiveawayManager giveaways,
                         GiveawayPrize prize, int warmupSeconds) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.prize = prize;
        this.warmupSeconds = warmupSeconds;
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.duration-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.duration-title");
    }

    @Override
    public int size() {
        return 36;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        long current = prize.duration(plugin.getConfig());

        inv.setItem(4, MenuItems.item(prize.icon(),
                prize.displayName(plugin.getConfig(), viewer),
                List.of(Lang.raw(viewer, "giveaway-gui.duration",
                        "duration", GiveawayPrize.durationShown(current, viewer)))));

        for (int i = 0; i < PRESETS.length; i++) {
            long millis = TimeUtil.parseDuration(PRESETS[i]);
            inv.setItem(PRESET_START + i, GiveawayChoices.option(viewer, Material.CLOCK,
                    TimeUtil.formatElapsed(millis).trim(), "<#C0B3D9>", current == millis));
        }
        inv.setItem(PERMANENT, GiveawayChoices.option(viewer, Material.NETHER_STAR,
                Lang.raw(viewer, "giveaway-gui.duration-permanent"), "<#C0B3D9>",
                current == TimeUtil.PERMANENT));
        // Volver a "lo que diga la config" tiene que ser posible: sin esta salida, tocar el
        // menu por curiosidad dejaria el premio con una duracion pegada para siempre.
        inv.setItem(DEFAULT, GiveawayChoices.option(viewer, Material.PAPER,
                Lang.raw(viewer, "giveaway-gui.duration-default"), "<#C0B3D9>", current == 0L));
        inv.setItem(CUSTOM, MenuItems.item(Material.WRITABLE_BOOK,
                "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.duration-custom"),
                Lang.rawList(viewer, "giveaway-gui.duration-custom-lore")));

        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
    }


    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) return;
        switch (slot) {
            case CLOSE -> player.closeInventory();
            case BACK -> back(player, prize);
            case PERMANENT -> pick(player, TimeUtil.PERMANENT);
            case DEFAULT -> pick(player, 0L);
            case CUSTOM -> {
                GiveawayAmountPrompt prompt = giveaways.amountPrompt();
                if (prompt != null) prompt.askDuration(player, prize, warmupSeconds);
            }
            default -> {
                int index = slot - PRESET_START;
                if (index >= 0 && index < PRESETS.length) {
                    pick(player, TimeUtil.parseDuration(PRESETS[index]));
                }
            }
        }
    }

    /** Elegir devuelve a la pantalla de lanzar: se vino a decidir esto y ya esta decidido. */
    private void pick(Player player, long millis) {
        GiveawayPrize updated = millis == 0L
                ? giveaways.prize(prize.id()).orElse(prize)
                : prize.withDuration(millis);
        player.sendMessage(Lang.get(player, "giveaway-gui.duration-set",
                "duration", GiveawayPrize.durationShown(
                        updated.duration(plugin.getConfig()), player)));
        back(player, updated);
    }

    private void back(Player player, GiveawayPrize with) {
        Menu.open(player, new GiveawayStartMenu(plugin, giveaways, with, warmupSeconds));
    }
}
