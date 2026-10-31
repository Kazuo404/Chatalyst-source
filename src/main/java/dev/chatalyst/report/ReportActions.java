package dev.chatalyst.report;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.DynmapIntegration;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/**
 * Acciones sobre reportes (reclamar / resolver) con su notificacion, compartidas por el
 * comando in-game (/reports) y el puente de Discord, para que ambos hagan lo mismo:
 * cambian el estado en {@link ReportManager} y avisan a staff online + consola + Discord.
 */
public final class ReportActions {

    private final ChatalystPlugin plugin;
    private final ReportManager reports;
    private final TicketConversation conversation;
    private DynmapIntegration dynmapIntegration;

    public ReportActions(ChatalystPlugin plugin, ReportManager reports, TicketConversation conversation) {
        this.plugin = plugin;
        this.reports = reports;
        this.conversation = conversation;
    }

    public ChatalystPlugin plugin() {
        return plugin;
    }

    public ReportManager reports() {
        return reports;
    }

    public TicketConversation conversation() {
        return conversation;
    }

    public void setDynmapIntegration(DynmapIntegration dynmapIntegration) {
        this.dynmapIntegration = dynmapIntegration;
    }

    /**
     * Añade un mensaje al hilo del ticket y avisa al otro lado. Si lo escribe el staff, se
     * notifica al reportante (in-game + DM de Discord); si lo escribe el reportante, se
     * notifica al staff. Devuelve false si el reporte no existe.
     */
    public boolean reply(int id, String author, boolean staff, String text) {
        ReportManager.Report r = reports.get(id);
        if (r == null || r.status().isClosed()) return false;
        if (!conversation.add(id, author, staff, text)) return false;
        if (staff) {
            // Aviso al reportante (in-game y, si existe el mapeo manual, por el bot).
            Player reporter = onlineReporter(r);
            if (reporter != null) {
                dev.chatalyst.util.FoliaTasks.entity(plugin, reporter, player -> {
                    player.sendMessage(MM.full(Lang.raw(player,
                                    "reports.reply-from-staff", "id", id),
                            Placeholder.unparsed("chatalyst_text", text)));
                    player.sendMessage(Lang.get(player, "reports.reply-ticket-hint", "id", id));
                });
            }
            if (r.reporterUuid() != null && !r.reporterUuid().isBlank()
                    && plugin.getConfig().getBoolean("Discord.Staff.DM_Reporter", true)) {
                try {
                    if (plugin.getDiscordBot() != null) {
                        plugin.getDiscordBot().dmLinkedUser(
                                java.util.UUID.fromString(r.reporterUuid()),
                                "Staff replied to your report #" + id + ": " + text);
                    }
                } catch (IllegalArgumentException ignore) {
                }
            }
        } else {
            // Aviso al staff (con el permiso de recepcion; si es prioritario, al de prioridad).
            String perm = r.priority() ? priorityPermission()
                    : plugin.getConfig().getString("Reports.Notify_Permission", "chatalyst.report.receive");
            dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                    p -> dev.chatalyst.staff.StaffAccess.has(p, perm),
                    p -> p.sendMessage(MM.full(Lang.raw(p, "reports.reply-from-reporter",
                                    "author", MM.escape(author), "id", id),
                            Placeholder.unparsed("chatalyst_text", text))));
            if (plugin.getStaffNotifier() != null) {
                plugin.getStaffNotifier().reportUpdate(
                        "Report #" + id + " — " + author + " replied: " + text, r.priority());
            }
        }
        return true;
    }

    /** Permiso para ver/gestionar tickets prioritarios (criticos). Configurable. */
    public String priorityPermission() {
        return plugin.getConfig().getString("Reports.Priority_Permission", "chatalyst.report.priority");
    }

    /** ¿El staff puede ver los tickets prioritarios (rango alto)? */
    public boolean canSeePriority(org.bukkit.permissions.Permissible who) {
        return has(who, priorityPermission());
    }

    /** Autorizacion base del panel; se reevalua en cada click, no solo al abrir /reports. */
    public boolean canManage(org.bukkit.permissions.Permissible who) {
        return has(who, "chatalyst.report.manage");
    }

    /** Aplica un cambio de estado (con verbo legible), notifica a staff y al reportante. */
    private boolean apply(int id, ReportManager.Status status, String verb, String handler, String note) {
        ReportManager.Report r = reports.setStatus(id, status, handler, note);
        if (r == null) return false;
        announce(r, verb, handler, note);
        syncDynmap(r);
        notifyReporter(r, verb, note);
        return true;
    }

    public boolean claim(int id, String handler) {
        boolean ok = apply(id, ReportManager.Status.CLAIMED, "claimed", handler, null);
        if (ok) audit(handler, "claim report #" + id);
        return ok;
    }

    public boolean resolve(int id, String handler, String note) {
        boolean ok = apply(id, ReportManager.Status.RESOLVED, "resolved", handler, note);
        if (ok) audit(handler, "resolve report #" + id, note);
        return ok;
    }

    /** En espera (esperando mas informacion o respuesta del reportante). */
    public boolean pending(int id, String handler, String note) {
        boolean ok = apply(id, ReportManager.Status.PENDING, "set to pending", handler, note);
        if (ok) audit(handler, "pending report #" + id, note);
        return ok;
    }

    /** Rechazado (no procede). */
    public boolean reject(int id, String handler, String note) {
        boolean ok = apply(id, ReportManager.Status.REJECTED, "rejected", handler, note);
        if (ok) audit(handler, "reject report #" + id, note);
        return ok;
    }

    /** Reabre ("regresa") un ticket cerrado. False si no existe o no estaba cerrado. */
    public boolean reopen(int id, String handler) {
        ReportManager.Report r = reports.reopen(id, handler);
        if (r == null) return false;
        announce(r, "reopened", handler, null);
        syncDynmap(r);
        notifyReporter(r, "reopened", null);
        audit(handler, "reopen report #" + id);
        return true;
    }

    /** Marca/quita la prioridad (mueve el ticket a la seccion critica). False si no existe. */
    public boolean setPriority(int id, boolean priority, String handler) {
        ReportManager.Report r = reports.setPriority(id, priority);
        if (r == null) return false;
        announce(r, priority ? "priority-on" : "priority-off", handler, null);
        syncDynmap(r);
        audit(handler, (priority ? "mark priority " : "remove priority ") + "report #" + id);
        return true;
    }

    /**
     * ¿Puede {@code who} borrar tickets? El dueño lo configura: por defecto solo el rango
     * alto (autorizacion maxima); si Delete_High_Rank_Only es false, tambien el soporte
     * normal. Reports.Allow_Delete apaga el borrado del todo.
     */
    public boolean canDelete(org.bukkit.permissions.Permissible who) {
        if (!plugin.getConfig().getBoolean("Reports.Allow_Delete", true)) return false;
        return plugin.getConfig().getBoolean("Reports.Delete_High_Rank_Only", true)
                ? has(who, priorityPermission())
                : has(who, plugin.getConfig().getString(
                        "Reports.Manage_Permission", "chatalyst.report.manage"));
    }

    private static boolean has(org.bukkit.permissions.Permissible who, String permission) {
        return who instanceof org.bukkit.command.CommandSender sender
                ? dev.chatalyst.staff.StaffAccess.has(sender, permission)
                : who.hasPermission(permission);
    }

    /** Borra un ticket. False si no existe (el permiso lo comprueba quien llama con canDelete). */
    public boolean delete(int id, String handler) {
        ReportManager.Report r = reports.get(id);
        if (r == null || !reports.delete(id)) return false;
        if (!conversation.clear(id)) {
            plugin.getLogger().warning("Report #" + id
                    + " was deleted, but its ticket conversation could not be removed.");
        }
        announce(r, "deleted", handler, null);
        if (dynmapIntegration != null) dynmapIntegration.delete(id);
        audit(handler, "delete report #" + id);
        return true;
    }

    private void syncDynmap(ReportManager.Report report) {
        if (dynmapIntegration != null) {
            dynmapIntegration.upsert(report);
        }
    }

    private void audit(String actor, String action) {
        audit(actor, action, "");
    }

    private void audit(String actor, String action, String note) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(actor, action, note);
        }
    }

    // Avisa a staff online, a la consola y a Discord. Si el ticket es prioritario, SOLO a
    // staff con el permiso de prioridad (rango alto) y al canal de bot de prioridad.
    private void announce(ReportManager.Report r, String verb, String handler, String note) {
        String perm = r.priority() ? priorityPermission()
                : plugin.getConfig().getString("Reports.Notify_Permission", "chatalyst.report.receive");
        // La nota y el nombre de quien atiende son texto libre, y los dos pueden llegar desde
        // Discord (un apodo de alli es lo que su dueño quiera): sin escapar, un
        // <click:run_command:...> escrito en cualquiera de ellos salia como boton en el chat
        // de todo el staff.
        String noteTail = (note == null || note.isBlank()) ? ""
                : " <#7B68A0>—<#C0B3D9> " + MM.escape(note);
        String safeHandler = MM.escape(handler == null ? "" : handler);
        String key = r.priority() ? "reports.notify-priority" : "reports.notify";
        // Renderizado POR ESPECTADOR: cada staff ve el aviso (y el verbo) en su idioma.
        java.util.function.Function<org.bukkit.command.CommandSender, Component> render = viewer ->
                MM.full(Lang.raw(viewer, key, "id", r.id(), "action", updateWord(viewer, verb),
                        "handler", safeHandler, "note", noteTail));
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                p -> dev.chatalyst.staff.StaffAccess.has(p, perm), p -> p.sendMessage(render.apply(p)));
        dev.chatalyst.util.FoliaTasks.global(plugin, () -> {
            org.bukkit.command.CommandSender console = plugin.getServer().getConsoleSender();
            Component rendered = render.apply(console);
            console.sendMessage(rendered);
            // Espejo a Discord (canal unico) en el idioma por defecto.
            if (plugin.getStaffNotifier() != null) {
                plugin.getStaffNotifier().reportUpdate(
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(rendered), r.priority());
            }
        });
    }

    // Avisa al jugador que reporto del cambio de estado de su ticket: in-game si esta
    // conectado, y por DM si su UUID esta en Discord.Linked_Accounts.
    private void notifyReporter(ReportManager.Report r, String verb, String note) {
        String tail = (note == null || note.isBlank()) ? "" : " · " + note;
        Player reporter = onlineReporter(r);
        if (reporter != null) {
            dev.chatalyst.util.FoliaTasks.entity(plugin, reporter, player ->
                    player.sendMessage(Lang.get(player, "reports.update-message",
                            "id", r.id(),
                            "status", updateWord(player, verb),
                            "note", tail.isEmpty() ? ""
                                    : " <#7B68A0>·<#C0B3D9> " + MM.escape(note))));
        }
        // DM por el bot al reportante mapeado (aunque este offline). Degrada sin mapeo.
        if (r.reporterUuid() != null && !r.reporterUuid().isBlank()
                && plugin.getConfig().getBoolean("Discord.Staff.DM_Reporter", true)) {
            try {
                if (plugin.getDiscordBot() != null) {
                    plugin.getDiscordBot().dmLinkedUser(
                            java.util.UUID.fromString(r.reporterUuid()),
                            "Your report #" + r.id() + " (" + r.targetDisplay() + ") was "
                                    + verb + "." + tail);
                }
            } catch (IllegalArgumentException ignore) {
                // uuid corrupto: nada
            }
        }
    }

    private String updateWord(org.bukkit.command.CommandSender viewer, String verb) {
        return switch (verb) {
            case "claimed" -> Lang.raw(viewer, "reports.update-claimed");
            case "resolved" -> Lang.raw(viewer, "reports.update-resolved");
            case "set to pending" -> Lang.raw(viewer, "reports.update-pending");
            case "rejected" -> Lang.raw(viewer, "reports.update-rejected");
            case "reopened" -> Lang.raw(viewer, "reports.update-reopened");
            case "deleted" -> Lang.raw(viewer, "reports.update-deleted");
            case "priority-on" -> Lang.raw(viewer, "reports.update-priority-on");
            case "priority-off" -> Lang.raw(viewer, "reports.update-priority-off");
            default -> verb;
        };
    }

    private Player onlineReporter(ReportManager.Report report) {
        String rawId = report.reporterUuid();
        if (rawId != null && !rawId.isBlank()) {
            try {
                return plugin.getServer().getPlayer(java.util.UUID.fromString(rawId));
            } catch (IllegalArgumentException corrupt) {
                return null;
            }
        }
        return plugin.getServer().getPlayerExact(report.reporter());
    }
}
