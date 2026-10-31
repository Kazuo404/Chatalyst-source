package dev.chatalyst.rental;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.hook.VaultHook;
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

/** Lo que se puede alquilar con dinero del juego, y por cuanto. */
public final class RentalMenu implements Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 27;
    private static final int BALANCE = 49;
    private static final int CLOSE = 53;

    private static final int BACK = 45;

    private final ChatalystPlugin plugin;
    private final RentalService rentals;
    /** null = la portada, con una carta por categoria. Si no, la categoria abierta. */
    private final String category;
    private final List<RentalOffer> shown = new ArrayList<>();
    private final List<dev.chatalyst.gui.Catalog> categories = new ArrayList<>();

    public RentalMenu(ChatalystPlugin plugin, RentalService rentals) {
        this(plugin, rentals, null);
    }

    private RentalMenu(ChatalystPlugin plugin, RentalService rentals, String category) {
        this.plugin = plugin;
        this.rentals = rentals;
        this.category = category;
    }

    @Override
    public Component title() {
        return Lang.get("rental-gui.title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "rental-gui.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        categories.clear();
        var grouped = dev.chatalyst.gui.Catalog.group(plugin.getConfig(), "Rental_Categories",
                RentalOffer.all(plugin.getConfig()), offer -> offer.category(plugin.getConfig()));

        if (grouped.isEmpty()) {
            inv.setItem(GRID_START + GRID_SIZE / 2, MenuItems.item(Material.BARRIER,
                    Lang.raw(viewer, "rental-gui.empty"),
                    Lang.rawList(viewer, "rental-gui.empty-lore")));
        } else if (category == null) {
            // Portada por categorias. Sin esto, con 32 ofertas y 27 huecos, cinco no se
            // enseñaban y nadie podia saber cuales: el menu mentia en silencio.
            int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.min(grouped.size(), GRID_SIZE), GRID_START / 9);
            int n = 0;
            for (var entry : grouped.entrySet()) {
                if (n >= layout.length) break;
                categories.add(entry.getKey());
                inv.setItem(layout[n++], categoryCard(viewer, entry.getKey(), entry.getValue()));
            }
        } else {
            List<RentalOffer> offers = grouped.entrySet().stream()
                    .filter(e -> e.getKey().id().equals(category))
                    .findFirst().map(java.util.Map.Entry::getValue).orElse(List.of());
            int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.min(offers.size(), GRID_SIZE), GRID_START / 9);
            for (int i = 0; i < layout.length; i++) {
                shown.add(offers.get(i));
                inv.setItem(layout[i], card(viewer, offers.get(i)));
            }
            inv.setItem(BACK, MenuNavigation.back(viewer));
        }

        // Simetria: en la portada el hueco de "volver" actualiza (saldo y ofertas al dia).
        if (category == null) inv.setItem(BACK, MenuNavigation.refresh(viewer));
        inv.setItem(BALANCE, MenuItems.item(Material.GOLD_INGOT,
                Lang.raw(viewer, "rental-gui.balance",
                        "balance", VaultHook.format(VaultHook.balance(viewer))),
                Lang.rawList(viewer, "rental-gui.balance-lore")));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
    }

    private ItemStack card(Player viewer, RentalOffer offer) {
        List<String> lore = new ArrayList<>(offer.description(plugin.getConfig(), viewer));
        lore.add("");
        lore.add(Lang.raw(viewer, "rental-gui.lasts",
                "duration", TimeUtil.formatElapsed(offer.durationMillis()).trim()));
        lore.add(Lang.raw(viewer, "rental-gui.price",
                "price", VaultHook.format(offer.price())));
        lore.add("");
        // Lo unico que el jugador no puede deducir mirando: si puede permitirselo AHORA y si
        // el server puede entregarlo. Decirlo aqui evita el clic que solo sirve para fallar.
        String problem = rentals.problem(offer);
        if (problem != null) {
            lore.add("<#FF9F1C>" + Lang.raw(viewer, problem));
        } else if (VaultHook.balance(viewer) < offer.price()) {
            lore.add("<#FF4D6D>" + Lang.raw(viewer, "rental-gui.cannot-afford"));
        } else {
            lore.add("<#3DFFA8>" + Lang.raw(viewer, "rental-gui.click-to-rent"));
        }
        ItemStack item = MenuItems.item(offer.icon(),
                offer.displayName(plugin.getConfig(), viewer), lore);
        if (offer.glow()) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == BACK) {
            Menu.open(player, new RentalMenu(plugin, rentals));
            return;
        }
        int index = dev.chatalyst.gui.MenuLayout.indexAt(slot, category == null ? categories.size() : shown.size(), GRID_START / 9);
        if (category == null) {
            if (index >= 0 && index < categories.size()) {
                Menu.open(player, new RentalMenu(plugin, rentals, categories.get(index).id()));
            }
            return;
        }
        if (index < 0 || index >= shown.size()) return;
        RentalOffer offer = shown.get(index);
        String failure = rentals.buy(player, offer);
        player.sendMessage(failure != null
                ? Lang.get(player, failure, "price", VaultHook.format(offer.price()))
                : Lang.get(player, "rental.bought",
                        "what", offer.displayName(plugin.getConfig(), player),
                        "duration", TimeUtil.formatElapsed(offer.durationMillis()).trim()));
        // Repintar: el saldo cambio y puede que esto ya no se pueda volver a pagar.
        Menu.open(player, new RentalMenu(plugin, rentals, category));
    }

    private org.bukkit.inventory.ItemStack categoryCard(Player viewer,
            dev.chatalyst.gui.Catalog group, List<RentalOffer> offers) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "menu.category-count",
                "count", String.valueOf(offers.size())));
        lore.add("");
        lore.add("<#7B68A0>" + Lang.raw(viewer, "menu.category-open"));
        return MenuItems.item(group.icon(),
                "<#E0AAFF>" + group.displayName(plugin.getConfig(), viewer), lore);
    }
}
