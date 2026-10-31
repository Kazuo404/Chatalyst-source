package dev.chatalyst.security;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.CommandNames;
import dev.chatalyst.util.Sanitize;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Staff security audit trail.
 * Logs sensitive staff actions so admin activity is visible in console and the private
 * staff bot channel, reducing silent misuse and helping incident review.
 */
public final class StaffSecurityListener implements Listener {

    private final ChatalystPlugin plugin;
    // Volatile + lista inmutable: los comandos corren en hilos de region (Folia) mientras
    // /chatalyst reload reescribe este estado.
    private volatile boolean enabled;
    private volatile boolean auditCommands;
    private volatile List<String> audited = List.of();

    public StaffSecurityListener(ChatalystPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Staff_Security.Enabled", false);
        auditCommands = plugin.getConfig().getBoolean("Staff_Security.Audit_Commands", true);
        List<String> nextAudited = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("Staff_Security.Commands")) {
            String base = CommandNames.baseLabel(raw);
            if (!base.isEmpty()) nextAudited.add(base.toLowerCase(Locale.ROOT));
        }
        if (nextAudited.isEmpty()) {
            nextAudited.addAll(List.of(
                    "/spy", "/staffchat", "/mute", "/unmute", "/reports", "/report",
                    "/nick", "/formatedit", "/clearchat", "/chatlock", "/chatunlock",
                    "/slowmode", "/players", "/chatalyst", "/warnings"
            ));
        }
        audited = List.copyOf(nextAudited); // publicacion atomica
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || !auditCommands) return;
        Player actor = event.getPlayer();
        String message = event.getMessage();
        String base = CommandNames.baseLabel(message).toLowerCase(Locale.ROOT);
        if (base.isEmpty() || !audited.contains(base)) return;

        // La lista es configurable: un owner puede incluir /login, /otp, etc. Auditarlo no
        // debe convertir la consola y Discord en un almacen de credenciales. Redactamos ANTES
        // de stripInvisible para que un separador tabulado no se elimine pegando clave+comando.
        dev.chatalyst.log.LogManager logs = plugin.getLogManager();
        String protectedMessage = logs != null
                ? logs.redactSensitiveCommand(message)
                : dev.chatalyst.log.LogManager.redactAlwaysSensitiveCommand(message);
        String plain = Sanitize.stripInvisible(protectedMessage).trim();
        String line = actor.getName() + " executed " + plain;
        plugin.getLogger().warning("[StaffSecurity] " + line);
        if (plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert(line);
        }
        actor.sendMessage(Lang.get(actor, "staff-security.recorded"));
    }

    public void audit(org.bukkit.command.CommandSender sender, String action, String detail) {
        audit(sender != null ? sender.getName() : "unknown", action, detail);
    }

    public void audit(String actor, String action, String detail) {
        if (!enabled) return;
        String line = actor + " " + action + (detail == null || detail.isBlank() ? "" : ": " + detail);
        plugin.getLogger().warning("[StaffSecurity] " + line);
        if (plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert(line);
        }
    }
}
