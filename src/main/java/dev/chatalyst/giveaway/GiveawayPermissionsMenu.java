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

import java.util.ArrayList;
import java.util.List;

/**
 * Los permisos que concede un premio, editables sin salir del juego.
 *
 * <p>A diferencia de la cantidad y de la duracion, esto SI se guarda en {@code giveaway.yml}:
 * un permiso no es una decision de un sorteo, es lo que el premio es. Y el fichero sigue siendo
 * la otra mitad de la verdad — se puede editar a mano, sus comentarios sobreviven al guardado, y
 * lo que se escriba ahi aparece aqui en el siguiente reload.
 *
 * <p>Se llega con clic derecho sobre el premio en el catalogo. El clic izquierdo sigue llevando
 * a lanzar el sorteo: lo que se hace todos los dias no puede quedar detras de un menu nuevo.
 */
public final class GiveawayPermissionsMenu implements Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 27;
    private static final int ADD = 49;
    private static final int BACK = 45;
    private static final int CLOSE = 53;

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayPrize prize;
    private final List<String> shown = new ArrayList<>();

    GiveawayPermissionsMenu(ChatalystPlugin plugin, GiveawayManager giveaways,
                            GiveawayPrize prize) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.prize = prize;
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.permissions-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.permissions-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        shown.addAll(prize.permissions(plugin.getConfig()));

        inv.setItem(4, MenuItems.item(prize.icon(),
                prize.displayName(plugin.getConfig(), viewer),
                Lang.rawList(viewer, "giveaway-gui.permissions-header")));

        for (int i = 0; i < shown.size() && i < GRID_SIZE; i++) {
            inv.setItem(GRID_START + i, MenuItems.item(Material.PAPER,
                    "<#FBF2FF>" + shown.get(i),
                    Lang.rawList(viewer, "giveaway-gui.permission-lore")));
        }

        // Nunca se queda una pantalla vacia sin explicacion: un menu en blanco parece roto.
        if (shown.isEmpty()) {
            inv.setItem(GRID_START + 4, MenuItems.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    Lang.raw(viewer, "giveaway-gui.permissions-none"),
                    Lang.rawList(viewer, "giveaway-gui.permissions-none-lore")));
        }

        inv.setItem(ADD, MenuItems.item(Material.WRITABLE_BOOK,
                "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.permission-add"),
                Lang.rawList(viewer, "giveaway-gui.permission-add-lore")));
        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) return;
        switch (slot) {
            case CLOSE -> player.closeInventory();
            case BACK -> Menu.open(player, new GiveawayMenu(plugin, giveaways));
            case ADD -> {
                GiveawayAmountPrompt prompt = giveaways.amountPrompt();
                if (prompt != null) prompt.askPermission(player, prize);
            }
            default -> {
                int index = slot - GRID_START;
                if (index < 0 || index >= shown.size()) return;
                // Quitar es destructivo y no hay deshacer, asi que pide el gesto deliberado
                // que ya usa el resto del plugin en vez de irse con un clic de paso.
                if (click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
                    player.sendMessage(Lang.get(player, "giveaway-gui.permission-remove-hint"));
                    return;
                }
                List<String> left = new ArrayList<>(shown);
                String removed = left.remove(index);
                if (giveaways.permissionsFile().save(prize.id(), left)) {
                    player.sendMessage(Lang.get(player, "giveaway-gui.permission-removed",
                            "permission", removed));
                } else {
                    player.sendMessage(Lang.get(player, "giveaway-gui.permission-save-failed"));
                }
                Menu.open(player, new GiveawayPermissionsMenu(plugin, giveaways,
                        giveaways.prize(prize.id()).orElse(prize)));
            }
        }
    }
}
