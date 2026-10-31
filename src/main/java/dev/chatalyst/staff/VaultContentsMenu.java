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

import java.util.List;
import java.util.UUID;

/**
 * Una mochila de PlayerVaultsX vista por dentro, en COPIA de solo lectura — el mismo espiritu
 * que {@link SilentContainerView}. La mochila nunca se abre: PlayerVaultsX no se entera, no la
 * bloquea para su dueño y no hay nada que guardar al cerrar. Lo que se ve es una foto del
 * momento (con los cambios sin guardar si alguien la tenia abierta); MenuListener ya cancela
 * todos los clics, asi que nada sale de aqui.
 *
 * <p>Para cambiar algo de verdad esta el boton de editar (con permiso), que abre la mochila REAL
 * con la mecanica del propio PlayerVaultsX y deja rastro en la auditoria.
 */
public final class VaultContentsMenu implements Menu {

    static final int MAX_SIZE = 54;
    /** Sin hueco para la fila de navegacion. */
    static final int NO_SLOT = -1;

    private final StaffModerationModule module;
    private final UUID ownerId;
    private final String ownerName;
    private final int number;
    private final ItemStack[] contents;
    private final Runnable back;
    private final int size;

    private VaultContentsMenu(StaffModerationModule module, UUID ownerId, String ownerName,
                              int number, ItemStack[] contents, Runnable back) {
        this.module = module;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.number = number;
        this.contents = contents;
        this.back = back;
        this.size = menuSize(contents.length);
    }

    /** Copia de solo lectura de la mochila number. back: null = solo cierra. */
    public static void open(StaffModerationModule module, Player viewer, UUID ownerId,
                            String ownerName, int number, Runnable back) {
        if (!StaffAccess.has(viewer, PlayerVaultsMenu.VIEW_PERMISSION)) {
            viewer.sendMessage(Lang.get(viewer, "common.no-permission"));
            return;
        }
        if (!PlayerVaultsHook.isAvailable()) {
            viewer.sendMessage(Lang.get(viewer, "staff-moderation.vaults-no-plugin"));
            return;
        }
        ItemStack[] contents = PlayerVaultsHook.contents(ownerId, number);
        if (contents == null) {
            viewer.sendMessage(Lang.get(viewer, "staff-moderation.vaults-not-found",
                    "player", MM.escape(ownerName), "number", String.valueOf(number)));
            return;
        }
        Menu.open(viewer, new VaultContentsMenu(module, ownerId, ownerName, number, contents, back));
        viewer.sendActionBar(Lang.get(viewer, "staff-moderation.vaults-view-open"));
        module.audit(viewer, "vaultsee", ownerName + " #" + number);
    }

    /** Filas que ocupa el contenido (al menos una, como mucho seis). */
    static int contentRows(int slots) {
        return Math.min(MAX_SIZE / 9, Math.max(1, (slots + 8) / 9));
    }

    /**
     * Tamaño del menu: el contenido mas una fila de navegacion debajo. Una mochila de 6 filas
     * (54 huecos) ya llena el inventario mas grande que Minecraft deja abrir, asi que se muestra
     * entera SIN fila de navegacion: esconder la ultima fila para meter un boton seria enseñar
     * una mochila incompleta, justo lo que el staff no puede permitirse al revisar. Se cierra con
     * Esc como cualquier inventario.
     */
    static int menuSize(int slots) {
        int rows = contentRows(slots);
        return rows < MAX_SIZE / 9 ? (rows + 1) * 9 : MAX_SIZE;
    }

    /** Hueco del boton de volver/cerrar (centro de la fila de navegacion), o NO_SLOT. */
    static int exitSlot(int slots) {
        int rows = contentRows(slots);
        return rows < MAX_SIZE / 9 ? rows * 9 + 4 : NO_SLOT;
    }

    /** Hueco del boton de editar (extremo derecho de la fila de navegacion), o NO_SLOT. */
    static int editSlot(int slots) {
        int rows = contentRows(slots);
        return rows < MAX_SIZE / 9 ? rows * 9 + 8 : NO_SLOT;
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.vaults-view-title",
                "number", String.valueOf(number), "player", MM.escape(ownerName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.vaults-view-title",
                "number", String.valueOf(number), "player", MM.escape(ownerName));
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        int shown = Math.min(contents.length, contentRows(contents.length) * 9);
        for (int slot = 0; slot < shown; slot++) {
            inventory.setItem(slot, contents[slot] == null ? null : contents[slot].clone());
        }
        int exit = exitSlot(contents.length);
        if (exit != NO_SLOT) {
            inventory.setItem(exit, back != null ? MenuNavigation.back(viewer)
                    : MenuNavigation.close(viewer));
        }
        int edit = editSlot(contents.length);
        if (edit != NO_SLOT && StaffAccess.has(viewer, PlayerVaultsMenu.EDIT_PERMISSION)) {
            inventory.setItem(edit, MenuItems.item(Material.WRITABLE_BOOK,
                    Lang.raw(viewer, "staff-moderation.vaults-entry",
                            "number", String.valueOf(number)),
                    List.of(Lang.raw(viewer, "staff-moderation.vaults-entry-edit"))));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, PlayerVaultsMenu.VIEW_PERMISSION)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot != NO_SLOT && slot == exitSlot(contents.length)) {
            if (back != null) back.run();
            else player.closeInventory();
            return;
        }
        if (slot != NO_SLOT && slot == editSlot(contents.length)) {
            if (StaffAccess.has(player, PlayerVaultsMenu.EDIT_PERMISSION)) {
                PlayerVaultsMenu.editReal(module, player, ownerId, ownerName, number);
            }
            return;
        }
        // Shulkers dentro de la mochila: un clic abre su contenido, tambien en solo lectura.
        if (slot >= 0 && slot < contents.length && contents[slot] != null) {
            dev.chatalyst.gui.ShulkerViewMenu.tryOpen(player, ownerName, contents[slot],
                    () -> Menu.open(player, this));
        }
    }
}
