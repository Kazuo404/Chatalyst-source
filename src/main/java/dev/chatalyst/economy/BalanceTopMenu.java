package dev.chatalyst.economy;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI con el ranking de dinero (baltop): una cabeza por jugador, en orden, con su posicion
 * y balance. Lee la cache de {@link BalanceTop} (no recalcula al abrir).
 */
public final class BalanceTopMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int CLOSE = 49;
    private static final int NEXT = 53;

    private final BalanceTop balanceTop;
    private int page;

    public BalanceTopMenu(BalanceTop balanceTop) {
        this(balanceTop, 0);
    }

    private BalanceTopMenu(BalanceTop balanceTop, int page) {
        this.balanceTop = balanceTop;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("baltop.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "baltop.gui-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        List<BalanceTop.Entry> top = balanceTop.top();
        if (top.isEmpty()) {
            // PAPER y no cristal: icono neutro que Bedrock (Geyser) muestra bien.
            inv.setItem(22, icon(Material.PAPER,
                    Lang.raw(viewer, "baltop.gui-no-data"),
                    List.of(Lang.raw(viewer, "baltop.gui-no-data-lore"))));
            inv.setItem(CLOSE, MenuNavigation.close(viewer));
            return;
        }
        int pages = Math.max(1, (top.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(top.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(end - start, 0);
        for (int index = start; index < end; index++) {
            int slot = layout[index - start];
            inv.setItem(slot, head(viewer, top.get(index), index + 1));
            // Skin real de los offline en segundo plano (cacheada); el slot se refresca solo.
            dev.chatalyst.gui.PlayerHeads.completeLater(viewer, inv, slot, top.get(index).uuid());
        }
        inv.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
        inv.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
        } else if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new BalanceTopMenu(balanceTop, page - 1));
        } else if (slot == NEXT) {
            int pages = Math.max(1, (balanceTop.top().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) Menu.open(player, new BalanceTopMenu(balanceTop, page + 1));
        }
    }

    private static ItemStack head(Player viewer, BalanceTop.Entry e, int rank) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        it.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, e.uuid());
            // Apodo actual si tiene uno (misma identidad que en el chat y la tab).
            String shown = dev.chatalyst.nick.NickManager.shownName(e.uuid().toString(), e.name());
            meta.displayName(MM.full(medal(rank) + " <#FBF2FF>" + shown)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            // 2026-09-20: los colores de estas dos lineas ya NO estan aqui. La linea
            // entera vive en lang/<idioma>.yml (baltop.gui-balance y baltop.gui-rank),
            // asi se cambia sin recompilar. El saldo va en blanco a proposito: la
            // moneda es un glifo de ItemsAdder y cualquier color tiñe su textura.
            lore.add(MM.full(line(viewer, "baltop.gui-balance", "balance",
                            VaultHook.format(e.balance())))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MM.full(line(viewer, "baltop.gui-rank", "rank", String.valueOf(rank)))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        return it;
    }

    /**
     * Una linea del lore con su valor. Hasta el 2026-09-20 estas claves eran solo la ETIQUETA
     * ("Saldo") y el numero lo ponia el codigo; ahora son la linea entera con {balance}. Las
     * actualizaciones solo añaden claves que faltan, asi que un server con el lang viejo se
     * quedaba en "Saldo" sin ninguna cantidad. Si el texto no trae el hueco, se añade detras.
     */
    static String line(org.bukkit.command.CommandSender viewer, String key, String var,
                       String value) {
        String template = Lang.raw(viewer, key);
        if (template.contains("{" + var + "}")) return Lang.raw(viewer, key, var, value);
        return "<#C0B3D9>" + template + " <#7B68A0>» <white>" + value;
    }

    /** Delegado en MenuItems: antes era una copia local que se saltaba la paleta. */
    private static ItemStack icon(Material mat, String name, List<String> loreLines) {
        return dev.chatalyst.gui.MenuItems.item(mat, name, loreLines);
    }

    // Medalla para el podio, numero para el resto.
    private static String medal(int rank) {
        return switch (rank) {
            case 1 -> "<#FFD166><bold>#1</bold>";
            case 2 -> "<#C0C0C0><bold>#2</bold>";
            case 3 -> "<#CD7F32><bold>#3</bold>";
            default -> "<#7B68A0>#" + rank;
        };
    }
}
