package dev.chatalyst.book;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.AdminMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
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

/** Paginated in-game library for opening and editing server books. */
public final class BookAdminMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    // Fila inferior simetrica: flechas prev/next en las esquinas (45 · 53), back y "nuevo"
    // flanqueando el centro (47 · 51). Antes back (48) quedaba descolgado del centro.
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_BACK = 47;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEW = 51;
    private static final int SLOT_NEXT = 53;

    private final ChatalystPlugin plugin;
    private final BookManager books;
    private int page;

    public BookAdminMenu(ChatalystPlugin plugin, BookManager books, int page) {
        this.plugin = plugin;
        this.books = books;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("book-menu.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "book-menu.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<String> names = books.names();
        int pages = Math.max(1, (names.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE;
        int end = Math.min(names.size(), start + PAGE_SIZE);
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int index = start; index < end; index++) {
            String name = names.get(index);
            BookManager.BookDef definition = books.get(name);
            if (definition != null) {
                inventory.setItem(layout[index - start], bookItem(viewer, name, definition));
            }
        }
        if (page > 0) inventory.setItem(SLOT_PREVIOUS,
                MenuNavigation.previous(viewer, page + 1, pages));
        inventory.setItem(SLOT_BACK, MenuNavigation.home(viewer));
        inventory.setItem(SLOT_PAGE, MenuNavigation.page(viewer, page + 1, pages));
        inventory.setItem(SLOT_NEW,
                control(viewer, Material.WRITABLE_BOOK, "book-menu.new"));
        if (end < names.size()) inventory.setItem(SLOT_NEXT,
                MenuNavigation.next(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission("chatalyst.book.admin")) return;
        if (slot == SLOT_PREVIOUS && page > 0) {
            Menu.open(player, new BookAdminMenu(plugin, books, page - 1));
            return;
        }
        if (slot == SLOT_BACK) {
            Menu.open(player, new AdminMenu(plugin));
            return;
        }
        if (slot == SLOT_NEW) {
            player.closeInventory();
            player.performCommand("book new");
            return;
        }
        List<String> names = books.names();
        int local = dev.chatalyst.gui.MenuLayout.indexAt(slot, Math.max(0, Math.min(names.size() - page * PAGE_SIZE, PAGE_SIZE)), 0);
        int index = page * PAGE_SIZE + local;
        if (slot == SLOT_NEXT && (page + 1) * PAGE_SIZE < names.size()) {
            Menu.open(player, new BookAdminMenu(plugin, books, page + 1));
        } else if (local >= 0 && index < names.size()) {
            String name = names.get(index);
            player.closeInventory();
            player.performCommand((click.isShiftClick() ? "book edit " : "book open ") + name);
        }
    }

    private static ItemStack bookItem(Player viewer, String name,
            BookManager.BookDef definition) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "book-menu.id", "book", name));
        lore.add(Lang.raw(viewer, "book-menu.author", "author", definition.author()));
        lore.add(Lang.raw(viewer, "book-menu.pages", "pages", definition.pages().size()));
        String permission = definition.permission().isEmpty()
                ? Lang.raw(viewer, "book-menu.public") : definition.permission();
        lore.add(Lang.raw(viewer, "book-menu.permission", "permission", permission));
        lore.add("");
        lore.add(Lang.raw(viewer, "book-menu.open"));
        lore.add(Lang.raw(viewer, "book-menu.edit"));
        return MenuItems.item(Material.WRITTEN_BOOK, definition.title(), lore);
    }

    private static ItemStack control(Player viewer, Material material, String key) {
        return MenuItems.item(material, Lang.raw(viewer, key), List.of());
    }
}
