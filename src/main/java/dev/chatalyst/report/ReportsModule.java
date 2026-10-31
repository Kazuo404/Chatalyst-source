package dev.chatalyst.report;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.hook.DynmapIntegration;
import dev.chatalyst.module.Module;

/**
 * Modulo de reportes/tickets: managers, acciones de staff, integracion con Dynmap y los
 * comandos /report, /reports y /ticket.
 */
public final class ReportsModule implements Module {

    private final ChatalystPlugin plugin;
    private final StorageManager storage;
    private ReportManager reports;
    private TicketConversation conversation;
    private ReportActions actions;
    private DynmapIntegration dynmap;

    public ReportsModule(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public String name() {
        return "reports";
    }

    @Override
    public void enable() {
        reports = new ReportManager(storage);
        conversation = new TicketConversation(storage);
        actions = new ReportActions(plugin, reports, conversation);
        dynmap = new DynmapIntegration(plugin);
        dynmap.enable(reports);
        actions.setDynmapIntegration(dynmap);
        plugin.registerCommand("report", new ReportCommand(plugin, reports, dynmap));
        plugin.registerCommand("reports", new ReportsCommand(actions));
        plugin.registerCommand("ticket", new TicketCommand(actions));
    }

    @Override
    public void disable() {
        if (reports != null) {
            reports.shutdown();
        }
        if (conversation != null) {
            conversation.shutdown();
        }
        if (dynmap != null) {
            dynmap.disable();
        }
    }

    @Override
    public void reload() {
        if (dynmap != null) {
            dynmap.reload();
        }
    }

    public ReportManager reports() {
        return reports;
    }

    public TicketConversation conversation() {
        return conversation;
    }

    public ReportActions actions() {
        return actions;
    }

    public DynmapIntegration dynmap() {
        return dynmap;
    }
}
