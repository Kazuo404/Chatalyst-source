package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.hook.PlayerVaultsHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Las mochilas de PlayerVaultsX de un jugador, una por item. Clic izquierdo abre una COPIA de
 * solo lectura ({@link VaultContentsMenu}); clic derecho, con permiso de edicion, abre la mochila
 * real con la mecanica del propio PlayerVaultsX.
 *
 * <p>Los datos se leen al construir el menu y no se guardan entre aperturas: lo que se ve es una
 * foto del momento. Una mochila que el dueño tiene abierta cambia mientras miras, y una cache
 * enseñaria un recuento de huecos que ya no es verdad justo cuando el staff decide por el.
 */
public final class PlayerVaultsMenu implements Menu {

    static final String VIEW_PERMISSION = "chatalyst.command.vaultsee";
    static final String EDIT_PERMISSION = "chatalyst.command.vaultsee.edit";

    // Misma fila de navegacion que el resto de listas paginadas del paquete (AltsListMenu).
    static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    /** Una mochila tal como estaba al abrir el menu. */
    record Entry(int number, int used, int size, boolean openNow) {
    }

    private final StaffModerationModule module;
    private final UUID ownerId;
    private final String ownerName;
    private final List<Entry> entries;
    private final Runnable back;
    private final int page;

    private PlayerVaultsMenu(StaffModerationModule module, UUID ownerId, String ownerName,
                             List<Entry> entries, Runnable back, int page) {
        this.module = module;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.entries = List.copyOf(entries);
        this.back = back;
        this.page = clampPage(page, entries.size());
    }

    /** Lista de mochilas de owner. back: qué abrir con la flecha de volver; null = el botón solo cierra. */
    public static void open(StaffModerationModule module, Player viewer, UUID ownerId,
                            String ownerName, Runnable back) {
        open(module, viewer, ownerId, ownerName, back, 0);
    }

    private static void open(StaffModerationModule module, Player viewer, UUID ownerId,
                             String ownerName, Runnable back, int page) {
        if (!StaffAccess.has(viewer, VIEW_PERMISSION)) {
            viewer.sendMessage(Lang.get(viewer, "common.no-permission"));
            return;
        }
        if (!PlayerVaultsHook.isAvailable()) {
            viewer.sendMessage(Lang.get(viewer, "staff-moderation.vaults-no-plugin"));
            return;
        }
        List<Entry> entries = snapshot(ownerId);
        if (entries.isEmpty()) {
            viewer.sendMessage(Lang.get(viewer, "staff-moderation.vaults-none",
                    "player", MM.escape(ownerName)));
            return;
        }
        Menu.open(viewer, new PlayerVaultsMenu(module, ownerId, ownerName, entries, back, page));
    }

    /** Lee ahora mismo cada mochila. Una que desaparece entre listar y leer se omite. */
    private static List<Entry> snapshot(UUID ownerId) {
        List<Entry> out = new ArrayList<>();
        Player owner = org.bukkit.Bukkit.getPlayer(ownerId);
        for (int number : PlayerVaultsHook.vaultNumbers(ownerId)) {
            ItemStack[] contents = PlayerVaultsHook.contents(ownerId, number);
            if (contents == null) continue;
            int used = used(contents);
            if (!listed(used, owner != null && PlayerVaultsHook.canAccess(owner, number))) continue;
            out.add(new Entry(number, used, contents.length,
                    PlayerVaultsHook.isOpenNow(ownerId, number)));
        }
        return out;
    }

    /**
     * Que mochilas salen en la lista: las que tienen algo dentro, y las vacias a las que su
     * dueño tiene acceso ahora.
     *
     * <p>PlayerVaultsX guarda una entrada por cada mochila que se abrio alguna vez, aunque se
     * cerrara vacia y aunque el jugador ya no tenga el rango que se la daba. Listarlas todas
     * llenaba el menu de cofres vacios que ni su dueño puede abrir, y lo que el staff busca —
     * donde hay cosas — quedaba enterrado. Una mochila CON items sale siempre, tenga o no
     * acceso su dueño: justo esas son las que interesan al revisar.
     */
    static boolean listed(int used, boolean ownerHasAccess) {
        return used > 0 || ownerHasAccess;
    }

    /** Huecos ocupados. El hook ya deja como null el aire, asi que basta con contar no-nulos. */
    static int used(Object[] slots) {
        if (slots == null) return 0;
        int used = 0;
        for (Object slot : slots) {
            if (slot != null) used++;
        }
        return used;
    }

    static int pages(int count) {
        return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Una pagina fuera de rango (mochilas borradas desde la ultima vez) cae en la ultima valida. */
    static int clampPage(int page, int count) {
        return Math.min(Math.max(0, page), pages(count) - 1);
    }

    /** Indice de la entrada bajo {@code slot}, o -1 si el hueco no es de contenido o esta vacio. */
    static int entryIndex(int page, int slot, int count) {
        int start = page * PAGE_SIZE;
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                Math.max(0, Math.min(count - start, PAGE_SIZE)), 0);
        return local < 0 ? -1 : start + local;
    }

    /** La cantidad del item dice el numero de la mochila, mientras quepa en una pila. */
    static int stackAmount(int number) {
        return number >= 1 && number <= 64 ? number : 1;
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.vaults-menu-title", "player", MM.escape(ownerName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.vaults-menu-title",
                "player", MM.escape(ownerName));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        boolean canEdit = StaffAccess.has(viewer, EDIT_PERMISSION);
        int start = page * PAGE_SIZE;
        int shown = Math.max(0, Math.min(entries.size() - start, PAGE_SIZE));
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(shown, 0);
        for (int i = 0; i < shown; i++) {
            inventory.setItem(layout[i], icon(viewer, entries.get(start + i), canEdit));
        }
        int pages = pages(entries.size());
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, back != null ? MenuNavigation.back(viewer)
                : MenuNavigation.close(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    private ItemStack icon(Player viewer, Entry entry, boolean canEdit) {
        String number = String.valueOf(entry.number());
        List<String> lore = new ArrayList<>();
        if (entry.openNow()) lore.add(Lang.raw(viewer, "staff-moderation.vaults-entry-open-now"));
        lore.add(entry.used() > 0
                ? Lang.raw(viewer, "staff-moderation.vaults-entry-used",
                        "used", String.valueOf(entry.used()), "size", String.valueOf(entry.size()))
                : Lang.raw(viewer, "staff-moderation.vaults-entry-empty"));
        lore.add(Lang.raw(viewer, "staff-moderation.vaults-entry-view"));
        if (canEdit) lore.add(Lang.raw(viewer, "staff-moderation.vaults-entry-edit"));
        // Ender chest para la que alguien tiene abierta: a simple vista se distingue que esa
        // copia puede cambiar mientras se mira.
        ItemStack item = MenuItems.item(entry.openNow() ? Material.ENDER_CHEST : Material.CHEST,
                Lang.raw(viewer, "staff-moderation.vaults-entry", "number", number), lore);
        item.setAmount(stackAmount(entry.number()));
        return item;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Se re-comprueba en cada clic: el permiso puede haberse retirado con el menu abierto.
        if (!StaffAccess.has(player, VIEW_PERMISSION)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            if (back != null) back.run();
            else player.closeInventory();
            return;
        }
        int pages = pages(entries.size());
        if (slot == PREVIOUS && page > 0) {
            open(module, player, ownerId, ownerName, back, page - 1);
            return;
        }
        if (slot == NEXT && page + 1 < pages) {
            open(module, player, ownerId, ownerName, back, page + 1);
            return;
        }
        int index = entryIndex(page, slot, entries.size());
        if (index < 0) return;
        int number = entries.get(index).number();
        if (click.isRightClick() && StaffAccess.has(player, EDIT_PERMISSION)) {
            editReal(module, player, ownerId, ownerName, number);
            return;
        }
        // Volver desde la copia relee la lista: el recuento de huecos puede haber cambiado.
        int currentPage = page;
        VaultContentsMenu.open(module, player, ownerId, ownerName, number,
                () -> open(module, player, ownerId, ownerName, back, currentPage));
    }

    /**
     * Abre la mochila REAL. Se cierra antes nuestro menu: PlayerVaultsX abre su propio inventario
     * y, si el nuestro siguiera debajo, el cierre de uno pisaria al otro.
     */
    static void editReal(StaffModerationModule module, Player player, UUID ownerId,
                         String ownerName, int number) {
        player.closeInventory();
        if (PlayerVaultsHook.openForEdit(player, ownerId, number)) {
            module.audit(player, "vaultsee edit", ownerName + " #" + number);
        } else {
            player.sendMessage(Lang.get(player, "staff-moderation.vaults-edit-failed",
                    "number", String.valueOf(number), "player", MM.escape(ownerName)));
        }
    }
}
