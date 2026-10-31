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
 * The last screen before a draw goes live: what is being given away, how long the server gets
 * to gather, and one button that starts it.
 *
 * <p>Separate from the catalogue on purpose. Starting a giveaway takes over every screen on the
 * server, so it must not be one misclick away from browsing prizes — and the warm-up is worth
 * choosing per draw, since "we are giving away VIP in thirty seconds" and "in five minutes" are
 * different events.
 */
public final class GiveawayStartMenu implements Menu {

    private static final int PRIZE = 4;
    private static final int MINUS_BIG = 11;
    private static final int MINUS = 12;
    private static final int WARMUP = 13;
    private static final int PLUS = 14;
    private static final int PLUS_BIG = 15;
    private static final int DURATION = 10;
    private static final int AMOUNT = 16;
    private static final int BACK = 18;
    private static final int START = 22;
    private static final int CLOSE = 26;

    private static final int STEP = 5;
    private static final int BIG_STEP = 30;
    private static final int MAX_WARMUP = 3_600;

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayPrize prize;
    private final int warmupSeconds;

    GiveawayStartMenu(ChatalystPlugin plugin, GiveawayManager giveaways, GiveawayPrize prize,
                      int warmupSeconds) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.prize = prize;
        this.warmupSeconds = Math.max(0, Math.min(MAX_WARMUP, warmupSeconds));
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.start-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.start-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        List<String> lore = new ArrayList<>(prize.description(plugin.getConfig(), viewer));
        lore.add("");
        lore.add(Lang.raw(viewer, "giveaway-gui.prize-kind",
                "kind", Lang.raw(viewer, "giveaway.kind." + prize.kind().key())));
        ItemStack preview = MenuItems.item(prize.icon(),
                prize.displayName(plugin.getConfig(), viewer), lore);
        if (prize.glow()) preview.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        inv.setItem(PRIZE, preview);

        inv.setItem(MINUS_BIG, step(viewer, "minus-big", BIG_STEP, Material.RED_DYE));
        inv.setItem(MINUS, step(viewer, "minus", STEP, Material.RED_DYE));
        inv.setItem(WARMUP, MenuItems.item(Material.CLOCK,
                Lang.raw(viewer, "giveaway-gui.warmup", "seconds",
                        String.valueOf(warmupSeconds)),
                Lang.rawList(viewer, "giveaway-gui.warmup-lore")));
        inv.setItem(PLUS, step(viewer, "plus", STEP, Material.LIME_DYE));
        inv.setItem(PLUS_BIG, step(viewer, "plus-big", BIG_STEP, Material.LIME_DYE));

        // Solo lo que caduca. Un premio de objetos no dura: preguntarle cuanto dura es una
        // pregunta sin respuesta, y un boton que no significa nada en la mitad de las
        // pantallas es peor que no tenerlo.
        if (expires()) {
            long millis = prize.duration(plugin.getConfig());
            inv.setItem(DURATION, MenuItems.item(Material.CLOCK,
                    Lang.raw(viewer, "giveaway-gui.duration",
                            "duration", GiveawayPrize.durationShown(millis, viewer)),
                    Lang.rawList(viewer, "giveaway-gui.duration-lore")));
        }

        // Solo el dinero: es lo unico cuyo valor se decide por sorteo. Un kit o un rango son
        // lo que son, y un boton que no hace nada en cuatro de los cinco tipos de premio solo
        // sirve para que alguien lo pulse y se pregunte por que no pasa nada.
        if (prize.kind() == GiveawayPrize.Kind.MONEY) {
            GiveawayPrize.MoneyBand band = prize.moneyBand();
            List<String> amountLore = new ArrayList<>(
                    Lang.rawList(viewer, "giveaway-gui.amount-lore"));
            inv.setItem(AMOUNT, MenuItems.item(Material.SUNFLOWER,
                    Lang.raw(viewer, "giveaway-gui.amount", "amount", band == null
                            ? Lang.raw(viewer, "giveaway-gui.amount-unset")
                            : GiveawayPrize.shown(band)),
                    amountLore));
        }

        String problem = giveaways.delivery().problem(prize);
        List<String> startLore = new ArrayList<>(
                Lang.rawList(viewer, "giveaway-gui.start-lore"));
        if (problem != null) {
            startLore.add("<#FF9F1C>" + Lang.raw(viewer, "giveaway-gui.prize-problem",
                    "problem", problem));
        }
        inv.setItem(START, MenuItems.item(Material.NETHER_STAR,
                "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.start"), startLore));

        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) return;
        switch (slot) {
            case BACK -> Menu.open(player, new GiveawayMenu(plugin, giveaways));
            case CLOSE -> player.closeInventory();
            case MINUS_BIG -> reopen(player, warmupSeconds - BIG_STEP);
            case MINUS -> reopen(player, warmupSeconds - STEP);
            case PLUS -> reopen(player, warmupSeconds + STEP);
            case PLUS_BIG -> reopen(player, warmupSeconds + BIG_STEP);
            case DURATION -> {
                // Pantalla, no prompt: la pregunta tiene dos partes (permanente o hasta
                // cuando) y encadenarlas por chat obliga a escribir sin ver el estado.
                if (expires()) {
                    Menu.open(player, new GiveawayDurationMenu(plugin, giveaways, prize,
                            warmupSeconds));
                }
            }
            case AMOUNT -> {
                // Pantalla con atajos, igual que la duracion: la cantidad se decide en cada
                // sorteo, asi que decidirla tiene que costar un clic.
                if (prize.kind() == GiveawayPrize.Kind.MONEY) {
                    Menu.open(player, new GiveawayAmountMenu(plugin, giveaways, prize,
                            warmupSeconds));
                }
            }
            case START -> {
                // Closed FIRST. With Warmup_Seconds at 0 the wheel opens inside start(),
                // and closing afterwards would shut the very menu the draw just handed
                // this admin.
                player.closeInventory();
                String failure = giveaways.start(player, prize, warmupSeconds);
                player.sendMessage(failure == null
                        ? Lang.get(player, "giveaway.started", "prize", prize.id())
                        : Lang.get(player, failure, "command",
                                plugin.getConfig().getString("Giveaway.Command", "sorteo")));
            }
            default -> {
                // Empty slot: nothing to do. The click is already cancelled.
            }
        }
    }

    /**
     * Si a este premio le pega tener duracion: concede un rango, o concede permisos, o las dos.
     * Un premio de objetos o de dinero se entrega y ya esta; no hay nada que caduque.
     */
    private boolean expires() {
        return prize.kind() == GiveawayPrize.Kind.RANK
                || prize.kind() == GiveawayPrize.Kind.PERMISSION
                || !prize.permissions(plugin.getConfig()).isEmpty();
    }

    private void reopen(Player player, int seconds) {
        Menu.open(player, new GiveawayStartMenu(plugin, giveaways, prize, seconds));
    }

    private ItemStack step(Player viewer, String action, int seconds, Material material) {
        return MenuItems.item(material,
                Lang.raw(viewer, "giveaway-gui." + action, "seconds", String.valueOf(seconds)),
                List.of());
    }
}
