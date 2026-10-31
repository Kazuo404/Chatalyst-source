package dev.chatalyst.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Un menu de inventario. Implementaciones definen titulo, tamano, contenido y que hacer al
 * hacer click. Se abre con {@link #open(Player, Menu)}.
 */
public interface Menu {

    Component title();

    default Component title(Player viewer) {
        return title();
    }

    /** Tamano en slots (multiplo de 9). */
    int size();

    /** Rellena el inventario para este espectador. */
    void build(Inventory inv, Player viewer);

    /** Reaccion a un click en un slot. */
    void onClick(Player player, int slot, ClickType click);

    /**
     * Reaccion a un click en el INVENTARIO PROPIO del jugador mientras este menu esta abierto
     * (el click ya llega cancelado: ningun item se mueve solo). Lo usan menus que "toman"
     * items del inventario, como la oferta de trueque del mercado. Por defecto, nada.
     */
    default void onClickBottom(Player player, int slot, ClickType click) {
    }

    // NOTA: los huecos se dejan VACIOS a proposito (sin panel de cristal de relleno).
    // Los jugadores de Bedrock (via Geyser) ven los menus como inventarios tactiles donde
    // cada item de relleno es ruido tocable; sin relleno la GUI queda limpia en ambas
    // plataformas. MenuListener cancela todo click/drag, asi que un hueco vacio no permite
    // meter items en la GUI.
    static void open(Player player, Menu menu) {
        MenuHolder holder = new MenuHolder(menu);
        Inventory inv = Bukkit.createInventory(holder, menu.size(),
                MenuTheme.inventoryTitle(menu.title(player)));
        holder.setInventory(inv);
        menu.build(inv, player);
        reportAsymmetry(menu, inv);
        player.openInventory(inv);
    }

    /** Clases ya avisadas: el aviso sale una vez por menu y arranque, no en cada apertura. */
    java.util.Set<String> REPORTED_ASYMMETRIC = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Todos los menus deben ser simetricos (KZ, 2026-10-04). Si alguno se abre torcido (por
     * permisos, datos o un cambio futuro), queda apuntado en el log con su clase y filas.
     */
    private static void reportAsymmetry(Menu menu, Inventory inv) {
        try {
            boolean[] occupied = new boolean[inv.getSize()];
            for (int i = 0; i < occupied.length; i++) {
                var item = inv.getItem(i);
                occupied[i] = item != null && !item.getType().isAir();
            }
            var rows = MenuSymmetry.asymmetricRows(occupied);
            String name = menu.getClass().getName();
            if (rows.isEmpty() || !REPORTED_ASYMMETRIC.add(name + rows)) return;
            var plugin = dev.chatalyst.ChatalystPlugin.get();
            if (plugin != null) {
                plugin.getLogger().info("[Menus] " + menu.getClass().getSimpleName()
                        + " no es simetrico en las filas " + rows + " (avisa a quien mantiene Chatalyst).");
            }
        } catch (RuntimeException ignored) {
            // un aviso nunca puede impedir abrir el menu
        }
    }
}
