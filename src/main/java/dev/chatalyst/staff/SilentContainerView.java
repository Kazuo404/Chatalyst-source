package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * El cofre que se mira sin abrirlo.
 *
 * <p>Abrir un cofre de verdad levanta la tapa, suena, y deja el contenedor marcado como "en
 * uso" para todo el que pase: es el gesto que mas rapido delata un vanish. Asi que en vanish
 * el clic derecho sobre un contenedor no lo abre — se cancela antes de que el bloque llegue a
 * enterarse — y en su lugar se muestra AQUI una copia de lo que hay dentro. Para el mundo no
 * ha pasado nada; para el staff, el cofre esta a la vista. Eso es exactamente lo que hace un
 * espectador, pero sin dejar de ser un jugador normal para todo lo demas.
 *
 * <p>La copia es de solo lectura por el mismo motivo que existe el modo observador: un item
 * que desaparece de un cofre sin que nadie lo haya abierto es imposible de explicar. Lo que se
 * ve es una FOTO del momento de abrirla; si alguien mueve algo dentro mientras miras, se ve al
 * volver a abrirla. Para editar de verdad estan las herramientas de staff (invsee/echest), que
 * dejan rastro en el historial.
 */
public final class SilentContainerView implements Menu {

    private static final int MAX_SLOTS = 54;

    private final ItemStack[] contents;
    private final int size;

    private SilentContainerView(ItemStack[] contents, int size) {
        this.contents = contents;
        this.size = size;
    }

    /** Copia el contenedor y muestra la copia. La fuente no se toca en ningun momento. */
    public static void open(Player viewer, Inventory source) {
        ItemStack[] snapshot = copy(source.getContents());
        Menu.open(viewer, new SilentContainerView(snapshot, rows(snapshot.length)));
        viewer.sendActionBar(Lang.get(viewer, "staff-moderation.vanish-silent-open"));
    }

    /** El tamano de un menu es multiplo de 9; un hopper (5) o un horno (3) caben en una fila. */
    static int rows(int slots) {
        int rounded = ((Math.max(1, slots) + 8) / 9) * 9;
        return Math.min(MAX_SLOTS, rounded);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.vanish-silent-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.vanish-silent-title");
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        for (int slot = 0; slot < Math.min(size, contents.length); slot++) {
            inventory.setItem(slot, contents[slot]);
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Solo lectura: MenuListener ya cancela el click y aqui no hay ninguna accion detras.
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null || items[i].getType().isAir() ? null : items[i].clone();
        }
        return out;
    }
}
