package dev.chatalyst.report;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GUI in-game con los reportes abiertos: un item por reporte, click para abrir el detalle
 * (donde se reclama / pone en espera / resuelve / rechaza). El equivalente visual de /reports.
 */
public final class ReportsMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int PAGE_INFO = 49;
    private static final int NEXT = 53;

    private final ReportActions actions;
    private final List<ReportManager.Report> shown = new ArrayList<>();
    private int page;
    private boolean hasPrevious;
    private boolean hasNext;

    public ReportsMenu(ReportActions actions) {
        this(actions, 0);
    }

    public ReportsMenu(ReportActions actions, int page) {
        this.actions = actions;
        this.page = Math.max(0, page);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("reports.gui-title-open"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "reports.gui-title-open");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        // Los tickets prioritarios (criticos) solo se muestran a rango alto.
        boolean seePriority = actions.canSeePriority(viewer);
        List<ReportManager.Report> visible = new ArrayList<>();
        for (ReportManager.Report r : actions.reports().listOpen()) {
            if (r.priority() && !seePriority) continue;
            visible.add(r);
        }
        if (visible.isEmpty()) {
            hasPrevious = false;
            hasNext = false;
            // PAPER y no cristal: icono neutro que Bedrock (Geyser) muestra bien.
            inv.setItem(22, named(Material.PAPER, Lang.raw(viewer, "reports.gui-no-open"), List.of()));
            inv.setItem(PAGE_INFO, MenuNavigation.page(viewer, 1, 1));
            return;
        }

        int maxPage = (visible.size() - 1) / PAGE_SIZE;
        page = Math.min(page, maxPage);
        hasPrevious = page > 0;
        hasNext = page < maxPage;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, visible.size());
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, end - start), 0);
        for (int i = start; i < end; i++) {
            ReportManager.Report r = visible.get(i);
            shown.add(r);
            inv.setItem(layout[i - start], icon(viewer, r));
        }

        int pages = maxPage + 1;
        if (hasPrevious) inv.setItem(PREVIOUS,
                MenuNavigation.previous(viewer, page + 1, pages));
        inv.setItem(PAGE_INFO, MenuNavigation.page(viewer, page + 1, pages));
        if (hasNext) inv.setItem(NEXT, MenuNavigation.next(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!actions.canManage(player)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == PREVIOUS && hasPrevious) {
            Menu.open(player, new ReportsMenu(actions, page - 1));
            return;
        }
        if (slot == NEXT && hasNext) {
            Menu.open(player, new ReportsMenu(actions, page + 1));
            return;
        }
        if (dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0) >= 0) {
            Menu.open(player, new ReportDetailMenu(actions, shown.get(dev.chatalyst.gui.MenuLayout.indexAt(slot, shown.size(), 0)).id(), page));
        }
    }

    static ItemStack icon(Player viewer, ReportManager.Report r) {
        // Los prioritarios destacan con redstone rojo; el resto por estado.
        Material mat = r.priority() ? Material.REDSTONE : switch (r.status()) {
            case CLAIMED -> Material.WRITABLE_BOOK;
            case PENDING -> Material.CLOCK;
            default -> Material.PAPER;
        };
        List<String> lore = List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "reports.gui-status") + ": " + statusWord(viewer, r.status())
                        + (r.priority() ? " " + Lang.raw(viewer, "reports.gui-priority") : ""),
                Lang.raw(viewer, "reports.gui-reported-by", "reporter", r.reporter()),
                "<#C0B3D9>" + ago(viewer, r.time()),
                "",
                Lang.raw(viewer, "reports.gui-reason"),
                // El reason lo escribe el reportante y esto se parsea como MiniMessage (MM.full
                // en named()): escapar evita que un reporte con <click>/<color> inyecte formato
                // o acciones falsas en la GUI del staff. (trim primero, sobre el texto crudo.)
                "<#FBF2FF>" + MM.escape(trim(r.reason(), 40)),
                "",
                Lang.raw(viewer, "reports.gui-click-manage"));
        return named(mat, (r.priority() ? "<#FF4D6D>! " : "") + "<#FBF2FF>#" + r.id() + " <#C0B3D9>· <#FBF2FF>" + r.target(), lore);
    }

    /** Delegado en MenuItems: antes era una copia local que se saltaba la paleta. */
    static ItemStack named(Material mat, String name, List<String> loreLines) {
        return dev.chatalyst.gui.MenuItems.item(mat, name, loreLines);
    }

    static String statusWord(Player viewer, ReportManager.Status s) {
        return switch (s) {
            case OPEN -> Lang.raw(viewer, "reports.gui-st-open");
            case CLAIMED -> Lang.raw(viewer, "reports.gui-st-claimed");
            case PENDING -> Lang.raw(viewer, "reports.gui-st-pending");
            case RESOLVED -> Lang.raw(viewer, "reports.gui-st-resolved");
            case REJECTED -> Lang.raw(viewer, "reports.gui-st-rejected");
        };
    }

    static String ago(Player viewer, long time) {
        long mins = Duration.ofMillis(System.currentTimeMillis() - time).toMinutes();
        if (mins < 1) return Lang.raw(viewer, "reports.gui-ago-now");
        if (mins < 60) return Lang.raw(viewer, "reports.gui-ago-min", "n", mins);
        long hours = mins / 60;
        return hours < 24 ? Lang.raw(viewer, "reports.gui-ago-hour", "n", hours)
                : Lang.raw(viewer, "reports.gui-ago-day", "n", hours / 24);
    }

    static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
