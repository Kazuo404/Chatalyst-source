package dev.chatalyst.report;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Detalle de un reporte con botones de accion: reclamar, en espera, resolver, rechazar.
 * Cada boton llama a {@link ReportActions} (notifica a staff, reportante y Discord) y vuelve
 * a la lista. El equivalente visual de /reports claim|pending|resolve|reject.
 */
public final class ReportDetailMenu implements Menu {

    // Simetria: resumen arriba al centro (4, click = leer el hilo), acciones espejadas
    // 10/12/14/16 (o reabrir en el 13), secundarias 20/22/24 abajo.
    private static final int SUMMARY = 4;
    private static final int CLAIM = 10, PENDING = 12, RESOLVE = 14, REJECT = 16;
    private static final int REOPEN = 13, UNAVAILABLE_BACK = 22;

    private enum SecondaryAction {
        PRIORITY,
        MODERATE,
        BACK,
        DELETE
    }

    private final ReportActions actions;
    private final int id;
    private final int page;
    // Confirmacion de borrado "armada": el primer clic normal sobre Borrar arma el boton
    // (se pinta como "clic de nuevo para confirmar"); el segundo lo borra. Asi el borrado
    // usa el MISMO clic normal que el resto de botones y no depende del shift-click oculto
    // (que en algunos proxies/versiones se intercepta), que era el motivo de "no funciona".
    private final boolean deleteArmed;

    public ReportDetailMenu(ReportActions actions, int id) {
        this(actions, id, 0);
    }

    public ReportDetailMenu(ReportActions actions, int id, int page) {
        this(actions, id, page, false);
    }

    private ReportDetailMenu(ReportActions actions, int id, int page, boolean deleteArmed) {
        this.actions = actions;
        this.id = id;
        this.page = Math.max(0, page);
        this.deleteArmed = deleteArmed;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("reports.gui-title-detail", "id", id));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "reports.gui-title-detail", "id", id);
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        ReportManager.Report r = actions.reports().get(id);
        if (r == null || (r.priority() && !actions.canSeePriority(viewer))) {
            inv.setItem(13, ReportsMenu.named(Material.BARRIER, Lang.raw(viewer, "reports.gui-not-available"), List.of()));
            inv.setItem(UNAVAILABLE_BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
            return;
        }
        List<String> summary = new ArrayList<>(List.of(
                "<#C0B3D9>" + Lang.raw(viewer, "reports.gui-status") + ": " + ReportsMenu.statusWord(viewer, r.status())
                        + (r.priority() ? " " + Lang.raw(viewer, "reports.gui-priority") : ""),
                Lang.raw(viewer, "reports.gui-by", "reporter", r.reporter())
                        + " <#C0B3D9>· " + ReportsMenu.ago(viewer, r.time()),
                "",
                Lang.raw(viewer, "reports.gui-reason"),
                // Reason del reportante -> MiniMessage: escapar contra inyeccion de formato/acciones.
                "<#FBF2FF>" + MM.escape(ReportsMenu.trim(r.reason(), 48))));
        if (!r.handler().isEmpty()) {
            summary.add(Lang.raw(viewer, "reports.gui-handled-by",
                    "handler", MM.escape(r.handler())));
        }
        // La conversacion vive en el propio resumen: click para leer el hilo.
        int msgs = actions.conversation().count(id);
        summary.add("");
        summary.add(Lang.raw(viewer, "reports.gui-messages", "count", msgs));
        summary.add(Lang.raw(viewer, "reports.gui-read-thread"));
        summary.add(Lang.raw(viewer, "reports.gui-reply-hint", "id", id));
        inv.setItem(SUMMARY, ReportsMenu.named(r.priority() ? Material.REDSTONE : Material.PAPER,
                (r.priority() ? "<#FF4D6D>! " : "") + "<#FBF2FF>#" + r.id() + " <#C0B3D9>· <#FBF2FF>" + r.target(),
                summary));

        if (r.status().isClosed()) {
            // Ticket cerrado: solo se puede reabrir ("regresar").
            inv.setItem(REOPEN, ReportsMenu.named(Material.LEVER, Lang.raw(viewer, "reports.gui-reopen"),
                    List.of(Lang.raw(viewer, "reports.gui-reopen-lore"))));
        } else {
            inv.setItem(CLAIM, ReportsMenu.named(Material.WRITABLE_BOOK, Lang.raw(viewer, "reports.gui-claim"),
                    List.of(Lang.raw(viewer, "reports.gui-claim-lore"))));
            inv.setItem(PENDING, ReportsMenu.named(Material.CLOCK, Lang.raw(viewer, "reports.gui-pending"),
                    List.of(Lang.raw(viewer, "reports.gui-pending-lore"))));
            inv.setItem(RESOLVE, ReportsMenu.named(Material.LIME_DYE, Lang.raw(viewer, "reports.gui-resolve"),
                    List.of(Lang.raw(viewer, "reports.gui-resolve-lore"))));
            inv.setItem(REJECT, ReportsMenu.named(Material.RED_DYE, Lang.raw(viewer, "reports.gui-reject"),
                    List.of(Lang.raw(viewer, "reports.gui-reject-lore"))));
        }
        List<SecondaryAction> secondary = secondaryActions(viewer);
        for (int i = 0; i < secondary.size(); i++) {
            int slot = secondarySlot(i, secondary.size());
            switch (secondary.get(i)) {
                case PRIORITY -> inv.setItem(slot, ReportsMenu.named(Material.NETHER_STAR,
                        Lang.raw(viewer, r.priority() ? "reports.gui-remove-priority" : "reports.gui-mark-priority"),
                        List.of(Lang.raw(viewer, "reports.gui-priority-lore"))));
                case MODERATE -> inv.setItem(slot, ReportsMenu.named(Material.IRON_SWORD,
                        Lang.raw(viewer, "reports.gui-moderate", "player", MM.escape(r.target())),
                        List.of(Lang.raw(viewer, "reports.gui-moderate-lore"))));
                case BACK -> inv.setItem(slot, dev.chatalyst.gui.MenuNavigation.back(viewer));
                case DELETE -> inv.setItem(slot, deleteArmed
                        ? ReportsMenu.named(Material.RED_CONCRETE,
                                Lang.raw(viewer, "reports.gui-delete-confirm"),
                                List.of(Lang.raw(viewer, "reports.gui-delete-confirm-lore1"),
                                        Lang.raw(viewer, "reports.gui-delete-confirm-lore2")))
                        : ReportsMenu.named(Material.BARRIER,
                                Lang.raw(viewer, "reports.gui-delete"),
                                List.of(Lang.raw(viewer, "reports.gui-delete-lore1"),
                                        Lang.raw(viewer, "reports.gui-delete-lore2"))));
            }
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!actions.canManage(player)) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        String handler = player.getName();
        ReportManager.Report r = actions.reports().get(id);
        if (r == null || (r.priority() && !actions.canSeePriority(player))) {
            Menu.open(player, new ReportsMenu(actions, page));
            return;
        }
        SecondaryAction secondary = secondaryActionAt(slot, secondaryActions(player));
        if (secondary != null) {
            switch (secondary) {
                case PRIORITY -> {
                    actions.setPriority(id, !r.priority(), handler);
                    Menu.open(player, new ReportDetailMenu(actions, id, page));
                }
                case MODERATE -> moderateTarget(player, r);
                case BACK -> Menu.open(player, new ReportsMenu(actions, page));
                case DELETE -> {
                    // Confirmacion de seguridad para no borrar por accidente. Shift-click
                    // borra al instante (atajo). Con clic normal, el primero ARMA el boton
                    // y el segundo confirma: mismo clic que el resto de botones, sin depender
                    // del modificador oculto (que era lo que fallaba en algunos setups).
                    if (click.isShiftClick() || deleteArmed) {
                        boolean ok = actions.delete(id, handler);
                        if (!ok) player.sendMessage(Lang.get(player, "reports.unknown", "id", id));
                        Menu.open(player, new ReportsMenu(actions, page));
                    } else {
                        Menu.open(player, new ReportDetailMenu(actions, id, page, true));
                    }
                }
            }
            return;
        }
        switch (slot) {
            case CLAIM -> actions.claim(id, handler);
            case PENDING -> actions.pending(id, handler, "");
            case RESOLVE -> actions.resolve(id, handler, "");
            case REJECT -> actions.reject(id, handler, "");
            case REOPEN -> actions.reopen(id, handler);
            case SUMMARY -> {
                openConversation(player, r);
                return;
            }
            default -> {
                return;
            }
        }
        // Tras una accion volvemos a la lista (el reporte cerrado desaparece de ella).
        Menu.open(player, new ReportsMenu(actions, page));
    }

    // Abre un libro con el hilo de la conversacion del ticket (mas comodo que el chat).
    private void openConversation(Player player, ReportManager.Report r) {
        List<Component> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder("<black>"
                + Lang.raw(player, "reports.ticket-book-title", "id", r.id())
                + "\n<#7B68A0>"
                + MM.escape(ReportsMenu.trim(r.reason(), 60)) + "\n\n");
        var msgs = actions.conversation().get(r.id());
        if (msgs.isEmpty()) {
            page.append(Lang.raw(player, "reports.book-no-replies"));
        }
        int lines = 4;
        for (var m : msgs) {
            String line = (m.staff() ? "<dark_red>" + Lang.raw(player, "reports.book-staff")
                    : "<dark_blue>" + Lang.raw(player, "reports.book-reporter"))
                    // Texto de la conversacion (reportante y staff) -> MiniMessage: escapar.
                    + "<black>" + MM.escape(ReportsMenu.trim(m.text(), 80)) + "\n";
            lines += 2;
            if (lines > 13) { // nueva pagina del libro
                pages.add(MM.full(page.toString()));
                page = new StringBuilder();
                lines = 2;
            }
            page.append(line);
        }
        pages.add(MM.full(page.toString()));
        player.openBook(net.kyori.adventure.inventory.Book.book(
                Lang.get(player, "reports.ticket-book-title", "id", r.id()),
                Lang.get(player, "reports.ticket-book-author"), pages));
    }

    /**
     * Moderar al reportado sin escribir comandos: si sigue online abre el menu de gestion
     * normal; si ya se desconecto (el caso "no me dio tiempo a congelarlo"), abre el menu
     * offline, cuyo freeze/ban durable lo espera en su proximo login.
     */
    private void moderateTarget(Player staff, ReportManager.Report r) {
        var module = actions.plugin().getStaffModeration();
        if (module == null || !module.enabled()) return;
        Player online = dev.chatalyst.nick.NickManager.online(r.target());
        if (online != null && (!dev.chatalyst.util.Vanish.isVanished(online)
                || dev.chatalyst.staff.StaffAccess.canSeeVanished(staff))) {
            dev.chatalyst.command.PlayerManageMenu.open(
                    actions.plugin(), staff, online.getUniqueId(), 0);
            return;
        }
        // Un vanished que el staff no puede ver se modera por el menu offline: no delata
        // su presencia y las acciones lo alcanzan igual (los comandos resuelven por UUID).
        org.bukkit.OfflinePlayer offline =
                dev.chatalyst.nick.NickManager.resolveOffline(r.target());
        if (offline == null || !offline.hasPlayedBefore()) {
            staff.sendMessage(Lang.get(staff, "common.unknown-player",
                    "player", MM.escape(r.target())));
            return;
        }
        int reportId = id;
        int reportPage = page;
        dev.chatalyst.staff.OfflineManageMenu.open(module, staff, offline, r.target(),
                current -> Menu.open(current, new ReportDetailMenu(actions, reportId, reportPage)));
    }

    private boolean canModerate(Player viewer) {
        var module = actions.plugin().getStaffModeration();
        return module != null && module.enabled()
                && (dev.chatalyst.staff.StaffAccess.has(viewer, "chatalyst.command.freeze")
                        || dev.chatalyst.staff.StaffAccess.has(viewer, "chatalyst.command.ban"));
    }

    private List<SecondaryAction> secondaryActions(Player viewer) {
        List<SecondaryAction> out = new ArrayList<>();
        if (actions.canSeePriority(viewer)) {
            out.add(SecondaryAction.PRIORITY);
        }
        if (canModerate(viewer)) {
            out.add(SecondaryAction.MODERATE);
        }
        out.add(SecondaryAction.BACK);
        if (actions.canDelete(viewer)) {
            out.add(SecondaryAction.DELETE);
        }
        return out;
    }

    private int secondarySlot(int index, int size) {
        if (size == 1) return 22;
        if (size == 2) return index == 0 ? 20 : 24;
        if (size == 3) {
            return switch (index) {
                case 0 -> 20;
                case 1 -> 22;
                default -> 24;
            };
        }
        return switch (index) {
            case 0 -> 19;
            case 1 -> 21;
            case 2 -> 23;
            default -> 25;
        };
    }

    private SecondaryAction secondaryActionAt(int slot, List<SecondaryAction> actions) {
        for (int i = 0; i < actions.size(); i++) {
            if (slot == secondarySlot(i, actions.size())) {
                return actions.get(i);
            }
        }
        return null;
    }
}
