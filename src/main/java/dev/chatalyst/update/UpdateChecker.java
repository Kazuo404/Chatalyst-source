package dev.chatalyst.update;

import dev.chatalyst.ChatalystPlugin;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aviso de actualizaciones (NO descarga): al arrancar consulta de forma asincrona una URL
 * que devuelve la ultima version publicada, la compara con la actual y, si hay una mas
 * nueva, lo anuncia en consola y a los admins al entrar. La descarga real la gestiona el
 * marketplace (Polymart/BuiltByBit auto-update), que verifica la licencia del comprador;
 * hostear el jar aqui abriria la puerta a la pirateria.
 */
public final class UpdateChecker implements Listener {

    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final ChatalystPlugin plugin;
    private final String currentVersion;
    private volatile String latestVersion;
    private volatile boolean updateAvailable;

    // Acepta "0.2.0" a secas o {"version":"0.2.0"} (Polymart/BuiltByBit-style).
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");

    public UpdateChecker(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    /** Lanza la comprobacion en segundo plano si esta activada y hay URL configurada. */
    public void check() {
        if (!plugin.getConfig().getBoolean("Updates.Check", true)) return;
        String url = plugin.getConfig().getString("Updates.Url", "");
        if (url == null || url.isBlank()) return;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8)).build();
        final HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Chatalyst/" + currentVersion)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
        } catch (IllegalArgumentException badUrl) {
            // URI.create/uri() lanzan de forma SINCRONA: una Updates.Url mal escrita en
            // config no debe reventar el arranque. El exceptionally solo cubre el envio.
            plugin.getLogger().warning("Invalid Updates.Url in config; skipping update check.");
            return;
        }

        http.sendAsync(req, dev.chatalyst.util.LimitedBodyHandlers.ofString(
                        MAX_RESPONSE_BYTES, java.nio.charset.StandardCharsets.UTF_8))
                .thenAccept(resp -> {
                    if (resp.statusCode() != 200) return;
                    Matcher m = VERSION.matcher(resp.body());
                    if (!m.find()) return;
                    String latest = m.group(1);
                    if (isNewer(latest, currentVersion)) {
                        latestVersion = latest;
                        updateAvailable = true;
                        plugin.getLogger().info("A new version is available: "
                                + latest + " (you have " + currentVersion + "). "
                                + "Update from your marketplace download.");
                    }
                })
                .exceptionally(ex -> null); // sin red / URL mala: silencioso, no molesta
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!updateAvailable) return;
        if (!plugin.getConfig().getBoolean("Updates.Notify_Admins", true)) return;
        Player p = event.getPlayer();
        if (!p.hasPermission("chatalyst.admin")) return;
        var msg = dev.chatalyst.util.MM.full(
                "<#7B68A0>[<gradient:#9D4EDD:#E0AAFF>Chatalyst</gradient><#7B68A0>] "
                        + "<#C0B3D9>A new version <#FBF2FF>" + latestVersion
                        + " <#C0B3D9>is available <#7B68A0>(you have " + currentVersion + ")");
        String link = plugin.getConfig().getString("Updates.Download_Url", "");
        if (link != null && !link.isBlank()) {
            msg = msg.clickEvent(ClickEvent.openUrl(link));
        }
        p.sendMessage(msg);
    }

    /** Compara versiones tipo "1.2.3": true si {@code candidate} es mayor que {@code base}. */
    static boolean isNewer(String candidate, String base) {
        String[] a = candidate.split("\\.");
        String[] b = base.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
