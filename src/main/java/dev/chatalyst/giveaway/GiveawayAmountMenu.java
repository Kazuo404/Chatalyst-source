package dev.chatalyst.giveaway;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Cuanto paga un premio de dinero.
 *
 * <p>Hermana de {@link GiveawayDurationMenu}, y por el mismo motivo: la cantidad se decide en
 * cada sorteo, asi que decidirla tiene que costar un clic y no una navegacion.
 *
 * <p>Sustituye a tener tres premios de dinero distintos. Cuando cada uno traia su cifra fija
 * tenian sentido; en cuanto la cifra se escribe al lanzar, "Bolsa", "Saco" y "Cofre" pasaron a
 * ser el mismo premio con tres iconos — y encima uno que mentia, porque nada impedia elegir
 * "Bolsa" y escribir cien mil. Un premio, y el tamaño se elige aqui.
 */
public final class GiveawayAmountMenu implements Menu {

    /**
     * Los saltos habituales, del fichero. Estaban clavados en el codigo y eso no puede estar
     * bien: lo que es un premio gordo depende de la economia de CADA server, y unos atajos de
     * 1.000 a 100.000 son ridiculos donde el jugador mas rico tiene tres mil.
     */
    private static final double[] FALLBACK = {1_000, 5_000, 10_000, 25_000, 50_000, 100_000};

    private double[] presets() {
        List<Double> written = plugin.getConfig().getDoubleList("Giveaway.Amount_Presets");
        if (written.isEmpty()) return FALLBACK;
        // Seis: los que caben en la fila. Mas convierte elegir en leer una lista.
        return written.stream().filter(v -> v != null && v > 0d).distinct().sorted()
                .limit(6).mapToDouble(Double::doubleValue).toArray();
    }
    private static final int PRESET_START = 10;
    private static final int CUSTOM = 22;
    private static final int BACK = 27;
    private static final int CLOSE = 35;

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayPrize prize;
    private final int warmupSeconds;

    GiveawayAmountMenu(ChatalystPlugin plugin, GiveawayManager giveaways,
                       GiveawayPrize prize, int warmupSeconds) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.prize = prize;
        this.warmupSeconds = warmupSeconds;
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.amount-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.amount-title");
    }

    @Override
    public int size() {
        return 36;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        GiveawayPrize.MoneyBand current = prize.moneyBand();

        inv.setItem(4, MenuItems.item(prize.icon(),
                prize.displayName(plugin.getConfig(), viewer),
                List.of(Lang.raw(viewer, "giveaway-gui.amount", "amount", current == null
                        ? Lang.raw(viewer, "giveaway-gui.amount-unset")
                        : GiveawayPrize.shown(current)))));

        double[] presets = presets();
        for (int i = 0; i < presets.length; i++) {
            double value = presets[i];
            boolean picked = current != null && !current.isRange() && current.min() == value;
            // El icono sube de material con la cifra: de un vistazo se ve cual es el gordo
            // sin leer seis numeros parecidos.
            Material material = value >= 50_000 ? Material.GOLD_BLOCK
                    : value >= 10_000 ? Material.GOLD_INGOT : Material.GOLD_NUGGET;
            inv.setItem(PRESET_START + i, GiveawayChoices.option(viewer, material,
                    GiveawayPrize.shown(new GiveawayPrize.MoneyBand(value, value)), "<#FFD166>", picked));
        }

        inv.setItem(CUSTOM, MenuItems.item(Material.WRITABLE_BOOK,
                "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.amount-custom"),
                Lang.rawList(viewer, "giveaway-gui.amount-custom-lore")));
        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
    }


    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) return;
        switch (slot) {
            case CLOSE -> player.closeInventory();
            case BACK -> open(player, prize);
            case CUSTOM -> {
                GiveawayAmountPrompt prompt = giveaways.amountPrompt();
                if (prompt != null) prompt.askAmount(player, prize, warmupSeconds);
            }
            default -> {
                double[] presets = presets();
                int index = slot - PRESET_START;
                if (index < 0 || index >= presets.length) return;
                double value = presets[index];
                GiveawayPrize updated = prize.withMoney(
                        new GiveawayPrize.MoneyBand(value, value));
                player.sendMessage(Lang.get(player, "giveaway-gui.amount-set",
                        "amount", GiveawayPrize.shown(updated.moneyBand())));
                open(player, updated);
            }
        }
    }

    private void open(Player player, GiveawayPrize with) {
        Menu.open(player, new GiveawayStartMenu(plugin, giveaways, with, warmupSeconds));
    }
}
