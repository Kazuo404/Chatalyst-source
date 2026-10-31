package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Notas de staff sobre un jugador: una por item, con quien la escribio y hace cuanto.
 * Añadir usa el prompt por chat (nunca sale al chat publico) y despacha /note real, que
 * re-verifica el permiso y deja auditoria. Shift+clic borra la nota.
 */
public final class StaffNotesMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int ADD = 47;
    private static final int BACK = 49;
    private static final int IDENTITY = 51;   // espejo de ADD: ficha de identidad
    private static final int NEXT = 53;

    private final StaffModerationModule module;
    private final UUID targetId;
    private final String targetName;
    private final Consumer<Player> back;
    private int page;

    public StaffNotesMenu(StaffModerationModule module, UUID targetId, String targetName,
                          Consumer<Player> back) {
        this(module, targetId, targetName, back, 0);
    }

    private StaffNotesMenu(StaffModerationModule module, UUID targetId, String targetName,
                           Consumer<Player> back, int page) {
        this.module = module;
        this.targetId = targetId;
        this.targetName = targetName;
        this.back = back;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.notes-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.notes-title",
                "player", MM.escape(targetName));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<StaffNotes.Note> notes = notes();
        if (notes.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.MAP,
                    Lang.raw(viewer, "staff-moderation.notes-empty"), List.of()));
        }
        int pages = Math.max(1, (notes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        long now = System.currentTimeMillis();
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                Math.max(0, Math.min(notes.size() - start, PAGE_SIZE)), 0);
        for (int index = start; index < Math.min(notes.size(), start + PAGE_SIZE); index++) {
            inventory.setItem(layout[index - start], noteItem(viewer, notes.get(index), now));
        }
        inventory.setItem(ADD, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "staff-moderation.notes-add"),
                List.of(Lang.raw(viewer, "staff-moderation.notes-add-lore"))));
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(IDENTITY, StaffAccess.has(viewer, "chatalyst.command.dupeip")
                ? MenuItems.item(Material.SPYGLASS, Lang.raw(viewer, "staff-identity.open-button"),
                        List.of(Lang.raw(viewer, "staff-identity.open-lore")))
                : MenuNavigation.unavailable(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            if (back != null) back.accept(player);
            else player.closeInventory();
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new StaffNotesMenu(module, targetId, targetName, back, page - 1));
            return;
        }
        int pages = Math.max(1, (notes().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT && page + 1 < pages) {
            Menu.open(player, new StaffNotesMenu(module, targetId, targetName, back, page + 1));
            return;
        }
        if (!StaffAccess.has(player, "chatalyst.staff.notes")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == IDENTITY) {
            if (StaffAccess.has(player, "chatalyst.command.dupeip")) {
                dev.chatalyst.staff.lookup.IdentityMenu.open(module, player, targetId, targetName, false,
                        p -> Menu.open(p, new StaffNotesMenu(module, targetId, targetName, back, page)));
            }
            return;
        }
        if (slot == ADD) {
            // El prompt captura la siguiente linea de chat y despacha "/note <player> <texto>".
            // Namespaced: siempre el /note de Chatalyst, gane quien gane la etiqueta.
            module.reasonPrompt().begin(player, "chatalyst:note " + targetName, targetName);
            return;
        }
        List<StaffNotes.Note> notes = notes();
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                Math.max(0, Math.min(notes.size() - page * PAGE_SIZE, PAGE_SIZE)), 0);
        if (local < 0) return;
        int index = page * PAGE_SIZE + local;
        if (!click.isShiftClick()) return; // solo shift borra: un clic de lectura no destruye
        StaffNotes.Note note = notes.get(index);
        if (!module.notes().remove(targetId, note.time())) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        module.audit(player, "note remove", targetName + " " + note.text());
        player.sendMessage(Lang.get(player, "staff-moderation.note-removed",
                "player", MM.escape(targetName)));
        Menu.open(player, new StaffNotesMenu(module, targetId, targetName, back, page));
    }

    // Mas reciente primero, como el historial de castigos.
    private List<StaffNotes.Note> notes() {
        List<StaffNotes.Note> out = new ArrayList<>(module.notes().list(targetId));
        java.util.Collections.reverse(out);
        return out;
    }

    private ItemStack noteItem(Player viewer, StaffNotes.Note note, long now) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "staff-moderation.history-entry-when",
                "duration", TimeUtil.formatElapsed(now - note.time())));
        lore.add(Lang.raw(viewer, "staff-moderation.notes-by",
                "staff", MM.escape(note.staff())));
        lore.add("");
        lore.add(Lang.raw(viewer, "staff-moderation.notes-delete"));
        return MenuItems.item(Material.PAPER,
                "<#FBF2FF>" + MM.escape(note.text()), lore);
    }
}
