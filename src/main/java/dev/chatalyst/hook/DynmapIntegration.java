package dev.chatalyst.hook;

import dev.chatalyst.report.ReportManager;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Optional Dynmap marker integration for open Chatalyst reports. */
public final class DynmapIntegration {

    private static final String DEFAULT_SET_ID = "chatalyst.reports";

    private final JavaPlugin plugin;
    private volatile ReportManager reports;
    private volatile boolean enabled;
    private volatile boolean connected;
    private volatile boolean warned;
    private volatile Object markerApi;
    private volatile Object markerSet;
    private volatile Object markerIcon;
    /** Invalida reintentos/callbacks que pertenecen a una configuracion anterior. */
    private final AtomicLong generation = new AtomicLong();

    public DynmapIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable(ReportManager reports) {
        long currentGeneration = generation.incrementAndGet();
        this.reports = reports;
        this.enabled = plugin.getConfig().getBoolean("Dynmap.Reports.Enabled", false);
        this.connected = false;
        this.warned = false;
        this.markerApi = null;
        this.markerSet = null;
        this.markerIcon = null;
        if (!enabled) return;
        connect(0, currentGeneration);
    }

    public void reload() {
        if (reports != null) {
            enable(reports);
        }
    }

    public void disable() {
        generation.incrementAndGet();
        enabled = false;
        connected = false;
        markerApi = null;
        markerSet = null;
        markerIcon = null;
    }

    public void upsert(ReportManager.Report report) {
        if (!enabled || report == null) return;
        long currentGeneration = generation.get();
        FoliaTasks.global(plugin, () -> {
            if (!active(currentGeneration)) return;
            if (!ready()) {
                connect(0, currentGeneration);
                return;
            }
            if (report.status().isClosed() || !report.hasLocation()) {
                deleteNow(report.id());
                return;
            }
            upsertNow(report);
        });
    }

    public void delete(int id) {
        if (!enabled) return;
        long currentGeneration = generation.get();
        FoliaTasks.global(plugin, () -> {
            if (!active(currentGeneration)) return;
            if (ready()) deleteNow(id);
        });
    }

    private void connect(int attempt, long currentGeneration) {
        FoliaTasks.global(plugin, () -> {
            if (!active(currentGeneration)) return;
            try {
                Plugin dynmap = dynmapPlugin();
                if (dynmap == null) {
                    warnOnce("Dynmap report markers are enabled, but Dynmap is not installed.");
                    return;
                }
                Method markerApiMethod = dynmap.getClass().getMethod("getMarkerAPI");
                Object nextApi = markerApiMethod.invoke(dynmap);
                if (nextApi == null) {
                    retry(attempt, currentGeneration);
                    return;
                }
                Object nextSet = nextApi.getClass()
                        .getMethod("getMarkerSet", String.class)
                        .invoke(nextApi, setId());
                if (nextSet == null) {
                    nextSet = nextApi.getClass()
                            .getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class)
                            .invoke(nextApi, setId(), setLabel(), null, persistent());
                }
                if (nextSet == null) {
                    warnOnce("Dynmap Marker API did not create the Chatalyst marker set.");
                    return;
                }
                configureSet(nextSet);
                Object nextIcon = nextApi.getClass()
                        .getMethod("getMarkerIcon", String.class)
                        .invoke(nextApi, iconId());
                // La reflexion puede coincidir con un /cht reload. Un resultado de la
                // generacion vieja nunca debe reactivar una integracion ya deshabilitada.
                if (!active(currentGeneration)) return;
                markerApi = nextApi;
                markerSet = nextSet;
                markerIcon = nextIcon;
                connected = true;
                refreshOpenReports();
                plugin.getLogger().info("Hooked into Dynmap (report markers active).");
            } catch (ReflectiveOperationException | RuntimeException ex) {
                if (attempt < 5) {
                    retry(attempt, currentGeneration);
                } else {
                    warnOnce("Could not hook into Dynmap Marker API: " + ex.getMessage());
                }
            }
        });
    }

    private void retry(int attempt, long currentGeneration) {
        FoliaTasks.globalLater(plugin, () -> {
            if (active(currentGeneration)) connect(attempt + 1, currentGeneration);
        }, 20L * (attempt + 1L));
    }

    private boolean active(long expectedGeneration) {
        return enabled && generation.get() == expectedGeneration;
    }

    private void refreshOpenReports() {
        if (reports == null || !ready()) return;
        List<ReportManager.Report> open = reports.listOpen();
        Set<String> liveIds = new HashSet<>();
        for (ReportManager.Report report : open) {
            if (report.hasLocation()) {
                liveIds.add(markerId(report.id()));
                upsertNow(report);
            }
        }
        deleteStaleMarkers(liveIds);
    }

    private void upsertNow(ReportManager.Report report) {
        Object existing = findMarker(report.id());
        if (existing != null) {
            invoke(existing, "setLabel", label(report), true);
            invoke(existing, "setLocation", report.world(), report.x(), report.y(), report.z());
            invoke(existing, "setDescription", description(report));
            if (markerIcon != null) invoke(existing, "setMarkerIcon", markerIcon);
            return;
        }
        Object marker = createMarker(report);
        if (marker != null) {
            invoke(marker, "setDescription", description(report));
        }
    }

    private Object createMarker(ReportManager.Report report) {
        for (Method method : markerSet.getClass().getMethods()) {
            if (!method.getName().equals("createMarker") || method.getParameterCount() != 9) continue;
            try {
                return method.invoke(markerSet, markerId(report.id()), label(report), true,
                        report.world(), report.x(), report.y(), report.z(), markerIcon, persistent());
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private void deleteNow(int id) {
        Object marker = findMarker(id);
        if (marker != null) {
            invoke(marker, "deleteMarker");
        }
    }

    private void deleteStaleMarkers(Set<String> liveIds) {
        Object markers = invokeResult(markerSet, "getMarkers");
        if (!(markers instanceof Iterable<?> iterable)) return;
        for (Object marker : iterable) {
            Object id = invokeResult(marker, "getMarkerID");
            if (id instanceof String markerId && markerId.startsWith("chatalyst_report_")
                    && !liveIds.contains(markerId)) {
                invoke(marker, "deleteMarker");
            }
        }
    }

    private Object findMarker(int id) {
        if (!ready()) return null;
        return invokeResult(markerSet, "findMarker", markerId(id));
    }

    private void configureSet(Object set) {
        invoke(set, "setHideByDefault", plugin.getConfig().getBoolean("Dynmap.Reports.Hide_By_Default", false));
        invoke(set, "setLayerPriority", plugin.getConfig().getInt("Dynmap.Reports.Layer_Priority", 10));
        invoke(set, "setLabelShow", plugin.getConfig().getBoolean("Dynmap.Reports.Show_Labels", false));
    }

    private boolean ready() {
        return connected && markerSet != null;
    }

    private Plugin dynmapPlugin() {
        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        return dynmap != null ? dynmap : Bukkit.getPluginManager().getPlugin("Dynmap");
    }

    private String setId() {
        String configured = plugin.getConfig().getString("Dynmap.Reports.Set_Id", DEFAULT_SET_ID);
        return configured == null || configured.isBlank() ? DEFAULT_SET_ID : configured;
    }

    private String setLabel() {
        return plugin.getConfig().getString("Dynmap.Reports.Set_Label", "Chatalyst Reports");
    }

    private String iconId() {
        return plugin.getConfig().getString("Dynmap.Reports.Icon", "exclamation");
    }

    private boolean persistent() {
        return plugin.getConfig().getBoolean("Dynmap.Reports.Persistent", false);
    }

    private static String markerId(int id) {
        return "chatalyst_report_" + id;
    }

    private static String label(ReportManager.Report report) {
        return "<b>Report #" + report.id() + "</b> " + html(report.target());
    }

    private static String description(ReportManager.Report report) {
        return "<b>Chatalyst Report #" + report.id() + "</b><br>"
                + "Status: " + html(report.status().name()) + "<br>"
                + "Target: " + html(report.target()) + "<br>"
                + "Reporter: " + html(report.reporter()) + "<br>"
                + "Reason: " + html(report.reason()) + "<br>"
                + "Command: /reports info " + report.id();
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning(message);
    }

    private static void invoke(Object target, String name, Object... args) {
        invokeResult(target, name, args);
    }

    private static Object invokeResult(Object target, String name, Object... args) {
        if (target == null) return null;
        Method method = findMethod(target.getClass(), name, args.length);
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int args) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args) return method;
        }
        return null;
    }
}
